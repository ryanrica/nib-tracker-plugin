package com.nibtracker;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * The character's goals from Nib Tracker (settings.goals), their progress, and a pop-up and chat line when one
 * completes. Progress is worked out by GoalProgress for the built-in goal types; for the rest the server's is shown.
 * Client thread unless noted.
 */
@Singleton
class GoalTracker
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private NibTrackerConfig config;

	@Inject
	private GoalPopup popup;

	private EventSink sink;
	private GoalsPanel panel;

	private List<GoalProgress.Goal> goals = Collections.emptyList();
	private JsonElement lastGoals;
	private String planner;
	private boolean received;
	/* False while the server is older than the goals feed (its settings have no "goals"). */
	private boolean serverGoals;
	private final GoalProgress.Facts facts = new GoalProgress.Facts();
	/* Canonical item id -> quantity, per container (bank, inventory, worn). */
	private final Map<Integer, Map<Integer, Long>> containers = new HashMap<>();
	/* Whether each goal was done when last worked out this session: a completion is a change from false to true. */
	private final Map<String, Boolean> wasDone = new HashMap<>();
	private final Set<String> announced = new HashSet<>();
	private volatile boolean dirty;
	private boolean questsDirty;
	/* What the panel shows now, so it's only rebuilt when something on it changes. */
	private String shown;

	void start(EventSink sink, GoalsPanel panel)
	{
		this.sink = sink;
		this.panel = panel;
		dirty = true;
	}

	/** Logged in and stats loaded. */
	void login()
	{
		dirty = true;
		questsDirty = true;
	}

	/** Any thread. Shows the panel again on the next tick, e.g. after the sync settings change. */
	void refresh()
	{
		dirty = true;
	}

	/** Logged out, hopped, or the plugin stopped: everything here belonged to that character. */
	void reset()
	{
		goals = Collections.emptyList();
		lastGoals = null;
		planner = null;
		received = false;
		serverGoals = false;
		facts.xp.clear();
		facts.levels.clear();
		facts.kc.clear();
		facts.clog.clear();
		facts.quests.clear();
		facts.bank.clear();
		facts.carried.clear();
		facts.bankSeen = false;
		containers.clear();
		wasDone.clear();
		announced.clear();
		popup.reset();
		dirty = true;
	}

	/** Any thread. New settings from the server, and the planner character this one syncs into. */
	void settingsChanged(JsonObject settings, String plannerAccount)
	{
		clientThread.invoke(() -> {
			received = true;
			serverGoals = settings.has("goals");
			planner = plannerAccount;
			JsonElement g = settings.get("goals");
			if (!sink.allowed("goals", true))
			{
				g = null;
			}
			if (!Objects.equals(g, lastGoals))
			{
				lastGoals = g;
				goals = GoalProgress.parse(g);
				questsDirty = true;
			}
			JsonElement aliases = settings.get("kcAliases");
			if (aliases != null && aliases.isJsonObject())
			{
				Map<String, String> m = new HashMap<>();
				for (Map.Entry<String, JsonElement> e : aliases.getAsJsonObject().entrySet())
				{
					if (e.getValue().isJsonPrimitive())
					{
						m.put(e.getKey(), e.getValue().getAsString());
					}
				}
				facts.kcAliases = m;
			}
			dirty = true;
		});
	}

	/** A chat event the plugin parsed (whether or not it's sent). */
	void onParsed(String type, JsonObject data)
	{
		switch (type)
		{
			case "kc":
				facts.kc.merge(GoalProgress.metric(data.get("boss").getAsString(), facts.kcAliases), data.get("count").getAsInt(), Math::max);
				break;
			case "clog_new":
				facts.clog.add(data.get("item").getAsString().toLowerCase(Locale.ROOT));
				break;
			case "quest_done":
				questsDirty = true;
				break;
			default:
				return;
		}
		dirty = true;
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		dirty = true;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		int id = e.getContainerId();
		if (id != InventoryID.BANK && id != InventoryID.INV && id != InventoryID.WORN)
		{
			return;
		}
		Map<Integer, Long> counts = new HashMap<>();
		ItemContainer c = e.getItemContainer();
		if (c != null)
		{
			for (Item it : c.getItems())
			{
				if (it.getId() > 0 && it.getQuantity() > 0)
				{
					counts.merge(itemManager.canonicalize(it.getId()), (long) it.getQuantity(), Long::sum);
				}
			}
		}
		containers.put(id, counts);
		dirty = true;
	}

	/** Once a game tick: works the goals out again if anything changed. */
	void tick()
	{
		if (!dirty)
		{
			return;
		}
		dirty = false;
		if (!sink.active() || goals.isEmpty())
		{
			refreshPanel(Collections.emptyList());
			return;
		}
		readFacts();
		List<GoalsPanel.Row> rows = new ArrayList<>();
		for (GoalProgress.Goal g : goals)
		{
			GoalProgress.Result r = GoalProgress.compute(g, facts);
			Boolean before = wasDone.put(g.id, r.done);
			if (Boolean.FALSE.equals(before) && r.done && announced.add(g.id))
			{
				completed(g);
			}
			rows.add(row(g, r));
		}
		refreshPanel(rows);
	}

	/** A goal as the panel shows it: its section, icon and wiki page come from its type and parameters. */
	private static GoalsPanel.Row row(GoalProgress.Goal g, GoalProgress.Result r)
	{
		String category = GoalProgress.category(g.type);
		Skill skill = null;
		String item = null;
		String page = null;
		switch (g.type)
		{
			case "skill_level":
			case "skill_xp":
				skill = skill(g.str("skill"));
				page = skill != null ? skill.getName() + " training" : null;
				break;
			case "base_level":
				page = "Skills";
				break;
			case "kc":
				skill = Skill.SLAYER;
				page = g.str("boss").replace('_', ' ');
				break;
			case "quest":
				page = g.str("quest") + "/Quick guide";
				break;
			case "clog_item":
			case "item_owned":
				item = g.str("item");
				page = item;
				break;
			case "diary_task":
			case "diary_tier":
				page = g.str("region") + " Diary";
				break;
			case "combat_achievement":
				skill = Skill.ATTACK;
				page = g.str("task");
				break;
			default:
				break;
		}
		return new GoalsPanel.Row(category, g.title, r.detail, r.hint, r.progress, r.done, g.points, skill,
			item == null || item.isEmpty() ? null : item, GoalsPanel.fallbackItem(category), wiki(page));
	}

	private static Skill skill(String name)
	{
		String key = GoalProgress.skillKey(name);
		for (Skill s : Skill.values())
		{
			if (s.getName().equalsIgnoreCase(key))
			{
				return s;
			}
		}
		return null;
	}

	/** The OSRS Wiki page for a name (its search finds the closest page when the name isn't exact). */
	private static String wiki(String page)
	{
		if (page == null || page.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return "https://oldschool.runescape.wiki/w/Special:Search?search=" + URLEncoder.encode(page.trim(), "UTF-8");
		}
		catch (UnsupportedEncodingException e)
		{
			return null;
		}
	}

	private void readFacts()
	{
		for (Skill s : Skill.values())
		{
			if (s != Skill.OVERALL)
			{
				String k = s.getName().toLowerCase(Locale.ROOT);
				facts.xp.put(k, (long) client.getSkillExperience(s));
				facts.levels.put(k, client.getRealSkillLevel(s));
			}
		}
		boolean items = false;
		for (GoalProgress.Goal g : goals)
		{
			if ("item_owned".equals(g.type))
			{
				items = true;
			}
			else if ("quest".equals(g.type) && questsDirty)
			{
				readQuest(g.str("quest"));
			}
		}
		questsDirty = false;
		if (items)
		{
			facts.bankSeen = containers.containsKey(InventoryID.BANK);
			facts.bank.clear();
			facts.carried.clear();
			names(containers.get(InventoryID.BANK), facts.bank);
			names(containers.get(InventoryID.INV), facts.carried);
			names(containers.get(InventoryID.WORN), facts.carried);
		}
	}

	private void readQuest(String name)
	{
		for (Quest q : Quest.values())
		{
			if (q.getName().equalsIgnoreCase(name))
			{
				facts.quests.put(name.toLowerCase(Locale.ROOT), q.getState(client).name());
				return;
			}
		}
	}

	private void names(Map<Integer, Long> counts, Map<String, Long> into)
	{
		if (counts == null)
		{
			return;
		}
		for (Map.Entry<Integer, Long> e : counts.entrySet())
		{
			into.merge(itemManager.getItemComposition(e.getKey()).getName().toLowerCase(Locale.ROOT), e.getValue(), Long::sum);
		}
	}

	private void completed(GoalProgress.Goal g)
	{
		String points = g.points > 0 ? " (+" + g.points + " points)" : "";
		if (config.goalChat())
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.runeLiteFormattedMessage(new ChatMessageBuilder()
					.append(ChatColorType.HIGHLIGHT).append("Nib Tracker goal complete: ")
					.append(ChatColorType.NORMAL).append(g.title + points)
					.build())
				.build());
		}
		if (config.goalPopups())
		{
			popup.show("Goal complete!", "<col=ffffff>" + Text.escapeJagex(g.title) + "</col>"
				+ (g.points > 0 ? "<br><br>+" + g.points + " Nib Tracker points" : ""));
		}
	}

	private void refreshPanel(List<GoalsPanel.Row> rows)
	{
		if (panel == null)
		{
			return;
		}
		String title = planner != null && sink.active() ? "Goals: " + planner : "Goals";
		String message;
		if (!config.syncEnabled() || config.pluginKey().trim().isEmpty())
		{
			message = "Turn on Sync to Nib Tracker and paste your plugin key in this plugin's settings to see your goals here.";
		}
		else if (!sink.active())
		{
			message = "Log in to see this character's goals.";
		}
		else if (!received)
		{
			message = "Connecting to Nib Tracker...";
		}
		else if (planner == null)
		{
			message = "Choose which planner character this one syncs into on Nib Tracker's Account page.";
		}
		else if (!serverGoals)
		{
			message = "Nib Tracker's server isn't sending goals yet. It needs updating (plugin.sql).";
		}
		else if (rows.isEmpty())
		{
			message = "No open goals. Add some in Nib Tracker, or open it once with this character selected so it sends them.";
		}
		else
		{
			message = null;
		}
		StringBuilder key = new StringBuilder(title).append('|').append(message);
		for (GoalsPanel.Row r : rows)
		{
			key.append('|').append(r.title).append(r.detail).append(r.hint).append(Math.round(r.progress * 1000)).append(r.done);
		}
		if (key.toString().equals(shown))
		{
			return;
		}
		shown = key.toString();
		SwingUtilities.invokeLater(() -> panel.show(title, message, rows));
	}
}
