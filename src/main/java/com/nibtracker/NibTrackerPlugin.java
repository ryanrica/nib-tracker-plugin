package com.nibtracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

/**
 * Reports the player's own game events to Nib Tracker. The plugin only reports facts (a drop, a kill count, XP gained);
 * the web app decides what they mean. Contract: docs/plugin-spec.md in the Nib Tracker repo.
 */
@Slf4j
@PluginDescriptor(
	name = "Nib Tracker",
	description = "Syncs your loot, kill counts, collection log, XP and levels to your Nib Tracker planner",
	tags = {"loot", "collection", "log", "xp", "goals", "planner", "sync", "leagues"}
)
public class NibTrackerPlugin extends Plugin implements EventSink
{
	private static final int MAX_QUEUE = 2000;
	private static final int BATCH = 50;
	private static final long MAX_BACKOFF_MS = 5 * 60_000L;
	/* IRONMAN varbit value -> the account modes the web app uses. */
	private static final String[] ACCOUNT_TYPES = {"main", "ironman", "ultimate", "hardcore", "group", "group", "group"};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private NibTrackerConfig config;

	@Inject
	private NibTrackerApi api;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private EventBus eventBus;

	@Inject
	private ProgressTracker progress;

	@Inject
	private SlayerTracker slayer;

	@Inject
	private ContainerTracker containers;

	@Inject
	private GrandExchangeTracker grandExchange;

	@Inject
	private FarmingTracker farming;

	@Inject
	private CollectionLogTracker collectionLog;

	@Inject
	private ScreenshotService screenshots;

	@Inject
	private GoalTracker goals;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private GoalOverlay goalOverlay;

	private NavigationButton goalsButton;

	/** An event waiting to be sent, with the character and profile it belongs to. */
	private static final class Pending
	{
		final JsonObject who;
		final JsonObject ev;

		Pending(JsonObject who, JsonObject ev)
		{
			this.who = who;
			this.ev = ev;
		}
	}

	private final ConcurrentLinkedDeque<Pending> queue = new ConcurrentLinkedDeque<>();
	private ScheduledFuture<?> ticker;

	/* Client thread only. */
	private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
	private final Map<String, Integer> xpGains = new LinkedHashMap<>();
	private long xpBucketStart;
	private long sessionStart;
	private int primeIn = -1;
	private boolean xpChanged;
	private long nextSnapshotAt;

	/* Who is playing: RSN, hashed account id and game profile (main vs league etc). Set at login, null when logged out. */
	private volatile JsonObject who;
	private volatile boolean linked;
	private volatile boolean helloSent;
	private volatile long nextHelloAt;
	private volatile JsonObject settings = new JsonObject();

	private volatile boolean inFlight;
	private volatile long nextSendAt;
	private volatile long backoffMs;
	private volatile long lastSentAt;
	/* When settings last came from the server; if nothing has been sent for a while they're asked for again. */
	private volatile long settingsAt;
	private volatile boolean refreshing;

	@Override
	protected void startUp()
	{
		for (Object t : trackers())
		{
			eventBus.register(t);
		}
		progress.start(this);
		slayer.start(this);
		containers.start(this);
		grandExchange.start(this);
		farming.start(this);
		collectionLog.start(this);
		GoalsPanel panel = new GoalsPanel(itemManager, skillIconManager);
		goals.start(this, panel);
		goalsButton = NavigationButton.builder()
			.tooltip("Nib Tracker goals")
			.icon(goalsIcon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(goalsButton);
		overlayManager.add(goalOverlay);
		ticker = executor.scheduleWithFixedDelay(this::tick, 2, 1, TimeUnit.SECONDS);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			primeIn = 2;
		}
	}

	@Override
	protected void shutDown()
	{
		if (ticker != null)
		{
			ticker.cancel(false);
			ticker = null;
		}
		clientToolbar.removeNavigation(goalsButton);
		overlayManager.remove(goalOverlay);
		goalsButton = null;
		for (Object t : trackers())
		{
			eventBus.unregister(t);
		}
		clientThread.invoke(() -> {
			endSession();
			send(true);
			resetLink();
			screenshots.reset();
			goals.reset();
		});
	}

	private Object[] trackers()
	{
		return new Object[]{progress, slayer, containers, grandExchange, collectionLog, goals};
	}

	/** The Goals panel's toolbar icon: the Nib Tracker logo (64 px in the jar, shown at 18 px). */
	private static BufferedImage goalsIcon()
	{
		return ImageUtil.resizeImage(ImageUtil.loadImageResource(NibTrackerPlugin.class, "goals_icon.png"), 18, 18, true);
	}

