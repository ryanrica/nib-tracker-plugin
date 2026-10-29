package com.nibtracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Daily tasks and farm timers from the server's settings (dailies, farm), and the time maths for showing them. The
 * server sends plain data; the plugin only counts down. Kept free of client calls so it can be unit tested.
 */
final class Routines
{
	private Routines()
	{
	}

	static final long MINUTE = 60_000L;
	static final long DAY = 24 * 60 * MINUTE;
	/* The patch states Nib Tracker sends. Anything else falls back to ready or growing. */
	private static final List<String> STATES = Arrays.asList("empty", "growing", "ready", "diseased", "dead", "unknown");

	/** A daily task switched on in Nib Tracker. dueAt is when it's next due (epoch ms), or 0 when it's due now. */
	static final class Daily
	{
		final String id;
		final String label;
		final String cadence;
		final int every;
		final long dueAt;
		/* What to do or collect, from Nib Tracker, or empty. */
		String how = "";
		/* The built-in task it is (zaff_staves...), so the game can tick it off; empty for a task the player named. */
		String key = "";
		/* Its icon item, from Nib Tracker; 0 when the feed has none (see DailySignals.icon). */
		int itemId;
		/* For a "condition" task (Miscellania): whether it's due now. Null for every other cadence. */
		Boolean due;

		Daily(String id, String label, String cadence, int every, long dueAt)
		{
			this.id = id;
			this.label = label;
			this.cadence = cadence;
			this.every = every;
			this.dueAt = dueAt;
		}

		boolean condition()
		{
			return "condition".equals(cadence);
		}
	}

	/** A farm patch or bird house with a timer. readyAt is epoch ms, or 0 when not known. */
	static final class Patch
	{
		final String region;
		final String name;
		final String type;
		final String crop;
		final int itemId;
		final long readyAt;
		final boolean ready;
		/* The best way there with what's in your bank, and how to use it, from Nib Tracker; empty when unknown. */
		String travel = "";
		String travelHow = "";
		/* Its kind (herb, fruit_tree...) and label (Herbs), its run (patches done together), its stop in that run's order
		   (1 = first, 0 = not planned), and the teleport's kind (ITEM, SPELLBOOK, FAIRY_RING...) and item, from Nib Tracker. */
		String kind = "";
		String kindLabel = "";
		String run = "";
		int order;
		/* Its stop in the one run over every kind of patch (see Stop), 0 when it has none. */
		int stop;
		String travelCat = "";
		String travelItem = "";
		/* empty, growing, ready, diseased, dead or unknown, from Nib Tracker; "growing" or "ready" when the feed has none. */
		String state;
		/* How far it has grown, 0 to 1; -1 when unknown. */
		double progress = -1;

		Patch(String region, String name, String type, String crop, int itemId, long readyAt, boolean ready)
		{
			this.region = region;
			this.name = name;
			this.type = type;
			this.crop = crop;
			this.itemId = itemId;
			this.readyAt = readyAt;
			this.ready = ready;
			this.state = ready ? "ready" : "growing";
		}

		boolean readyAt(long now)
		{
			return ready || (readyAt > 0 && readyAt <= now);
		}

		/** True when it's diseased or dead and needs a visit. */
		boolean needsAttention()
		{
			return "diseased".equals(state) || "dead".equals(state);
		}

		/** Which patch this is: region, name and kind. */
		String id()
		{
			return region + "|" + name + "|" + kind;
		}
	}

	/**
	 * A stop in the one farm run over every kind of patch, as Nib Tracker's planner works it out with your bank: the
	 * place (the Farming Guild's herb and trees are one stop) and the teleports you can use to get there, best first.
	 */
	static final class Stop
	{
		final int stop;
		final String place;
		final List<Tele> teles = new ArrayList<>();

		Stop(int stop, String place)
		{
			this.stop = stop;
			this.place = place;
		}
	}

	/** A teleport: its name ("Ardougne cloak 2"), how to use it, its kind (ITEM, SPELLBOOK, FAIRY_RING...) and item. */
	static final class Tele
	{
		final String travel;
		final String how;
		final String cat;
		final String item;

		Tele(String travel, String how, String cat, String item)
		{
			this.travel = travel;
			this.how = how;
			this.cat = cat;
			this.item = item;
		}
	}

	/** A live bingo board of the player's group: its name, size (3 to 7 a side) and tiles, as Nib Tracker sends them. */
	static final class Board
	{
		final String id;
		final String name;
		final int size;
		/* size * size slots, row by row; a slot the feed didn't fill is null. */
		final Tile[] tiles;

		Board(String id, String name, int size)
		{
			this.id = id;
			this.name = name;
			this.size = size;
			this.tiles = new Tile[size * size];
		}
	}

	/** A tile: what it asks for, its picture (an item, or a skill for an XP tile), and whether the team has it. */
	static final class Tile
	{
		final String label;
		final int itemId;
		final String skill;
		final boolean done;

		Tile(String label, int itemId, String skill, boolean done)
		{
			this.label = label;
			this.itemId = itemId;
			this.skill = skill;
			this.done = done;
		}
	}

