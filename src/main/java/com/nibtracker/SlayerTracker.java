package com.nibtracker;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;

/** The current slayer task, points and streaks, read from the same game values RuneLite's Slayer plugin reads. */
@Singleton
class SlayerTracker
{
	/* Game database tables and columns for task names and locations (as RuneLite's Slayer plugin uses them). The boss task
	   id has no named constant in RuneLite's API. */
	private static final int TASK_BOSS_ID = 98;
	/* While a task counts down, progress is sent at most this often. */
	private static final int PROGRESS_EVERY_TICKS = 50;

	@Inject
	private Client client;

	@Inject
	private NibTrackerConfig config;

	private EventSink sink;
	private boolean dirty;
	private JsonObject last;
	private int lastStreak = -1;
	private int ticks;
	private int lastSentTick = -PROGRESS_EVERY_TICKS;
	/* The task as last read, for the Goals panel (whether or not Slayer is being sent), and a count of changes to it. */
	private JsonObject current;
	private int version;

	void start(EventSink sink)
	{
		this.sink = sink;
	}

	void login()
	{
		dirty = true;
		last = null;
		lastStreak = -1;
	}

	void reset()
	{
		dirty = false;
		current = null;
		version++;
	}

	/** Client thread. The current task ({ name, remaining, amount, location }), or null before it's been read. */
	JsonObject current()
	{
		return current;
	}

	/** Goes up whenever the current task changes, so the Goals panel knows to update. */
	int version()
	{
		return version;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		int varp = e.getVarpId(), varbit = e.getVarbitId();
		if (varbit == -1 && (varp == VarPlayerID.SLAYER_COUNT || varp == VarPlayerID.SLAYER_TARGET || varp == VarPlayerID.SLAYER_AREA
			|| varp == VarPlayerID.SLAYER_COUNT_ORIGINAL))
		{
			dirty = true;
		}
		else if (varbit == VarbitID.SLAYER_POINTS || varbit == VarbitID.SLAYER_TASKS_COMPLETED || varbit == VarbitID.SLAYER_MASTER
			|| varbit == VarbitID.SLAYER_TARGET_BOSSID || varbit == VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED)
		{
			dirty = true;
		}
	}

	void tick()
	{
		ticks++;
		if (!dirty || sink == null || !sink.active())
		{
			return;
		}
		JsonObject now = read();
		if (!now.equals(current))
		{
			current = now;
			version++;
		}
		if (!sink.allowed("slayer", config.sendSlayer()))
		{
			dirty = false;
			return;
		}
		int streak = client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
		if (lastStreak >= 0 && streak > lastStreak)
		{
			JsonObject d = new JsonObject();
			d.addProperty("streak", streak);
			d.addProperty("points", client.getVarbitValue(VarbitID.SLAYER_POINTS));
			d.addProperty("wildStreak", client.getVarbitValue(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED));
			sink.emit("slayer_done", d);
		}
		lastStreak = streak;

		if (now.equals(last))
		{
			dirty = false;
			return;
		}
		boolean newTask = last == null || !Objects.equals(str(last, "name"), str(now, "name"));
		boolean finished = now.get("remaining").getAsInt() == 0;
		if (!newTask && !finished && ticks - lastSentTick < PROGRESS_EVERY_TICKS)
		{
			return; // stays dirty: sent once the wait is up
		}
		dirty = false;
		last = now;
		lastSentTick = ticks;
		sink.emit("slayer_state", now);
	}

	private JsonObject read()
	{
		int remaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		JsonObject d = new JsonObject();
		d.addProperty("remaining", Math.max(0, remaining));
		d.addProperty("amount", client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL));
		d.addProperty("taskId", taskId);
		d.addProperty("master", client.getVarbitValue(VarbitID.SLAYER_MASTER));
		d.addProperty("points", client.getVarbitValue(VarbitID.SLAYER_POINTS));
		d.addProperty("streak", client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED));
		d.addProperty("wildStreak", client.getVarbitValue(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED));
		if (remaining > 0)
		{
			String name = taskId == TASK_BOSS_ID
				? field(DBTableID.SlayerTaskSublist.ID, DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID), DBTableID.SlayerTaskSublist.COL_TASK)
				: field(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, taskId, DBTableID.SlayerTask.COL_NAME_UPPERCASE);
			if (name != null)
			{
				d.addProperty("name", name);
			}
			int area = client.getVarpValue(VarPlayerID.SLAYER_AREA);
			String location = area > 0 ? field(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, area, DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER) : null;
			if (location != null)
			{
				d.addProperty("location", location);
			}
		}
		return d;
	}

	private String field(int table, int column, int value, int nameColumn)
	{
		try
		{
			List<Integer> rows = client.getDBRowsByValue(table, column, 0, value);
			if (rows == null || rows.isEmpty())
			{
				return null;
			}
			Object[] f = client.getDBTableField(rows.get(0), nameColumn, 0);
			return f != null && f.length > 0 && f[0] instanceof String ? (String) f[0] : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static String str(JsonObject o, String key)
	{
		return o.has(key) ? o.get(key).getAsString() : null;
	}
}
