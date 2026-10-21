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

	@Inject
	private FarmingTracker farmingTracker;

	/* The id the current Slayer task has in the panel and on screen. */
	static final String SLAYER_TASK = "slayer-task";
	/* Ids for the daily tasks and farming icons on the game screen. */
	static final String DAILIES = "dailies";
	static final String FARMING = "farming";
	/* The game-screen boxes for things pinned from the Daily tasks and Farming tabs. */
	private static final String DAILIES_BOX = "Daily tasks";
	private static final String FARMING_BOX = "Farming";
	/* Old group pins (dailies, farming, farming:<Label>) each keep a box of their own, as before boxes: "legacy:" + the pin.
	   A box with one entry has no title line, so this key is never shown. */
	private static final String LEGACY_BOX = "legacy:";

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
	/* The containers changed since item names were last counted from them (that lookup is skipped otherwise). */
	private boolean namesDirty = true;
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
		panel.onFavourite(this::toggleFavourite);
		panel.onDailyDone(this::dailyDone);
		panel.onRegion(this::toggleRegion);
		panel.onRegionsSet(this::setHiddenRegions);
		panel.onFavouritesSet(this::setFavourites);
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

	/** Swing thread. Adds a row to its tab's favourites, or takes it off; remembered in the plugin's settings. */
	void toggleFavourite(String tab, String id)
	{
		Map<String, Set<String>> favourites = Favourites.parse(config.favourites());
		Favourites.toggle(favourites, tab, id);
		configManager.setConfiguration(NibTrackerConfig.GROUP, NibTrackerConfig.FAVOURITES, Favourites.write(favourites));
		dirty = true;
	}

	/** Swing thread. Sets a tab's favourites all at once (the Farming picker's Done), in this order. */
	void setFavourites(String tab, List<String> ids)
	{
		Map<String, Set<String>> favourites = Favourites.parse(config.favourites());
		Favourites.setAll(favourites, tab, ids);
		configManager.setConfiguration(NibTrackerConfig.GROUP, NibTrackerConfig.FAVOURITES, Favourites.write(favourites));
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
		namesDirty = true;
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
		namesDirty = true;
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
				// In its home tab's box, also when pinned from Overview.
				onScreen.add(goalEntry(r, r.category));
			}
		}
		long now = System.currentTimeMillis();
		GoalsPanel.Extras extras = extras(now, pins, onScreen, rows);
		overlay.set(onScreen);
		refreshPanel(rows, extras);
	}

	/** A goal on the game screen, in the given box. */
	private GoalOverlay.Entry goalEntry(GoalsPanel.Row r, String box)
	{
		// The detail line: what to do (a combat achievement's task) or what's left ("250,964 XP to go").
		String detail = !r.info.isEmpty() ? r.info : r.hint;
		return new GoalOverlay.Entry(box, overlayIcon(r), r.title, GoalProgress.shortText(r.detail, r.progress, false), detail,
			r.progress, false, tooltip(r, r.info, r.travel));
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
			if (GoalProgress.justCompleted(wasDone, announced, g.id, r.done))
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
	private GoalsPanel.Extras extras(long now, Set<String> pins, List<GoalOverlay.Entry> onScreen, List<GoalsPanel.Row> rows)
	{
		GoalsPanel.Extras x = new GoalsPanel.Extras();
		x.dailiesOn = config.dailiesPanel();
		x.farmOn = config.farmingPanel();
		x.dailiesPinned = pins.contains(DAILIES);
		x.farmPinned = pins.contains(FARMING);
		x.pins.addAll(pins);
		x.favourites.putAll(Favourites.parse(config.favourites()));

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
			onScreen.add(new GoalOverlay.Entry(LEGACY_BOX + DAILIES, calendarIcon(), "Daily tasks", dueNow.size() + " due", dueNow.get(0).label + (dueNow.size() > 1 ? " +" + (dueNow.size() - 1) + " more" : ""),
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
		regions.sort(Comparator.comparingLong(e -> soonest(e.getValue(), now)));
		List<Routines.Patch> readyNow = new ArrayList<>();
		Routines.Patch next = null;
		// Every patch goes in x.patches (hiding is for All patches only); only shown regions go in x.regions.
		for (Map.Entry<String, List<Routines.Patch>> e : regions)
		{
			List<Routines.Patch> ps = new ArrayList<>(e.getValue());
			ps.sort(Comparator.comparingLong(p -> FarmRows.ready(p, now) ? 0 : p.readyAt > 0 ? p.readyAt : Long.MAX_VALUE));
			boolean shown = FarmGroups.shownInAll(ps.get(0), hidden);
			GoalsPanel.Region r = shown ? new GoalsPanel.Region(e.getKey(), Routines.summary(ps, now), ps.stream().anyMatch(p -> FarmRows.ready(p, now))) : null;
			for (Routines.Patch p : ps)
			{
				boolean isReady = FarmRows.ready(p, now);
				if (shown && isReady)
				{
					readyNow.add(p);
				}
				else if (shown && !p.needsAttention() && p.readyAt > now && (next == null || p.readyAt < next.readyAt))
				{
					next = p;
				}
				GoalsPanel.PatchView v = new GoalsPanel.PatchView(p.region, FarmRows.title(p), p.type,
					FarmRows.status(p, now), p.travel, p.travelHow, isReady, p.itemId);
				v.id = p.id();
				v.name = p.name;
				v.kind = p.kind;
				v.state = p.state;
				v.progress = isReady ? 1 : p.progress;
				v.attention = p.needsAttention();
				v.kindLabel = p.kindLabel;
				v.order = p.order;
				v.travelCat = p.travelCat;
				v.travelItem = p.travelItem;
				if (r != null)
				{
					r.patches.add(v);
				}
				x.patches.add(v);
			}
			if (r != null)
			{
				x.regions.add(r);
			}
		}
		// The Farming Guild contract, read from Time Tracking (held in memory, no disk read): there whenever there's farm
		// data or a contract, so a favourited or pinned contract never goes away ("No contract · talk to Jane" when none).
		int contractItem = farmingTracker.contractItemId();
		if (contractItem > 0 || !farm.isEmpty())
		{
			x.contract = FarmGroups.contractView(FarmGroups.contract(contractItem), farm, now);
		}
		x.patches.sort(Comparator.comparing((GoalsPanel.PatchView v) -> !v.ready).thenComparing(v -> v.status.length()).thenComparing(v -> v.status));
		if (x.farmPinned && !x.regions.isEmpty())
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
			onScreen.add(new GoalOverlay.Entry(LEGACY_BOX + FARMING, skillIcons.getSkillImage(Skill.FARMING, true), "Farming",
				!readyNow.isEmpty() ? readyNow.size() + " ready" : next == null ? "Growing" : Routines.left(next.readyAt - now), detail,
				-1, !readyNow.isEmpty(), tip.toString()));
		}
		// One kind of patch on screen (Herbs, say): how many are ready, and the run to do them in, with each stop's teleport.
		for (String pin : pins)
		{
			// Only old "farming:Herbs" pins; the Special, All trees, kind and contract pins aren't kind labels.
			if (pin.equals(Favourites.SPECIAL_PIN) || pin.equals(Favourites.TREES_PIN) || !Favourites.isLegacyKindPin(pin))
			{
				continue;
			}
			String kind = pin.substring(FARMING.length() + 1);
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
				onScreen.add(kindEntry(LEGACY_BOX + pin, kind, ps, now));
			}
		}
		pinnedSingles(now, pins, onScreen, x, rows, sorted, dueAt);
		return x;
	}

	/**
	 * The pins from ▣ on single rows and group headers: a daily task (while due), a patch, the contract, Special, All
	 * trees, one kind of patch, and a tab's whole favourites list. Each goes in its tab's box, or its group's.
	 */
	private void pinnedSingles(long now, Set<String> pins, List<GoalOverlay.Entry> onScreen, GoalsPanel.Extras x,
		List<GoalsPanel.Row> rows, List<Routines.Daily> dailyList, Map<String, Long> dueAt)
	{
		String dailyPrefix = Favourites.dailyPin(""), patchPrefix = Favourites.patchPin(""), kindPrefix = Favourites.kindPin("");
		String favPrefix = Favourites.groupPin("");
		Map<String, Routines.Patch> patches = new HashMap<>();
		for (Routines.Patch p : farm)
		{
			patches.putIfAbsent(p.id(), p);
		}
		Map<String, Routines.Daily> daily = new HashMap<>();
		for (Routines.Daily d : dailyList)
		{
			daily.put(d.id, d);
		}
		for (String pin : pins)
		{
			if (pin.startsWith(dailyPrefix))
			{
				// Like the whole-dailies pin: on screen only while due, so it goes away once done.
				Routines.Daily d = daily.get(pin.substring(dailyPrefix.length()));
				if (d != null && dueAt.get(d.id) <= now)
				{
					onScreen.add(dailyEntry(d, dueAt.get(d.id), now, DAILIES_BOX));
				}
			}
			else if (pin.startsWith(patchPrefix))
			{
				// Always there while pinned: a ready patch stays until it's harvested or replanted, then shows its new state.
				Routines.Patch p = patches.get(pin.substring(patchPrefix.length()));
				if (p != null)
				{
					onScreen.add(patchEntry(p, now, FARMING_BOX));
				}
			}
			else if (pin.equals(FarmGroups.CONTRACT_ID))
			{
				if (x.contract != null)
				{
					onScreen.add(contractEntry(x.contract, FARMING_BOX));
				}
			}
			else if (pin.equals(Favourites.SPECIAL_PIN) || pin.equals(Favourites.TREES_PIN) || pin.startsWith(kindPrefix))
			{
				String key = pin.startsWith(kindPrefix) ? pin.substring(kindPrefix.length()) : null;
				String label = pin.equals(Favourites.SPECIAL_PIN) ? "Special" : pin.equals(Favourites.TREES_PIN) ? "All trees" : key;
				List<Routines.Patch> ps = new ArrayList<>();
				for (Routines.Patch p : farm)
				{
					if (key != null ? key.equals(p.kind) : pin.equals(Favourites.SPECIAL_PIN) ? FarmGroups.special(p.kind) : FarmGroups.tree(p.kind))
					{
						ps.add(p);
						if (key != null && !p.kindLabel.isEmpty())
						{
							label = p.kindLabel;
						}
					}
				}
				if (!ps.isEmpty())
				{
					onScreen.add(kindEntry(label, label, ps, now));
				}
			}
			else if (pin.startsWith(favPrefix))
			{
				String tab = pin.substring(favPrefix.length());
				favouritesBox(tab, x.favourites.getOrDefault(tab, Collections.emptySet()), now, onScreen, x, rows, patches, daily, dueAt);
			}
		}
	}

	/** A tab's favourites list on the game screen, live: one line per favourite, ready (or due) ones first. */
	private void favouritesBox(String tab, Set<String> favs, long now, List<GoalOverlay.Entry> onScreen, GoalsPanel.Extras x,
		List<GoalsPanel.Row> rows, Map<String, Routines.Patch> patches, Map<String, Routines.Daily> daily, Map<String, Long> dueAt)
	{
		String box = "★ " + tab + " favourites";
		List<GoalOverlay.Entry> out = new ArrayList<>();
		if (FARMING_BOX.equals(tab))
		{
			for (String id : favs)
			{
				if (FarmGroups.CONTRACT_ID.equals(id))
				{
					if (x.contract != null)
					{
						out.add(contractEntry(x.contract, box));
					}
				}
				else if (patches.containsKey(id))
				{
					out.add(patchEntry(patches.get(id), now, box));
				}
			}
		}
		else if (DAILIES_BOX.equals(tab))
		{
			for (String id : favs)
			{
				Routines.Daily d = daily.get(id);
				if (d != null)
				{
					out.add(dailyEntry(d, dueAt.get(d.id), now, box));
				}
			}
		}
		else
		{
			Map<String, GoalsPanel.Row> byId = new HashMap<>();
			for (GoalsPanel.Row r : rows)
			{
				byId.putIfAbsent(r.id, r);
			}
			for (String id : favs)
			{
				GoalsPanel.Row r = byId.get(id);
				if (r != null)
				{
					out.add(goalEntry(r, box));
				}
			}
		}
		onScreen.addAll(FarmRows.readyFirst(out, e -> e.ready));
	}

	/** One patch on the game screen: its crop, status, growth bar and "region · name · teleport". */
	private GoalOverlay.Entry patchEntry(Routines.Patch p, long now, String box)
	{
		boolean ready = FarmRows.ready(p, now);
		BufferedImage icon = p.itemId > 0 ? itemManager.getImage(p.itemId) : skillIcons.getSkillImage(Skill.FARMING, true);
		StringBuilder tip = new StringBuilder(ColorUtil.wrapWithColorTag(Text.escapeJagex(p.region), TIP_TITLE))
			.append(": ").append(Text.escapeJagex(FarmRows.title(p))).append(" (").append(FarmRows.status(p, now)).append(')');
		if (!p.travel.isEmpty())
		{
			tip.append("</br>").append(Text.escapeJagex(p.travel));
		}
		if (!p.travelHow.isEmpty())
		{
			tip.append("</br>").append(Text.escapeJagex(p.travelHow));
		}
		return new GoalOverlay.Entry(box, icon, FarmRows.title(p), FarmRows.status(p, now), FarmRows.sub(p.region, p.name, p.travel),
			ready ? 1 : p.progress, ready, tip.toString());
	}

	/** The Farming Guild contract on the game screen, whatever crop it is now. */
	private GoalOverlay.Entry contractEntry(FarmGroups.ContractView v, String box)
	{
		BufferedImage icon = v.itemId > 0 ? itemManager.getImage(v.itemId) : skillIcons.getSkillImage(Skill.FARMING, true);
		StringBuilder tip = new StringBuilder(ColorUtil.wrapWithColorTag(Text.escapeJagex(v.title), TIP_TITLE))
			.append("</br>").append(Text.escapeJagex(v.value));
		if (!v.sub.isEmpty())
		{
			tip.append("</br>").append(Text.escapeJagex(v.sub));
		}
		return new GoalOverlay.Entry(box, icon, v.title, v.value, v.sub, v.progress, v.ready, tip.toString());
	}

	/** One daily task on the game screen: Due (in green), or when it resets. */
	private static GoalOverlay.Entry dailyEntry(Routines.Daily d, long at, long now, String box)
	{
		boolean due = at <= now;
		String tip = ColorUtil.wrapWithColorTag(Text.escapeJagex(d.label), TIP_TITLE) + (d.how.isEmpty() ? "" : "</br>" + Text.escapeJagex(d.how));
		return new GoalOverlay.Entry(box, calendarIcon(), d.label, due ? "Due" : "Resets in " + Routines.left(at - now), d.how, -1, due, tip);
	}

	/** One kind of patch on screen: ready count (or time to the next), and the run in order with each stop's teleport. */
	private GoalOverlay.Entry kindEntry(String box, String kind, List<Routines.Patch> ps, long now)
	{
		ps.sort(Comparator.comparingInt((Routines.Patch p) -> p.order > 0 ? p.order : Integer.MAX_VALUE).thenComparing(p -> p.region));
		List<Routines.Patch> ready = new ArrayList<>();
		Routines.Patch next = null;
		for (Routines.Patch p : ps)
		{
			if (FarmRows.ready(p, now))
			{
				ready.add(p);
			}
			else if (p.readyAt > now && (next == null || p.readyAt < next.readyAt))
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
		return new GoalOverlay.Entry(box, icon, kind, value, detail, (double) ready.size() / ps.size(), !ready.isEmpty(), tip.toString());
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

	/** Swing thread. Sets every hidden farming region at once (the All patches picker's Done). */
	void setHiddenRegions(Set<String> hidden)
	{
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
		if (items && namesDirty)
		{
			facts.bankSeen = containers.containsKey(InventoryID.BANK);
			facts.bank.clear();
			facts.carried.clear();
			names(containers.get(InventoryID.BANK), facts.bank);
			names(containers.get(InventoryID.SEED_VAULT), facts.bank);
			names(containers.get(InventoryID.INV), facts.carried);
			names(containers.get(InventoryID.WORN), facts.carried);
			namesDirty = false;
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
			.append(extras.hiddenRegions).append(extras.allRegions).append(extras.pins).append(extras.favourites);
		for (GoalsPanel.DailyView d : extras.dailies)
		{
			key.append('|').append(d.id).append(d.status);
		}
		for (GoalsPanel.Region r : extras.regions)
		{
			key.append('|').append(r.name).append(r.summary);
			r.patches.forEach(p -> key.append(p.crop).append(p.status).append(p.travel));
		}
		for (GoalsPanel.PatchView p : extras.patches)
		{
			key.append('|').append(p.id).append(p.status).append(p.state).append(Math.round(p.progress * 100));
		}
		if (extras.contract != null)
		{
			FarmGroups.ContractView c = extras.contract;
			key.append('|').append(c.title).append(c.value).append(c.sub).append(c.itemId).append(Math.round(c.progress * 100));
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