	/** Up to 3 boards: each 3 to 7 a side, its tiles placed by idx; anything out of range is left out. */
	static List<Board> bingo(JsonElement e)
	{
		List<Board> out = new ArrayList<>();
		for (JsonObject o : objects(e, 3))
		{
			int size = (int) num(o, "size");
			String name = str(o, "name");
			if (size < 3 || size > 7 || name.isEmpty())
			{
				continue;
			}
			Board b = new Board(str(o, "id"), name.length() > 60 ? name.substring(0, 60) : name, size);
			for (JsonObject t : objects(o.get("tiles"), 49))
			{
				int idx = (int) num(t, "idx");
				String label = str(t, "label");
				if (idx >= 0 && idx < b.tiles.length && !label.isEmpty() && t.has("idx"))
				{
					JsonElement done = t.get("done");
					b.tiles[idx] = new Tile(label.length() > 80 ? label.substring(0, 80) : label, (int) num(t, "itemId"), str(t, "skill"),
						done != null && done.isJsonPrimitive() && done.getAsJsonPrimitive().isBoolean() && done.getAsBoolean());
				}
			}
			out.add(b);
		}
		return out;
	}

	static List<Stop> route(JsonElement e)
	{
		List<Stop> out = new ArrayList<>();
		for (JsonObject o : objects(e, 60))
		{
			int n = (int) num(o, "stop");
			String place = str(o, "place");
			if (n > 0 && !place.isEmpty())
			{
				Stop s = new Stop(n, place);
				for (JsonObject t : objects(o.get("teles"), 6))
				{
					if (!str(t, "travel").isEmpty())
					{
						s.teles.add(new Tele(str(t, "travel"), str(t, "how"), str(t, "cat"), str(t, "item")));
					}
				}
				out.add(s);
			}
		}
		return out;
	}

	static List<Daily> dailies(JsonElement e)
	{
		List<Daily> out = new ArrayList<>();
		for (JsonObject o : objects(e, 40))
		{
			String id = str(o, "id"), label = str(o, "label"), cadence = str(o, "cadence");
			if (!id.isEmpty() && !label.isEmpty())
			{
				Daily d = new Daily(id, label, cadence, (int) Math.max(1, num(o, "every")), (long) num(o, "dueAt"));
				d.how = str(o, "how");
				d.key = str(o, "key");
				d.itemId = (int) num(o, "itemId");
				if (d.condition())
				{
					JsonElement due = o.get("due");
					d.due = due != null && due.isJsonPrimitive() && due.getAsJsonPrimitive().isBoolean() && due.getAsBoolean();
				}
				out.add(d);
			}
		}
		return out;
	}

	static List<Patch> farm(JsonElement e)
	{
		List<Patch> out = new ArrayList<>();
		for (JsonObject o : objects(e, 150))
		{
			String region = str(o, "region");
			if (!region.isEmpty())
			{
				Patch p = new Patch(region, str(o, "name"), str(o, "type"), str(o, "crop"), (int) num(o, "itemId"), (long) num(o, "readyAt"),
					o.has("ready") && o.get("ready").isJsonPrimitive() && o.get("ready").getAsBoolean());
				p.travel = str(o, "travel");
				p.travelHow = str(o, "travelHow");
				p.kind = str(o, "kind");
				p.kindLabel = str(o, "kindLabel").isEmpty() ? p.type : str(o, "kindLabel");
				p.run = str(o, "run");
				p.order = (int) num(o, "order");
				p.stop = (int) num(o, "stop");
				p.travelCat = str(o, "travelCat");
				p.travelItem = str(o, "travelItem");
				String state = str(o, "state");
				if (STATES.contains(state))
				{
					p.state = state;
				}
				if (o.has("progress") && o.get("progress").isJsonPrimitive() && o.get("progress").getAsJsonPrimitive().isNumber())
				{
					p.progress = Math.max(0, Math.min(1, num(o, "progress")));
				}
				out.add(p);
			}
		}
		return out;
	}

	/** When a task done at `now` is next due, as Nib Tracker works it out: daily ones at the next UTC midnight(s). */
	static long nextDue(Daily d, long now)
	{
		if ("interval_min".equals(d.cadence))
		{
			return now + d.every * MINUTE;
		}
		return (now / DAY) * DAY + d.every * DAY;
	}

	/** A duration in a few characters: "34m", "5h 12m", "2d 3h" (under a minute shows as "1m"). */
	static String left(long ms)
	{
		long m = Math.max(1, (ms + MINUTE - 1) / MINUTE);
		if (m < 60)
		{
			return m + "m";
		}
		long h = m / 60;
		if (h < 24)
		{
			return h + "h " + (m % 60) + "m";
		}
		return (h / 24) + "d " + (h % 24) + "h";
	}

	/**
	 * A region's line: how many are ready, or how long until the next growing one is; with neither, Growing, Diseased,
	 * Dead, Empty or Unknown (a timer on a patch that isn't growing means nothing).
	 */
	static String summary(List<Patch> patches, long now)
	{
		long ready = patches.stream().filter(p -> FarmRows.ready(p, now)).count();
		if (ready > 0)
		{
			return ready + " ready";
		}
		Patch next = FarmRows.next(patches, now);
		return next == null ? FarmRows.idle(patches) : "next " + left(next.readyAt - now);
	}

	private static List<JsonObject> objects(JsonElement e, int max)
	{
		List<JsonObject> out = new ArrayList<>();
		if (e != null && e.isJsonArray())
		{
			for (JsonElement x : (JsonArray) e)
			{
				if (x.isJsonObject() && out.size() < max)
				{
					out.add(x.getAsJsonObject());
				}
			}
		}
		return out;
	}

	private static String str(JsonObject o, String k)
	{
		JsonElement v = o.get(k);
		return v != null && v.isJsonPrimitive() ? v.getAsString() : "";
	}

	private static double num(JsonObject o, String k)
	{
		JsonElement v = o.get(k);
		return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : 0;
	}
}
