package com.nibtracker;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemPrice;

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

	@Inject
	private SlayerTracker slayer;

	@Inject
	private GoalOverlay overlay;

	@Inject
	private ConfigManager configManager;

	@Inject
	private SkillIconManager skillIcons;

	/* The id the current Slayer task has in the panel and on screen. */
	static final String SLAYER_TASK = "slayer-task";
	/* Ids for the daily tasks and farming icons on the game screen. */
	static final String DAILIES = "dailies";
	static final String FARMING = "farming";

	/* Daily tasks and farm timers from the server; tasks ticked off here (id -> when next due) until the server has them. */
	private List<Routines.Daily> dailies = Collections.emptyList();
	private List<Routines.Patch> farm = Collections.emptyList();
	private final Map<String, Long> tickedOff = new HashMap<>();
	/* Whether each daily task was due when last looked at, so one getting done can have a pop-up. */
	private final Map<String, Boolean> dailyWasDue = new HashMap<>();
	/* Titles in on-screen hover text. */
	private static final Color TIP_TITLE = new Color(0xFF, 0x98, 0x1F);
	private long lastMinute;
	private static BufferedImage calendar;

	private EventSink sink;
	private int slayerSeen = -1;
	/* Icons for the on-screen goals, and item ids found by name, so each is looked up once. */
	private final Map<String, BufferedImage> overlayIcons = new HashMap<>();
	private final Map<String, Integer> itemIds = new HashMap<>();
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
	/* Goals finished while RuneLite has been open (kept through logouts and hops). Only these show as Completed: goals
	   finished in an earlier session stay out of the panel, so the list never builds up. */
	private final Set<String> finishedThisSession = new HashSet<>();
	private volatile boolean dirty;
	private boolean questsDirty;
	/* What the panel shows now, so it's only rebuilt when something on it changes. */
	private String shown;

	void start(EventSink sink, GoalsPanel panel)
	{
		finishedThisSession.clear();
		this.sink = sink;
		this.panel = panel;
		panel.onPin(this::togglePin);
		panel.onDailyDone(this::dailyDone);
		panel.onRegion(this::toggleRegion);
		dirty = true;
	}

	/** Swing thread. Puts a goal on the game screen, or takes it off; remembered in the plugin's settings. */
	void togglePin(String id)
	{
		Set<String> pins = pins();
		if (!pins.remove(id))
		{
			pins.add(id);
		}
		configManager.setConfiguration(NibTrackerConfig.GROUP, NibTrackerConfig.PINNED_GOALS, String.join(",", pins));
		dirty = true;
	}

	private Set<String> pins()
	{
		Set<String> out = new LinkedHashSet<>();
		for (String s : config.pinnedGoals().split(","))
		{
			if (!s.trim().isEmpty())
			{
				out.add(s.trim());
			}
		}
		return out;
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
		overlay.set(Collections.emptyList());
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
			dailies = Routines.dailies(settings.get("dailies"));
			farm = Routines.farm(settings.get("farm"));
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
		if (id != InventoryID.BANK && id != InventoryID.SEED_VAULT && id != InventoryID.INV && id != InventoryID.WORN)
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
		if (slayer.version() != slayerSeen)
		{
			slayerSeen = slayer.version();
			dirty = true;
		}
		// Timers count down a minute at a time.
		long minute = System.currentTimeMillis() / Routines.MINUTE;
		if (minute != lastMinute)
		{
			lastMinute = minute;
			dirty = true;
		}
		if (!dirty)
		{
			return;
		}
		dirty = false;
		if (!sink.active())
		{
			overlay.set(Collections.emptyList());
			refreshPanel(Collections.emptyList(), new GoalsPanel.Extras());
			return;
		}
		List<GoalsPanel.Row> rows = goals.isEmpty() ? new ArrayList<>() : goalRows();
		Set<String> pins = pins();
		List<GoalOverlay.Entry> onScreen = new ArrayList<>();
		for (GoalsPanel.Row r : rows)
		{
			r.pinned = pins.contains(r.id);
			// Finished goals leave the screen by themselves, so it only shows what's still to do.
			if (r.pinned && !r.done)
			{
				// The detail line: what to do (a combat achievement's task) or what's left ("250,964 XP to go").
				String detail = !r.info.isEmpty() ? r.info : r.hint;
				onScreen.add(new GoalOverlay.Entry(overlayIcon(r), r.title, GoalProgress.shortText(r.detail, r.progress, false), detail,
					r.progress, false, tooltip(r, r.info, r.travel)));
			}
		}
		long now = System.currentTimeMillis();
		GoalsPanel.Extras extras = extras(now, pins, onScreen);
		overlay.set(onScreen);
		refreshPanel(rows, extras);
	}

	/** Goals as panel rows, announcing any that just completed. */
	private List<GoalsPanel.Row> goalRows()
	{
		readFacts();
		Map<String, GoalProgress.Result> results = new HashMap<>();
		for (GoalProgress.Goal g : goals)
		{
			GoalProgress.Result r = GoalProgress.compute(g, facts);
			results.put(g.id, r);
			Boolean before = wasDone.put(g.id, r.done);
			if (Boolean.FALSE.equals(before) && r.done && announced.add(g.id))
			{
				finishedThisSession.add(g.id);
				completed(g);
			}
		}
		List<GoalsPanel.Row> rows = new ArrayList<>();
		for (GoalProgress.Goal g : goals)
		{
			GoalProgress.Result r = results.get(g.id);
			// A goal finished before this session is left out, unless it's a part of a main goal still open (it counts there).
			GoalProgress.Result main = g.parent.isEmpty() ? null : results.get(g.parent);
			if (r.done && !finishedThisSession.contains(g.id) && (main == null || main.done))
			{
				continue;
			}
			rows.add(row(g, r, planner));
		}
		GoalsPanel.Row task = slayerTaskRow(results);
		if (task != null)
		{
			rows.add(task);
		}
		return rows;
	}

	/**
	 * Daily tasks and farm timers for the panel, and their icons on the game screen if chosen. A task ticked off here
	 * counts as done until Nib Tracker's own copy catches up.
	 */
	private GoalsPanel.Extras extras(long now, Set<String> pins, List<GoalOverlay.Entry> onScreen)
	{
		GoalsPanel.Extras x = new GoalsPanel.Extras();
		x.dailiesOn = config.dailiesPanel();
		x.farmOn = config.farmingPanel();
		x.dailiesPinned = pins.contains(DAILIES);
		x.farmPinned = pins.contains(FARMING);

		List<Routines.Daily> sorted = new ArrayList<>(dailies);
		Map<String, Long> dueAt = new HashMap<>();
		for (Routines.Daily d : sorted)
		{
			Long mine = tickedOff.get(d.id);
			if (mine != null && d.dueAt >= mine)
			{
				tickedOff.remove(d.id); // Nib Tracker has it now
				mine = null;
			}
			dueAt.put(d.id, Math.max(d.dueAt, mine == null ? 0 : mine));
		}
		sorted.sort(Comparator.comparing((Routines.Daily d) -> dueAt.get(d.id) > now).thenComparing(d -> d.label));
		List<Routines.Daily> dueNow = new ArrayList<>();
		for (Routines.Daily d : sorted)
		{
			long at = dueAt.get(d.id);
			boolean isDue = at <= now;
			// A task seen due this session that's now done (ticked here, or in Nib Tracker) gets a pop-up.
			Boolean before = dailyWasDue.put(d.id, isDue);
			if (Boolean.TRUE.equals(before) && !isDue)
			{
				dailyDone(d);
			}
			if (isDue)
			{
				dueNow.add(d);
			}
			x.dailies.add(new GoalsPanel.DailyView(d.id, d.label, isDue ? "Due" : "Resets in " + Routines.left(at - now), d.how, isDue));
		}
		// On screen only while something is due: done for the day, it goes away by itself.
		if (x.dailiesPinned && !dueNow.isEmpty())
		{
			StringBuilder tip = new StringBuilder("Daily tasks due");
			for (Routines.Daily d : dueNow)
			{
				tip.append("</br>").append(ColorUtil.wrapWithColorTag(Text.escapeJagex(d.label), TIP_TITLE));
				if (!d.how.isEmpty())
				{
					tip.append("</br>").append(Text.escapeJagex(d.how));
				}
			}
			onScreen.add(new GoalOverlay.Entry(calendarIcon(), "Daily tasks", dueNow.size() + " due", dueNow.get(0).label + (dueNow.size() > 1 ? " +" + (dueNow.size() - 1) + " more" : ""),
				(double) (sorted.size() - dueNow.size()) / sorted.size(), false, tip.toString()));
		}

		Set<String> hidden = hiddenRegions();
		Map<String, List<Routines.Patch>> byRegion = new LinkedHashMap<>();
		for (Routines.Patch p : farm)
		{
			byRegion.computeIfAbsent(p.region, k -> new ArrayList<>()).add(p);
		}
		x.allRegions.addAll(byRegion.keySet());
		x.allRegions.sort(String::compareTo);
		x.hiddenRegions.addAll(hidden);
		List<Map.Entry<String, List<Routines.Patch>>> regions = new ArrayList<>(byRegion.entrySet());
		regions.removeIf(e -> hidden.contains(e.getKey()));
		regions.sort(Comparator.comparingLong(e -> soonest(e.getValue(), now)));
		List<Routines.Patch> readyNow = new ArrayList<>();
		Routines.Patch next = null;
		for (Map.Entry<String, List<Routines.Patch>> e : regions)
		{
			List<Routines.Patch> ps = new ArrayList<>(e.getValue());
			ps.sort(Comparator.comparingLong(p -> p.readyAt(now) ? 0 : p.readyAt > 0 ? p.readyAt : Long.MAX_VALUE));
			GoalsPanel.Region r = new GoalsPanel.Region(e.getKey(), Routines.summary(ps, now), ps.stream().anyMatch(p -> p.readyAt(now)));
			for (Routines.Patch p : ps)
			{
				boolean isReady = p.readyAt(now);
				if (isReady)
				{
					readyNow.add(p);
				}
				else if (p.readyAt > 0 && (next == null || p.readyAt < next.readyAt))
				{
					next = p;
				}
				String crop = p.crop + (p.name.isEmpty() || p.name.equals(p.region) ? "" : " · " + p.name);
				GoalsPanel.PatchView v = new GoalsPanel.PatchView(p.region, crop, p.type,
					isReady ? "Ready" : p.readyAt > 0 ? Routines.left(p.readyAt - now) : "Growing", p.travel, p.travelHow, isReady, p.itemId);
				v.kindLabel = p.kindLabel;
				v.order = p.order;
				v.travelCat = p.travelCat;
				v.travelItem = p.travelItem;
				r.patches.add(v);
				x.patches.add(v);
			}
			x.regions.add(r);
		}
		x.patches.sort(Comparator.comparing((GoalsPanel.PatchView v) -> !v.ready).thenComparing(v -> v.status.length()).thenComparing(v -> v.status));
		if (x.farmPinned && !regions.isEmpty())
		{
			StringBuilder tip = new StringBuilder(readyNow.isEmpty() ? "Farming" : "Ready to harvest");
			for (Routines.Patch p : readyNow)
			{
				tip.append("</br>").append(ColorUtil.wrapWithColorTag(Text.escapeJagex(p.region), TIP_TITLE)).append(": ").append(Text.escapeJagex(p.crop))
					.append(p.travel.isEmpty() ? "" : " (" + Text.escapeJagex(p.travel) + ")");
			}
			if (next != null)
			{
				tip.append("</br>Next: ").append(Text.escapeJagex(next.region)).append(", ").append(Text.escapeJagex(next.crop)).append(" in ").append(Routines.left(next.readyAt - now));
			}
			String detail = !readyNow.isEmpty() ? "Ready: " + readyNow.stream().map(p -> p.region).distinct().collect(Collectors.joining(", "))
				: next == null ? "" : "Next: " + next.region + ", " + next.crop;
			onScreen.add(new GoalOverlay.Entry(skillIcons.getSkillImage(Skill.FARMING, true), "Farming",
				!readyNow.isEmpty() ? readyNow.size() + " ready" : next == null ? "Growing" : Routines.left(next.readyAt - now), detail,
				-1, !readyNow.isEmpty(), tip.toString()));
		}
		// One kind of patch on screen (Herbs, say): how many are ready, and the run to do them in, with each stop's teleport.
		for (String pin : pins)
		{
			if (!pin.startsWith(FARMING + ":"))
			{
				continue;
			}
			String kind = pin.substring(FARMING.length() + 1);
			x.pinnedKinds.add(kind);
			List<Routines.Patch> ps = new ArrayList<>();
			for (Routines.Patch p : farm)
			{
				if (kind.equals(p.kindLabel) && !hidden.contains(p.region))
				{
					ps.add(p);
				}
			}
			if (!ps.isEmpty())
			{
				onScreen.add(kindEntry(kind, ps, now));
			}
		}
		return x;
	}

	/** One kind of patch on screen: ready count (or time to the next), and the run in order with each stop's teleport. */
	private GoalOverlay.Entry kindEntry(String kind, List<Routines.Patch> ps, long now)
	{
		ps.sort(Comparator.comparingInt((Routines.Patch p) -> p.order > 0 ? p.order : Integer.MAX_VALUE).thenComparing(p -> p.region));
		List<Routines.Patch> ready = new ArrayList<>();
		Routines.Patch next = null;
		for (Routines.Patch p : ps)
		{
			if (p.readyAt(now))
			{
				ready.add(p);
			}
			else if (p.readyAt > 0 && (next == null || p.readyAt < next.readyAt))
			{
				next = p;
			}
		}
		// Stops with something ready, in run order: "1 Ardougne cloak 2  2 Camelot Teleport".
		Map<String, Routines.Patch> stops = new LinkedHashMap<>();
		for (Routines.Patch p : ready)
		{
			stops.putIfAbsent(p.region, p);
		}
		StringBuilder route = new StringBuilder();
		StringBuilder tip = new StringBuilder(ColorUtil.wrapWithColorTag(Text.escapeJagex(kind), TIP_TITLE));
		int n = 0;
		for (Routines.Patch p : stops.values())
		{
			n++;
			route.append(route.length() == 0 ? "" : "  ").append(n).append(' ').append(p.travel.isEmpty() ? p.region : p.travel);
			tip.append("</br>").append(n).append(". ").append(Text.escapeJagex(p.region)).append(p.travel.isEmpty() ? "" : ": " + Text.escapeJagex(p.travel));
			for (Routines.Patch q : ready)
			{
				if (q.region.equals(p.region))
				{
					tip.append("</br>    ").append(Text.escapeJagex(q.crop)).append(" (ready)");
				}
			}
		}
		if (stops.isEmpty() && next != null)
		{
			tip.append("</br>Next: ").append(Text.escapeJagex(next.region)).append(", ").append(Text.escapeJagex(next.crop)).append(" in ").append(Routines.left(next.readyAt - now));
		}
		BufferedImage icon = ps.get(0).itemId > 0 ? itemManager.getImage(ps.get(0).itemId) : skillIcons.getSkillImage(Skill.FARMING, true);
		String value = !ready.isEmpty() ? ready.size() + " ready" : next == null ? "Growing" : Routines.left(next.readyAt - now);
		String detail = route.length() > 0 ? route.toString() : next == null ? "" : "Next: " + next.region;
		return new GoalOverlay.Entry(icon, kind, value, detail, (double) ready.size() / ps.size(), !ready.isEmpty(), tip.toString());
	}

	/** A daily task that's just been done: the same pop-up and chat line as a goal. */
	private void dailyDone(Routines.Daily d)
	{
		if (config.goalChat())
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.runeLiteFormattedMessage(new ChatMessageBuilder()
					.append(ChatColorType.HIGHLIGHT).append("Nib Tracker daily task done: ")
					.append(ChatColorType.NORMAL).append(Text.escapeJagex(d.label))
					.build())
				.build());
		}
		if (config.goalPopups())
		{
			popup.show("Daily task done!", "<col=ffffff>" + Text.escapeJagex(d.label) + "</col>");
		}
	}

	/** What hovering over a goal on the game screen shows: what it is, what's left, and how to get there. */
	private static String tooltip(GoalsPanel.Row r, String info, String travel)
	{
		StringBuilder t = new StringBuilder(ColorUtil.wrapWithColorTag(Text.escapeJagex(r.title), TIP_TITLE));
		if (!info.isEmpty())
		{
			t.append("</br>").append(Text.escapeJagex(info));
		}
		if (!r.hint.isEmpty() && !r.hint.equals(info))
		{
			t.append("</br>").append(Text.escapeJagex(r.hint));
		}
		if (!travel.isEmpty())
		{
			t.append("</br>Get there: ").append(Text.escapeJagex(travel));
		}
		return t.toString();
	}

	private static long soonest(List<Routines.Patch> ps, long now)
	{
		long best = Long.MAX_VALUE;
		for (Routines.Patch p : ps)
		{
			best = Math.min(best, p.readyAt(now) ? 0 : p.readyAt > 0 ? p.readyAt : Long.MAX_VALUE - 1);
		}
		return best;
	}

	/** Swing thread. A daily task ticked off in the panel: tell Nib Tracker, and count it done here straight away. */
	void dailyDone(String id)
	{
		clientThread.invoke(() -> {
			for (Routines.Daily d : dailies)
			{
				if (d.id.equals(id))
				{
					long now = System.currentTimeMillis();
					tickedOff.put(id, Routines.nextDue(d, now));
					JsonObject data = new JsonObject();
					data.addProperty("id", d.id);
					data.addProperty("label", d.label);
					sink.emit("chore_done", data);
					dirty = true;
					return;
				}
			}
		});
	}

	/** Swing thread. Shows a farming region in the panel, or hides it; remembered in the plugin's settings. */
	void toggleRegion(String region)
	{
		Set<String> hidden = hiddenRegions();
		if (!hidden.remove(region))
		{
			hidden.add(region);
		}
		configManager.setConfiguration(NibTrackerConfig.GROUP, NibTrackerConfig.HIDDEN_FARM_REGIONS, String.join(",", hidden));
		dirty = true;
	}

	private Set<String> hiddenRegions()
	{
		Set<String> out = new LinkedHashSet<>();
		for (String s : config.hiddenFarmRegions().split(","))
		{
			if (!s.trim().isEmpty())
			{
				out.add(s.trim());
			}
		}
		return out;
	}

	/** The icon for daily tasks (panel tab and game screen): a small calendar, drawn once. */
	static synchronized BufferedImage calendarIcon()
	{
		if (calendar == null)
		{
			BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.setColor(new Color(0xE8, 0xE2, 0xD0));
			g.fillRect(1, 3, 14, 12);
			g.setColor(new Color(0xC8, 0x4B, 0x3C));
			g.fillRect(1, 1, 14, 4);
			g.setColor(new Color(0x3A, 0x2A, 0x14));
			g.drawRect(1, 1, 13, 13);
			for (int i = 0; i < 3; i++)
			{
				for (int j = 0; j < 3; j++)
				{
					g.fillRect(3 + i * 4, 7 + j * 3, 2, 2);
				}
			}
			g.dispose();
			calendar = img;
		}
		return calendar;
	}

	/**
	 * The current Slayer task, under the first open Slayer level or XP goal: its name, kills left and a bar for kills
	 * done out of the task. Null when there's no such goal or no task.
	 */
	private GoalsPanel.Row slayerTaskRow(Map<String, GoalProgress.Result> results)
	{
		JsonObject t = slayer.current();
		if (t == null || !t.has("name") || t.get("remaining").getAsInt() <= 0)
		{
			return null;
		}
		GoalProgress.Goal goal = null;
		for (GoalProgress.Goal g : goals)
		{
			if (("skill_level".equals(g.type) || "skill_xp".equals(g.type)) && "slayer".equals(GoalProgress.skillKey(g.str("skill"))) && !results.get(g.id).done)
			{
				goal = g;
				break;
			}
		}
		if (goal == null)
		{
			return null;
		}
		int left = t.get("remaining").getAsInt(), amount = Math.max(left, t.has("amount") ? t.get("amount").getAsInt() : left);
		String where = t.has("location") ? t.get("location").getAsString() : "";
		GoalsPanel.Row row = new GoalsPanel.Row(SLAYER_TASK, "Skills", "Task: " + t.get("name").getAsString(),
			left + " left", (amount - left) + " of " + amount + " killed" + (where.isEmpty() ? "" : "  ·  " + where),
			amount > 0 ? (double) (amount - left) / amount : -1, false, 0, null, null, ItemID.SLAYER_GEM, NibTrackerApi.SITE + "/#slayer");
		row.parent = goal.id;
		row.extra = true;
		return row;
	}

	/** The icon for a goal on screen: its skill, its item, or a picture for its kind. Looked up once. */
	private BufferedImage overlayIcon(GoalsPanel.Row r)
	{
		if ("Combat achievements".equals(r.category))
		{
			return GoalsPanel.CA_BOOK; // the combat achievements book, as on the Nib Tracker site
		}
		String key = r.skill != null ? "skill:" + r.skill.name() : r.item != null ? "item:" + r.item.toLowerCase(Locale.ROOT) : "id:" + r.fallbackItem;
		BufferedImage cached = overlayIcons.get(key);
		if (cached != null)
		{
			return cached;
		}
		BufferedImage img = null;
		if (r.skill != null)
		{
			img = skillIcons.getSkillImage(r.skill, true);
		}
		else
		{
			int id = r.item == null ? r.fallbackItem : itemIds.computeIfAbsent(r.item.toLowerCase(Locale.ROOT), k -> {
				for (ItemPrice p : itemManager.search(r.item))
				{
					if (p.getName().equalsIgnoreCase(r.item))
					{
						return p.getId();
					}
				}
				return r.fallbackItem;
			});
			if (id > 0)
			{
				img = itemManager.getImage(id);
			}
		}
		if (img != null)
		{
			overlayIcons.put(key, img);
		}
		return img;
	}

	/** A goal as the panel shows it: its section and icon come from its type and parameters. */
	private static GoalsPanel.Row row(GoalProgress.Goal g, GoalProgress.Result r, String planner)
	{
		String category = GoalProgress.category(g.type);
		Skill skill = null;
		String item = null;
		switch (g.type)
		{
			case "skill_level":
			case "skill_xp":
				skill = skill(g.str("skill"));
				break;
			case "kc":
				skill = Skill.SLAYER;
				break;
			case "clog_item":
			case "item_owned":
				item = g.str("item");
				break;
			default:
				break;
		}
		GoalsPanel.Row row = new GoalsPanel.Row(g.id, category, g.title, r.detail, r.hint, r.progress, r.done, g.points, skill,
			item == null || item.isEmpty() ? null : item, GoalsPanel.fallbackItem(category), link(g, planner));
		row.parent = g.parent;
		row.serverDone = g.done;
		row.partsDone = g.partsDone;
		row.partsTotal = g.partsTotal;
		row.info = g.info;
		row.travel = g.travel;
		row.tag = "combat_achievement".equals(g.type) || g.type.startsWith("diary") ? g.str("tier") : "quest".equals(g.type) && !r.done ? r.detail : "";
		return row;
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

	static final String WIKI = "https://oldschool.runescape.wiki/w/";

	/**
	 * Where clicking a goal goes. Quests open their wiki quick guide and achievement diaries their wiki page; everything
	 * else opens the goal in the Nib Tracker web app, which picks the page (a skill's setup, a boss plan, Slayer, Farm,
	 * or the wiki for an item no boss drops). The addresses are fixed here; only names and ids come from the server, and
	 * they're encoded.
	 */
	static String link(GoalProgress.Goal g, String planner)
	{
		switch (g.type)
		{
			case "quest":
				return g.str("quest").isEmpty() ? null : wiki(g.str("quest") + "/Quick guide");
			case "diary_task":
			case "diary_tier":
				return g.str("region").isEmpty() ? null : wiki(g.str("region") + " Diary");
			default:
				return NibTrackerApi.SITE + "/#goal=" + encode(g.id) + (planner == null ? "" : "&c=" + encode(planner));
		}
	}

	static String wiki(String page)
	{
		return WIKI + encode(page.trim().replace(' ', '_'));
	}

	private static String encode(String s)
	{
		try
		{
			return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
		}
		catch (UnsupportedEncodingException e)
		{
			return "";
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
			names(containers.get(InventoryID.SEED_VAULT), facts.bank);
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
					.append(ChatColorType.NORMAL).append(Text.escapeJagex(g.title) + points)
					.build())
				.build());
		}
		if (config.goalPopups())
		{
			popup.show("Goal complete!", "<col=ffffff>" + Text.escapeJagex(g.title) + "</col>"
				+ (g.points > 0 ? "<br><br>+" + g.points + " Nib Tracker points" : ""));
		}
	}

	private void refreshPanel(List<GoalsPanel.Row> rows, GoalsPanel.Extras extras)
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
			key.append('|').append(r.title).append(r.detail).append(r.hint).append(Math.round(r.progress * 1000)).append(r.done)
				.append(r.parent).append(r.partsDone).append('/').append(r.partsTotal).append(r.pinned);
		}
		key.append('|').append(extras.dailiesOn).append(extras.farmOn).append(extras.dailiesPinned).append(extras.farmPinned)
			.append(extras.hiddenRegions).append(extras.allRegions);
		for (GoalsPanel.DailyView d : extras.dailies)
		{
			key.append('|').append(d.id).append(d.status);
		}
		for (GoalsPanel.Region r : extras.regions)
		{
			key.append('|').append(r.name).append(r.summary);
			r.patches.forEach(p -> key.append(p.crop).append(p.status).append(p.travel));
		}
		for (GoalsPanel.Row r : rows)
		{
			key.append(r.tag);
		}
		if (key.toString().equals(shown))
		{
			return;
		}
		shown = key.toString();
		SwingUtilities.invokeLater(() -> panel.show(title, message, rows, extras));
	}
}
