package com.nibtracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.StructComposition;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Quests, achievement diaries and combat achievements as full snapshots (in Character Export's file shapes), plus
 * the game values the server asks to watch (combat task bits, league tasks, relics and points).
 */
@Slf4j
@Singleton
class ProgressTracker
{
	/* region, then the easy/medium/hard/elite completion varbits. Karamja's first three count as done at 2. */
	private static final Object[][] DIARIES = {
		{"Ardougne", VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE},
		{"Desert", VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE},
		{"Falador", VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE},
		{"Fremennik", VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE},
		{"Kandarin", VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE},
		{"Karamja", VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE},
		{"Kourend & Kebos", VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE},
		{"Lumbridge & Draynor", VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE},
		{"Morytania", VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE},
		{"Varrock", VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE},
		{"Western Provinces", VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE},
		{"Wilderness", VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE},
	};
	private static final String[] DIARY_TIERS = {"easy", "medium", "hard", "elite"};

	/* Combat achievements: one enum of task structs per tier (no named constants for these in RuneLite's API); each
	   struct has the task's id and name. A task is done when bit (id % 32) of CA_TASK_COMPLETED_(id / 32) is set. */
	private static final int[] CA_TIER_ENUMS = {3981, 3982, 3983, 3984, 3985, 3986};
	private static final String[] CA_TIERS = {"Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"};
	private static final int CA_PARAM_ID = 1306;
	private static final int CA_PARAM_NAME = 1308;
	private static final int[] CA_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2, VarPlayerID.CA_TASK_COMPLETED_3,
		VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5, VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7,
		VarPlayerID.CA_TASK_COMPLETED_8, VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14, VarPlayerID.CA_TASK_COMPLETED_15,
		VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17, VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19,
		VarPlayerID.CA_TASK_COMPLETED_20,
	};
	/* Watched values are sent at most this often while they change. */
	private static final int VARS_EVERY_TICKS = 10;
	/* Watch list ids from the server above this are ignored (the game has far fewer varps and varbits). */
	private static final int MAX_VAR_ID = 65536;

	@Inject
	private Client client;

	@Inject
	private NibTrackerConfig config;

	private EventSink sink;
	private final Set<Integer> diaryVarbits = new HashSet<>();
	private final Set<Integer> caVarps = new HashSet<>();
	private final Set<Integer> watchVarps = new HashSet<>();
	private final Set<Integer> watchVarbits = new HashSet<>();
	private final Map<Integer, Integer> changedVarps = new HashMap<>();
	private final Map<Integer, Integer> changedVarbits = new HashMap<>();
	private final Map<String, JsonObject> lastSnapshot = new HashMap<>();
	private boolean questsDirty;
	private boolean diariesDirty;
	private boolean caDirty;
	private boolean varsFull;
	private int ticks;
	private int lastVarsTick;

	void start(EventSink sink)
	{
		this.sink = sink;
		for (Object[] d : DIARIES)
		{
			for (int i = 1; i < d.length; i++)
			{
				diaryVarbits.add((Integer) d[i]);
			}
		}
		for (int v : CA_VARPS)
		{
			caVarps.add(v);
		}
	}

	void login()
	{
		questsDirty = diariesDirty = caDirty = varsFull = true;
		ticks = 0;
	}

	void reset()
	{
		questsDirty = diariesDirty = caDirty = varsFull = false;
		changedVarps.clear();
		changedVarbits.clear();
	}

	/** A chat event the plugin parsed; some mean a snapshot is out of date. */
	void onParsed(String type)
	{
		switch (type)
		{
			case "quest_done":
				questsDirty = true;
				break;
			case "diary_done":
			case "diary_task":
				diariesDirty = true;
				break;
			case "ca_done":
				caDirty = true;
				break;
			default:
		}
	}

	/** The server's watch list changed (or arrived): send every watched value again. */
	void settingsChanged()
	{
		Set<Integer> varps = ids("varps");
		Set<Integer> varbits = ids("varbits");
		if (!varps.equals(watchVarps) || !varbits.equals(watchVarbits))
		{
			watchVarps.clear();
			watchVarps.addAll(varps);
			watchVarbits.clear();
			watchVarbits.addAll(varbits);
			varsFull = true;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		int varbit = e.getVarbitId(), varp = e.getVarpId();
		if (varbit != -1)
		{
			if (diaryVarbits.contains(varbit))
			{
				diariesDirty = true;
			}
			if (watchVarbits.contains(varbit))
			{
				changedVarbits.put(varbit, e.getValue());
			}
		}
		else if (varp != -1)
		{
			if (caVarps.contains(varp))
			{
				caDirty = true;
			}
			if (watchVarps.contains(varp))
			{
				changedVarps.put(varp, e.getValue());
			}
		}
	}

	/** Client thread, every game tick. At most one snapshot per tick, so login stays smooth. */
	void tick()
	{
		if (sink == null || !sink.active())
		{
			return;
		}
		ticks++;
		if (ticks < 5)
		{
			return; // let the game finish sending varps after login
		}
		boolean tasks = config.sendTasks();
		if (questsDirty)
		{
			questsDirty = false;
			if (sink.allowed("quests", tasks))
			{
				snapshotQuests();
			}
			return;
		}
		if (diariesDirty)
		{
			diariesDirty = false;
			if (sink.allowed("diaries", tasks))
			{
				snapshotDiaries();
			}
			return;
		}
		if (caDirty)
		{
			caDirty = false;
			if (sink.allowed("combatTasks", tasks))
			{
				snapshotCombatAchievements();
			}
			return;
		}
		if (ticks - lastVarsTick >= VARS_EVERY_TICKS && (varsFull || !changedVarps.isEmpty() || !changedVarbits.isEmpty()))
		{
			lastVarsTick = ticks;
			sendVars();
		}
	}

	private void snapshotQuests()
	{
		JsonArray quests = new JsonArray();
		for (Quest q : Quest.values())
		{
			QuestState s = q.getState(client);
			JsonObject o = new JsonObject();
			o.addProperty("name", q.getName());
			o.addProperty("state", s == null ? "NOT_STARTED" : s.name());
			quests.add(o);
		}
		JsonObject d = new JsonObject();
		d.add("quests", quests);
		emitIfChanged("snapshot_quests", d);
	}

	private void snapshotDiaries()
	{
		JsonObject regions = new JsonObject();
		for (Object[] d : DIARIES)
		{
			JsonObject tiers = new JsonObject();
			for (int i = 0; i < DIARY_TIERS.length; i++)
			{
				int value = client.getVarbitValue((Integer) d[i + 1]);
				boolean karamjaEarly = "Karamja".equals(d[0]) && i < 3;
				JsonObject t = new JsonObject();
				t.addProperty("complete", karamjaEarly ? value >= 2 : value >= 1);
				t.add("tasks", new JsonArray());
				tiers.add(DIARY_TIERS[i], t);
			}
			regions.add((String) d[0], tiers);
		}
		JsonObject data = new JsonObject();
		data.add("diaries", regions);
		emitIfChanged("snapshot_diaries", data);
	}

	private void snapshotCombatAchievements()
	{
		JsonObject tiers = new JsonObject();
		int named = 0;
		for (int i = 0; i < CA_TIER_ENUMS.length; i++)
		{
			EnumComposition en = client.getEnum(CA_TIER_ENUMS[i]);
			if (en == null)
			{
				continue;
			}
			JsonArray tasks = new JsonArray();
			for (int structId : en.getIntVals())
			{
				StructComposition s = client.getStructComposition(structId);
				String name = s == null ? null : s.getStringValue(CA_PARAM_NAME);
				if (name == null || name.isEmpty())
				{
					continue;
				}
				int id = s.getIntValue(CA_PARAM_ID);
				JsonObject t = new JsonObject();
				t.addProperty("id", id);
				t.addProperty("name", name);
				t.addProperty("complete", caDone(id));
				tasks.add(t);
				named++;
			}
			JsonObject tier = new JsonObject();
			tier.addProperty("name", CA_TIERS[i]);
			tier.add("tasks", tasks);
			tiers.add(CA_TIERS[i].toLowerCase(), tier);
		}
		if (named == 0)
		{
			log.debug("Combat achievement structs not readable; the raw task bits are still sent as watched values");
			return;
		}
		JsonObject d = new JsonObject();
		d.add("tiers", tiers);
		emitIfChanged("snapshot_combat_achievements", d);
	}

	private boolean caDone(int id)
	{
		int word = id / 32;
		return id >= 0 && word < CA_VARPS.length && ((client.getVarpValue(CA_VARPS[word]) >> (id % 32)) & 1) == 1;
	}

	private void sendVars()
	{
		if (!sink.allowed("vars", true))
		{
			changedVarps.clear();
			changedVarbits.clear();
			varsFull = false;
			return;
		}
		JsonObject varps = new JsonObject();
		JsonObject varbits = new JsonObject();
		if (varsFull)
		{
			int varpCount = client.getVarps().length;
			for (int id : watchVarps)
			{
				if (id < varpCount)
				{
					varps.addProperty(String.valueOf(id), client.getVarpValue(id));
				}
			}
			for (int id : watchVarbits)
			{
				try
				{
					varbits.addProperty(String.valueOf(id), client.getVarbitValue(id));
				}
				catch (RuntimeException ex)
				{
					// Not a varbit in this game version: skip it.
				}
			}
		}
		else
		{
			changedVarps.forEach((id, v) -> varps.addProperty(String.valueOf(id), v));
			changedVarbits.forEach((id, v) -> varbits.addProperty(String.valueOf(id), v));
		}
		boolean full = varsFull;
		varsFull = false;
		changedVarps.clear();
		changedVarbits.clear();
		if (varps.size() == 0 && varbits.size() == 0)
		{
			return;
		}
		JsonObject d = new JsonObject();
		d.addProperty("full", full);
		d.add("varps", varps);
		d.add("varbits", varbits);
		sink.emit("vars", d);
	}

	/** Snapshots are only sent when they differ from the last one (hopping worlds re-reads them). */
	private void emitIfChanged(String type, JsonObject d)
	{
		if (!d.equals(lastSnapshot.get(type)))
		{
			lastSnapshot.put(type, d);
			sink.emit(type, d);
		}
	}

	private Set<Integer> ids(String key)
	{
		return sink == null ? new HashSet<>() : watchIds(sink.settings(), key, MAX_VAR_ID, 500);
	}

	/** The server's watch list under key ("varps", "varbits", "containers"): up to limit plain ids from 0 to below max. */
	static Set<Integer> watchIds(JsonObject settings, String key, int max, int limit)
	{
		Set<Integer> out = new HashSet<>();
		JsonElement w = settings.get("watch");
		if (w == null || !w.isJsonObject())
		{
			return out;
		}
		JsonElement list = w.getAsJsonObject().get(key);
		if (list == null || !list.isJsonArray())
		{
			return out;
		}
		for (JsonElement e : list.getAsJsonArray())
		{
			// Only plain ids in the game's range; anything else from the server is ignored.
			if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && out.size() < limit)
			{
				int id = e.getAsInt();
				if (id >= 0 && id < max)
				{
					out.add(id);
				}
			}
		}
		return out;
	}
}