	@Provides
	NibTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(NibTrackerConfig.class);
	}

	// ------------------------------------------------------------ game events

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState s = e.getGameState();
		if (s == GameState.LOGGED_IN && who == null && primeIn < 0)
		{
			primeIn = 2;
		}
		else if (s == GameState.LOGIN_SCREEN || s == GameState.HOPPING)
		{
			// Hopping can move between a normal and a league or Deadman world, so the profile is worked out again after.
			endSession();
			send(true);
			resetLink();
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (primeIn > 0 && --primeIn == 0)
		{
			prime();
		}
		goals.tick();
		if (who == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (!xpGains.isEmpty() && now - xpBucketStart >= setting("xpBucketSeconds", 60) * 1000L)
		{
			flushXp();
		}
		if (xpChanged && linked && now >= nextSnapshotAt)
		{
			snapshotCharacter();
		}
		progress.tick();
		slayer.tick();
		containers.tick();
		farming.tick();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (NibTrackerConfig.GROUP.equals(e.getGroup()) && ("syncEnabled".equals(e.getKey()) || "pluginKey".equals(e.getKey())))
		{
			linked = false;
			helloSent = false;
			nextHelloAt = 0;
			goals.refresh();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		ChatMessageType t = e.getType();
		if (t != ChatMessageType.GAMEMESSAGE && t != ChatMessageType.SPAM)
		{
			return;
		}
		String msg = Text.removeTags(e.getMessage());
		ChatParser.Parsed p = ChatParser.parse(msg);
		if (p == null)
		{
			return;
		}
		goals.onParsed(p.type, p.data);
		if (!allowed(sourceOf(p.type), localToggle(p.type)))
		{
			return;
		}
		progress.onParsed(p.type);
		// The original line goes along too, so the server can re-read it if a pattern here turns out to be wrong.
		p.data.addProperty("msg", msg);
		String id = add(p.type, p.data);
		if ("clog_new".equals(p.type))
		{
			screenshotIfWanted(id, p.data.get("item").getAsString());
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived e)
	{
		if (!allowed("loot", config.sendLoot()))
		{
			return;
		}
		JsonArray items = new JsonArray();
		long value = 0;
		for (ItemStack s : e.getItems())
		{
			long price = itemManager.getItemPrice(s.getId());
			value += price * s.getQuantity();
			JsonObject it = new JsonObject();
			it.addProperty("id", s.getId());
			it.addProperty("name", itemManager.getItemComposition(s.getId()).getName());
			it.addProperty("qty", s.getQuantity());
			it.addProperty("price", price);
			items.add(it);
		}
		if (items.size() == 0 || value < setting("minLootValue", 0))
		{
			return;
		}
		JsonObject d = new JsonObject();
		d.addProperty("source", e.getName());
		d.addProperty("sourceType", e.getType().name());
		if (e.getCombatLevel() > 0)
		{
			d.addProperty("combatLevel", e.getCombatLevel());
		}
		if (e.getAmount() > 1)
		{
			d.addProperty("amount", e.getAmount());
		}
		d.add("items", items);
		String id = add("loot", d);
		for (JsonElement it : items)
		{
			if (screenshotIfWanted(id, it.getAsJsonObject().get("name").getAsString()))
			{
				break;
			}
		}
	}

	/** Captures the screen for this event if the item is on one of the player's live bingo drop tiles. */
	private boolean screenshotIfWanted(String eventId, String item)
	{
		JsonElement wanted = settings.get("screenshotItems");
		if (eventId == null || item == null || wanted == null || !wanted.isJsonArray() || !allowed("screenshots", config.sendScreenshots()))
		{
			return false;
		}
		for (JsonElement w : wanted.getAsJsonArray())
		{
			if (w.isJsonPrimitive() && w.getAsString().equalsIgnoreCase(item))
			{
				screenshots.capture(eventId, config.pluginKey().trim());
				return true;
			}
		}
		return false;
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		if (who == null)
		{
			return;
		}
		Skill skill = e.getSkill();
		Integer xpBefore = xp.put(skill, e.getXp());
		if (xpBefore != null && e.getXp() > xpBefore)
		{
			xpChanged = true;
			if (xpGains.isEmpty())
			{
				xpBucketStart = System.currentTimeMillis();
			}
			xpGains.merge(skill.getName(), e.getXp() - xpBefore, Integer::sum);
		}
		int real = client.getRealSkillLevel(skill);
		Integer before = levels.put(skill, real);
		if (before != null && real > before && allowed("levels", config.sendLevels()))
		{
			JsonObject d = new JsonObject();
			d.addProperty("skill", skill.getName());
			d.addProperty("level", real);
			d.addProperty("xp", e.getXp());
			add("level", d);
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		Player me = client.getLocalPlayer();
		if (me == null || e.getActor() != me || !allowed("deaths", config.sendSessions()))
		{
			return;
		}
		JsonObject d = new JsonObject();
		d.addProperty("regionId", me.getWorldLocation().getRegionID());
		add("death", d);
	}

	// ------------------------------------------------------------ login, sessions, linking

	/** Client thread, a couple of ticks after login once stats have loaded. */
	private void prime()
	{
		Player me = client.getLocalPlayer();
		long hash = client.getAccountHash();
		if (me == null || me.getName() == null || hash == -1)
		{
			primeIn = 2;
			return;
		}
		for (Skill s : Skill.values())
		{
			if (s != Skill.OVERALL)
			{
				levels.put(s, client.getRealSkillLevel(s));
				xp.put(s, client.getSkillExperience(s));
			}
		}
		JsonArray worldTypes = new JsonArray();
		for (WorldType t : client.getWorldType())
		{
			worldTypes.add(t.name());
		}
		JsonObject w = new JsonObject();
		w.addProperty("rsn", me.getName().replace((char) 160, ' '));
		w.addProperty("acct", accountId(hash));
		w.addProperty("profile", RuneScapeProfileType.getCurrent(client).name());
		w.add("worldTypes", worldTypes);
		who = w;
		sessionStart = System.currentTimeMillis();
		nextSnapshotAt = 0;
		xpChanged = true;

		if (allowed("sessions", config.sendSessions()))
		{
			JsonObject d = new JsonObject();
			d.addProperty("world", client.getWorld());
			d.add("worldTypes", worldTypes);
			add("session_start", d);
		}
		progress.login();
		slayer.login();
		grandExchange.login();
		farming.login();
		goals.login();
	}

	/** Client thread. Sends what's left of the XP bucket and the session length, then forgets who was playing. */
	private void endSession()
	{
		if (who == null)
		{
			return;
		}
		flushXp();
		farming.send();
		if (xpChanged)
		{
			snapshotCharacter();
		}
		if (allowed("sessions", config.sendSessions()))
		{
			JsonObject d = new JsonObject();
			d.addProperty("seconds", (System.currentTimeMillis() - sessionStart) / 1000);
			add("session_end", d);
		}
		who = null;
		progress.reset();
		slayer.reset();
		containers.reset();
		grandExchange.reset();
		farming.reset();
		collectionLog.reset();
		goals.reset();
		levels.clear();
		xp.clear();
		primeIn = -1;
	}

	private void flushXp()
	{
		if (xpGains.isEmpty())
		{
			return;
		}
		if (allowed("xp", config.sendXp()))
		{
			JsonObject gains = new JsonObject();
			xpGains.forEach(gains::addProperty);
			JsonObject d = new JsonObject();
			d.add("gains", gains);
			d.addProperty("seconds", Math.max(1, (System.currentTimeMillis() - xpBucketStart) / 1000));
			add("xp", d);
		}
		xpGains.clear();
	}

	/** Scheduler thread. */
	private void hello()
	{
		JsonObject w = who;
		String key = config.pluginKey().trim();
		if (w == null || !config.syncEnabled() || key.isEmpty() || helloSent)
		{
			return;
		}
		helloSent = true;
		api.hello(key, w, r -> {
			if (r == null)
			{
				helloSent = false;
				nextHelloAt = System.currentTimeMillis() + 30_000;
				return;
			}
			if (!r.has("ok") || !r.get("ok").getAsBoolean())
			{
				chat(problem(r));
				return;
			}
			if (r.has("settings") && r.get("settings").isJsonObject())
			{
				updateSettings(r.getAsJsonObject("settings"), r.get("plannerAccount"));
			}
			linked = true;
			JsonElement msg = settings.get("message");
			if (msg != null && msg.isJsonPrimitive())
			{
				chat(msg.getAsString());
			}
			JsonElement planner = r.get("plannerAccount");
			String name = w.get("rsn").getAsString() + profileLabel(w.get("profile").getAsString());
			chat(planner != null && planner.isJsonPrimitive()
				? "Nib Tracker is syncing " + name + " to " + planner.getAsString() + "."
				: "Nib Tracker: choose which planner character " + name + " syncs to on the Account page. Nothing is lost meanwhile.");
		});
	}

	private void resetLink()
	{
		linked = false;
		helloSent = false;
		inFlight = false;
		backoffMs = 0;
		nextSendAt = 0;
		nextHelloAt = 0;
	}

	private static String problem(JsonObject r)
	{
		String err = r.has("error") ? r.get("error").getAsString() : "";
		if ("bad_key".equals(err))
		{
			return "Nib Tracker doesn't recognise your plugin key. Make a new one on the Account page.";
		}
		return "Nib Tracker couldn't link this character (" + err + ").";
	}

	private static String profileLabel(String profile)
	{
		if ("STANDARD".equals(profile))
		{
			return "";
		}
		String p = profile.toLowerCase().replace('_', ' ');
		return " (" + Character.toUpperCase(p.charAt(0)) + p.substring(1) + ")";
	}

	/**
	 * A one-way hash of RuneLite's account hash: stable for the account across name changes, but not the raw id.
	 * The "bun-tracker:" salt is from before the rename and must never change, or every character gets a new id.
	 */
	static String accountId(long accountHash)
	{
		try
		{
			byte[] d = MessageDigest.getInstance("SHA-256").digest(("bun-tracker:" + accountHash).getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 16; i++)
			{
				sb.append(String.format("%02x", d[i]));
			}
			return sb.toString();
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	// ------------------------------------------------------------ snapshots, queue and sending

	private void snapshotCharacter()
	{
		xpChanged = false;
		nextSnapshotAt = System.currentTimeMillis() + setting("snapshotMinutes", 30) * 60_000L;
		Player me = client.getLocalPlayer();
		if (!allowed("levels", config.sendLevels()) || me == null || who == null)
		{
			return;
		}
		JsonObject stats = new JsonObject();
		for (Skill s : Skill.values())
		{
			if (s == Skill.OVERALL)
			{
				continue;
			}
			JsonObject v = new JsonObject();
			v.addProperty("real_level", client.getRealSkillLevel(s));
			v.addProperty("experience", client.getSkillExperience(s));
			stats.add(s.getName().toLowerCase(), v);
		}
		int iron = client.getVarbitValue(VarbitID.IRONMAN);
		JsonObject d = new JsonObject();
		d.addProperty("account_name", who.get("rsn").getAsString());
		d.addProperty("account_type", iron >= 0 && iron < ACCOUNT_TYPES.length ? ACCOUNT_TYPES[iron] : "main");
		d.addProperty("combat_level", me.getCombatLevel());
		d.addProperty("total_level", client.getTotalLevel());
		d.add("stats", stats);
		add("snapshot_character", d);
	}

	/** Client thread. */
	private String add(String type, JsonObject data)
	{
		JsonObject w = who;
		if (w == null || !config.syncEnabled() || config.pluginKey().trim().isEmpty())
		{
			return null;
		}
		String id = UUID.randomUUID().toString();
		JsonObject ev = new JsonObject();
		ev.addProperty("v", 1);
		ev.addProperty("id", id);
		ev.addProperty("type", type);
		ev.addProperty("ts", Instant.now().toString());
		ev.addProperty("world", client.getWorld());
		ev.add("data", data);
		queue.addLast(new Pending(w, ev));
		while (queue.size() > MAX_QUEUE)
		{
			queue.pollFirst();
		}
		return id;
	}

	// EventSink, for the trackers (client thread).

	@Override
	public String emit(String type, JsonObject data)
	{
		return add(type, data);
	}

	@Override
	public JsonObject settings()
	{
		return settings;
	}

	@Override
	public boolean active()
	{
		return who != null;
	}

	/** Any thread. New settings from the server; the watch list is re-read on the client thread if it changed. */
	private void updateSettings(JsonObject s, JsonElement plannerAccount)
	{
		JsonElement oldWatch = settings.get("watch");
		settings = s;
		settingsAt = System.currentTimeMillis();
		if (oldWatch == null || !oldWatch.equals(s.get("watch")))
		{
			clientThread.invoke(progress::settingsChanged);
		}
		goals.settingsChanged(s, plannerAccount != null && plannerAccount.isJsonPrimitive() ? plannerAccount.getAsString() : null);
	}

	/** Scheduler thread. Asks for settings again (goals included) when nothing has been sent for a couple of minutes. */
	private void refreshSettings()
	{
		JsonObject w = who;
		if (w == null || refreshing)
		{
			return;
		}
		refreshing = true;
		settingsAt = System.currentTimeMillis();
		api.hello(config.pluginKey().trim(), w, r -> {
			refreshing = false;
			if (r != null && r.has("ok") && r.get("ok").getAsBoolean() && r.has("settings") && r.get("settings").isJsonObject() && w == who)
			{
				updateSettings(r.getAsJsonObject("settings"), r.get("plannerAccount"));
			}
		});
	}

	/** Scheduler thread, once a second. */
	private void tick()
	{
		if (!helloSent && who != null && config.syncEnabled() && System.currentTimeMillis() >= nextHelloAt)
		{
			hello();
		}
		if (linked && System.currentTimeMillis() - settingsAt >= 120_000L)
		{
			refreshSettings();
		}
		long gap = setting("flushSeconds", 10) * 1000L;
		if (queue.size() >= BATCH || System.currentTimeMillis() - lastSentAt >= gap)
		{
			send(false);
		}
	}

	/** Sends one batch. A batch only holds events from one character and profile. */
	private void send(boolean now)
	{
		long t = System.currentTimeMillis();
		Pending first = queue.peekFirst();
		if (!linked || inFlight || first == null || (!now && t < nextSendAt))
		{
			return;
		}
		List<Pending> batch = new ArrayList<>();
		Pending p;
		while (batch.size() < BATCH && (p = queue.peekFirst()) != null && p.who == first.who)
		{
			batch.add(queue.pollFirst());
		}
		JsonArray arr = new JsonArray();
		batch.forEach(x -> arr.add(x.ev));
		inFlight = true;
		lastSentAt = t;
		api.ingest(config.pluginKey().trim(), first.who, arr, r -> {
			inFlight = false;
			if (r != null && r.has("ok") && r.get("ok").getAsBoolean())
			{
				backoffMs = 0;
				nextSendAt = 0;
				List<String> ids = new ArrayList<>();
				batch.forEach(x -> ids.add(x.ev.get("id").getAsString()));
				screenshots.eventsDelivered(ids);
				if (r.has("settings") && r.get("settings").isJsonObject())
				{
					updateSettings(r.getAsJsonObject("settings"), r.get("plannerAccount"));
				}
				return;
			}
			String err = r != null && r.has("error") ? r.get("error").getAsString() : null;
			if ("bad_key".equals(err))
			{
				linked = false;
				queue.clear();
				chat(problem(r));
				return;
			}
			// Network trouble, rate limit or a server error: put the batch back and wait longer each time.
			for (int i = batch.size() - 1; i >= 0; i--)
			{
				queue.addFirst(batch.get(i));
			}
			backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs == 0 ? 5_000 : backoffMs * 2);
			nextSendAt = System.currentTimeMillis() + backoffMs;
		});
	}

	// ------------------------------------------------------------ helpers

	/** Which server "send" switch covers a chat event type. */
	private static String sourceOf(String type)
	{
		switch (type)
		{
			case "clog_new":
				return "clog";
			case "pet":
				return "pets";
			case "quest_done":
				return "quests";
			case "ca_done":
				return "combatTasks";
			case "clue_done":
				return "clues";
			case "boss_time":
				return "times";
			case "league_task":
				return "league";
			case "diary_task":
			case "diary_done":
				return "diaries";
			case "slayer_task_done":
				return "slayer";
			default:
				return "kc";
		}
	}

	private boolean localToggle(String type)
	{
		switch (type)
		{
			case "clog_new":
			case "pet":
				return config.sendClog();
			case "quest_done":
			case "ca_done":
			case "league_task":
			case "diary_task":
			case "diary_done":
				return config.sendTasks();
			case "slayer_task_done":
				return config.sendSlayer();
			default:
				return config.sendKc();
		}
	}

	/** A source is sent only if both the player and the server allow it. */
	@Override
	public boolean allowed(String serverKey, boolean local)
	{
		if (!local)
		{
			return false;
		}
		JsonElement send = settings.get("send");
		if (send == null || !send.isJsonObject())
		{
			return true;
		}
		JsonElement v = send.getAsJsonObject().get(serverKey);
		return v == null || v.getAsBoolean();
	}

	private int setting(String key, int fallback)
	{
		JsonElement v = settings.get(key);
		return v != null && v.isJsonPrimitive() ? v.getAsInt() : fallback;
	}

	private void chat(String msg)
	{
		// Escaped, so names and messages from the server show as plain text and can't add colours or icons.
		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", Text.escapeJagex(msg), null));
	}
}
