package com.nibtracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Farm patches and bird houses worked out from what RuneLite's Time Tracking plugin saves, so the Farming tab is
 * current without the website open. A port of the web app's TT.predictAll, itself a port of RuneLite's
 * FarmingTracker.predictPatch (BSD-2-Clause); the tables in timetracking.json come from the same source. Rows mean
 * the same as the feed's (see plugExtra in the web app), so the two join by id(). Pure, so it can be tested.
 */
final class PatchPredictor
{
	/* Spirit trees are left out of runs and reminders, as on the website. */
	private static final String NO_TRACK = "SPIRIT_TREE";
	/* Time Tracking's tab names, as the feed shows them (TAB_LABEL in the web app). */
	private static final Map<String, String> TAB_LABEL = new HashMap<>();
	/* Bird house spaces (Time Tracking's key) and the house built, by (value - 1) / 3. */
	private static final Map<Integer, String> BIRDHOUSE_SPACES = new LinkedHashMap<>();
	private static final String[] BIRDHOUSE_NAMES = {"Bird House", "Oak Bird House", "Willow Bird House", "Teak Bird House", "Maple Bird House",
		"Mahogany Bird House", "Yew Bird House", "Magic Bird House", "Redwood Bird House"};
	private static final int[] BIRDHOUSE_ITEMS = {ItemID.BIRDHOUSE_NORMAL, ItemID.BIRDHOUSE_OAK, ItemID.BIRDHOUSE_WILLOW, ItemID.BIRDHOUSE_TEAK,
		ItemID.BIRDHOUSE_MAPLE, ItemID.BIRDHOUSE_MAHOGANY, ItemID.BIRDHOUSE_YEW, ItemID.BIRDHOUSE_MAGIC, ItemID.BIRDHOUSE_REDWOOD};
	/* A seeded bird house is full after 50 minutes. */
	private static final long BIRDHOUSE_SECS = 50 * 60;

	static
	{
		TAB_LABEL.put("BIRD_HOUSE", "Bird house");
		TAB_LABEL.put("ALLOTMENT", "Allotment");
		TAB_LABEL.put("FLOWER", "Flower");
		TAB_LABEL.put("HERB", "Herb");
		TAB_LABEL.put("TREE", "Tree");
		TAB_LABEL.put("FRUIT_TREE", "Fruit Tree");
		TAB_LABEL.put("HOPS", "Hops");
		TAB_LABEL.put("BUSH", "Bush");
		TAB_LABEL.put("GRAPE", "Grape");
		TAB_LABEL.put("SPECIAL", "Special");
		BIRDHOUSE_SPACES.put(VarPlayerID.BIRDHOUSE_TRANSMIT_A, "Mushroom Meadow (North)");
		BIRDHOUSE_SPACES.put(VarPlayerID.BIRDHOUSE_TRANSMIT_B, "Mushroom Meadow (South)");
		BIRDHOUSE_SPACES.put(VarPlayerID.BIRDHOUSE_TRANSMIT_C, "Verdant Valley (Northeast)");
		BIRDHOUSE_SPACES.put(VarPlayerID.BIRDHOUSE_TRANSMIT_D, "Verdant Valley (Southwest)");
	}

	/** A crop: label, minutes per growth tick, stages, minutes per regrowth tick, harvest stages, item id. */
	private static final class Produce
	{
		String label;
		int tick;
		int stages;
		int regrow;
		int harvest;
		int itemId;
	}

	/** One value range of a patch: what's in it, its state, and its stage (a * value + b). */
	private static final class Range
	{
		int lo;
		int hi;
		String produce;
		String crop;
		int a;
		int b;
	}

	private static final class Impl
	{
		String tab;
		final List<Range> ranges = new ArrayList<>();
	}

	private static final class Spot
	{
		final String region;
		final String name;
		final String impl;

		Spot(String region, String name, String impl)
		{
			this.region = region;
			this.name = name;
			this.impl = impl;
		}
	}

	/** Kind label and group (run). */
	private static final class Kind
	{
		final String label;
		final String group;

		Kind(String label, String group)
		{
			this.label = label;
			this.group = group;
		}
	}

	private final Map<String, Produce> produce = new HashMap<>();
	private final Map<String, Impl> impls = new HashMap<>();
	/* "<regionId>.<varbit>" -> patch, in the table's order. */
	private final Map<String, Spot> patches = new LinkedHashMap<>();
	private final Map<String, Kind> kinds = new HashMap<>();
	private final Map<String, String> implKind = new HashMap<>();
	private final Map<String, String> splitTree = new HashMap<>();

	private PatchPredictor()
	{
	}

	/** Reads timetracking.json from the jar. Call once, off the client thread. */
	static PatchPredictor load(Gson gson)
	{
		try (InputStream in = PatchPredictor.class.getResourceAsStream("timetracking.json"))
		{
			if (in == null)
			{
				throw new IllegalStateException("timetracking.json is missing");
			}
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				return from(gson.fromJson(r, JsonObject.class));
			}
		}
		catch (IOException e)
		{
			throw new IllegalStateException("timetracking.json can't be read", e);
		}
	}

	private static PatchPredictor from(JsonObject j)
	{
		PatchPredictor t = new PatchPredictor();
		for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("produce").entrySet())
		{
			JsonArray a = e.getValue().getAsJsonArray();
			Produce p = new Produce();
			p.label = a.get(0).getAsString();
			p.tick = a.get(1).getAsInt();
			p.stages = a.get(2).getAsInt();
			p.regrow = a.get(3).getAsInt();
			p.harvest = a.get(4).getAsInt();
			p.itemId = a.get(5).isJsonNull() ? 0 : a.get(5).getAsInt();
			t.produce.put(e.getKey(), p);
		}
		for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("impl").entrySet())
		{
			JsonObject o = e.getValue().getAsJsonObject();
			Impl impl = new Impl();
			impl.tab = o.get("tab").getAsString();
			for (JsonElement re : o.getAsJsonArray("r"))
			{
				JsonArray a = re.getAsJsonArray();
				Range r = new Range();
				r.lo = a.get(0).getAsInt();
				r.hi = a.get(1).getAsInt();
				r.produce = a.get(2).getAsString();
				r.crop = a.get(3).getAsString();
				r.a = a.get(4).getAsInt();
				r.b = a.get(5).getAsInt();
				impl.ranges.add(r);
			}
			t.impls.put(e.getKey(), impl);
		}
		for (JsonElement re : j.getAsJsonArray("regions"))
		{
			JsonArray region = re.getAsJsonArray();
			String name = region.get(0).getAsString();
			int id = region.get(1).getAsInt();
			for (JsonElement pe : region.get(2).getAsJsonArray())
			{
				JsonArray p = pe.getAsJsonArray();
				t.patches.put(id + "." + p.get(1).getAsInt(), new Spot(name, p.get(0).getAsString(), p.get(2).getAsString()));
			}
		}
		for (JsonElement ke : j.getAsJsonArray("kinds"))
		{
			JsonArray k = ke.getAsJsonArray();
			t.kinds.put(k.get(0).getAsString(), new Kind(k.get(1).getAsString(), k.get(2).getAsString()));
		}
		for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("implKind").entrySet())
		{
			t.implKind.put(e.getKey(), e.getValue().getAsString());
		}
		for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("splitTree").entrySet())
		{
			t.splitTree.put(e.getKey(), e.getValue().getAsJsonArray().get(1).getAsString());
		}
		return t;
	}

	/** Time Tracking's farming tick offset: minutes and how sure it is, either null when not saved. */
	private static final class Offset
	{
		Long mins;
		Long precision;
	}

	/**
	 * Every saved patch and bird house at nowMs. entries are Time Tracking's keys for this character without the
	 * profile ("11062.4774" = "<value>:<unix seconds>", "birdhouse.1626", "farmTickOffset" ...). Rows come in the
	 * table's order; unknown patches and values are skipped.
	 */
	List<Routines.Patch> predict(Map<String, String> entries, long nowMs)
	{
		long now = Math.floorDiv(nowMs, 1000);
		Offset tt = new Offset();
		tt.mins = number(entries.get("farmTickOffset"));
		tt.precision = number(entries.get("farmTickOffsetPrecision"));
		boolean autoweed = "2".equals(entries.get("autoweed"));
		List<Routines.Patch> rows = new ArrayList<>();
		for (Map.Entry<String, Spot> e : patches.entrySet())
		{
			long[] saved = saved(entries.get(e.getKey()));
			Spot patch = e.getValue();
			if (saved == null || NO_TRACK.equals(patch.impl))
			{
				continue;
			}
			Routines.Patch p = patch(patch, (int) saved[0], saved[1], now, tt, autoweed);
			if (p != null)
			{
				rows.add(p);
			}
		}
		for (Map.Entry<Integer, String> e : BIRDHOUSE_SPACES.entrySet())
		{
			long[] saved = saved(entries.get("birdhouse." + e.getKey()));
			if (saved != null)
			{
				Routines.Patch p = birdhouse(e.getValue(), (int) saved[0], saved[1], nowMs);
				if (p != null)
				{
					rows.add(p);
				}
			}
		}
		return rows;
	}

	/** A spirit tree spot: where it is, what's there (Not planted, Growing, Grown, Diseased, Dead, Unknown), and growth 0..1 (-1 unknown). */
	static final class SpiritTree
	{
		final String region;
		final String state;
		final double progress;

		SpiritTree(String region, String state, double progress)
		{
			this.region = region;
			this.state = state;
			this.progress = progress;
		}
	}

	/**
	 * Every spirit tree spot at nowMs, by place name, whether or not Time Tracking has saved it (Unknown when not). Grown
	 * is the final stage, worked out with the same growth ticks as any patch. These are never farm timers.
	 */
	List<SpiritTree> spiritTrees(Map<String, String> entries, long nowMs)
	{
		long now = Math.floorDiv(nowMs, 1000);
		Offset tt = new Offset();
		tt.mins = number(entries.get("farmTickOffset"));
		tt.precision = number(entries.get("farmTickOffsetPrecision"));
		List<SpiritTree> out = new ArrayList<>();
		for (Map.Entry<String, Spot> e : patches.entrySet())
		{
			Spot spot = e.getValue();
			if (!NO_TRACK.equals(spot.impl))
			{
				continue;
			}
			String region = "Kourend".equals(spot.region) ? "Hosidius" : spot.region;
			long[] saved = saved(entries.get(e.getKey()));
			Routines.Patch p = saved == null ? null : patch(spot, (int) saved[0], saved[1], now, tt, false);
			if (p == null)
			{
				out.add(new SpiritTree(region, "Unknown", -1));
				continue;
			}
			switch (p.state)
			{
				case "ready":
					out.add(new SpiritTree(region, "Grown", 1));
					break;
				case "empty":
					out.add(new SpiritTree(region, "Not planted", -1));
					break;
				case "diseased":
					out.add(new SpiritTree(region, "Diseased", p.progress));
					break;
				case "dead":
					out.add(new SpiritTree(region, "Dead", p.progress));
					break;
				default:
					out.add(new SpiritTree(region, "Growing", p.progress));
			}
		}
		out.sort(Comparator.comparing(t -> t.region));
		return out;
	}

	private Routines.Patch patch(Spot patch, int value, long time, long now, Offset tt, boolean autoweed)
	{
		Impl impl = impls.get(patch.impl);
		Range st = impl == null ? null : stateFor(impl, value);
		if (st == null)
		{
			return null;
		}
		Produce pr = produce.get(st.produce);
		if (pr == null)
		{
			return null;
		}
		int stage = st.a * value + st.b;
		int stages = "HARVESTABLE".equals(st.crop) || "FILLING".equals(st.crop) ? pr.harvest : pr.stages;
		int rate = "GROWING".equals(st.crop) ? pr.tick : "HARVESTABLE".equals(st.crop) ? pr.regrow : 0;
		if (autoweed && "WEEDS".equals(st.produce))
		{
			stage = 0;
			stages = 1;
			rate = 0;
		}
		long done = 0;
		if (rate > 0)
		{
			long tNow = tickTime(rate, 0, now, tt), tThen = tickTime(rate, 0, time, tt);
			long delta = Math.floorDiv(tNow - tThen, rate * 60L);
			done = tickTime(rate, stages - 1 - stage, tThen, tt);
			stage = (int) Math.min(stages - 1, stage + delta);
		}
		boolean weeds = "WEEDS".equals(st.produce) || "SCARECROW".equals(st.produce);
		String status = st.crop;
		if ("GROWING".equals(st.crop) && done != 0 && done <= now)
		{
			status = "HARVESTABLE";
		}
		if (weeds)
		{
			status = "EMPTY";
		}
		String type = splitTree.containsKey(patch.impl) ? splitTree.get(patch.impl) : TAB_LABEL.getOrDefault(impl.tab, impl.tab);
		String crop = weeds ? ("SCARECROW".equals(st.produce) ? "Scarecrow" : "Weeds") : pr.label;
		Routines.Patch p = new Routines.Patch(patch.region, patch.name, type, crop, weeds ? 0 : pr.itemId, done != 0 ? done * 1000 : 0,
			"HARVESTABLE".equals(status));
		finish(p, implKind.getOrDefault(patch.impl, "herb"), status, stage, stages);
		return p;
	}

	/** A bird house; null for an empty one (nothing built, or no seeds yet), which the feed leaves out too. */
	private Routines.Patch birdhouse(String space, int value, long time, long nowMs)
	{
		int type = value > 0 ? (value - 1) / 3 : -1;
		boolean known = type >= 0 && type < BIRDHOUSE_NAMES.length;
		boolean seeded = value > 0 && value % 3 == 0;
		if (!seeded)
		{
			return null;
		}
		long readyAt = (time + BIRDHOUSE_SECS) * 1000;
		boolean ready = readyAt <= nowMs;
		Routines.Patch p = new Routines.Patch("Fossil Island", space, TAB_LABEL.get("BIRD_HOUSE"), known ? BIRDHOUSE_NAMES[type] : "Empty",
			known ? BIRDHOUSE_ITEMS[type] : 0, readyAt, ready);
		finish(p, "birdhouse", ready ? "HARVESTABLE" : "GROWING", ready ? 1 : 0, 2);
		return p;
	}

	/** The fields the feed adds: state, progress, kind, its label and its run. */
	private void finish(Routines.Patch p, String kind, String status, int stage, int stages)
	{
		switch (status)
		{
			case "HARVESTABLE":
				p.state = "ready";
				break;
			case "EMPTY":
				p.state = "empty";
				break;
			case "DISEASED":
				p.state = "diseased";
				break;
			case "DEAD":
				p.state = "dead";
				break;
			default:
				p.state = "growing";
		}
		if (stages > 1)
		{
			p.progress = Math.max(0, Math.min(1, (double) stage / (stages - 1)));
		}
		Kind k = kinds.get(kind);
		p.kind = kind;
		p.kindLabel = k != null ? k.label : p.type;
		p.run = k != null ? k.group : "";
	}

	private static Range stateFor(Impl impl, int value)
	{
		for (Range r : impl.ranges)
		{
			if (value >= r.lo && value <= r.hi)
			{
				return r;
			}
		}
		return null;
	}

	/** The unix second of the farming tick `ticks` ticks after the one at or before t, for a crop growing every tickRate minutes. */
	private static long tickTime(int tickRate, int ticks, long t, Offset tt)
	{
		long off = 0;
		if (tt.precision != null && tt.mins != null && (tt.precision >= tickRate || tt.precision >= 40))
		{
			off = (tt.mins % tickRate) * 60;
		}
		long now = t + off;
		return now - (now % (tickRate * 60L)) + (long) ticks * tickRate * 60 - off;
	}

	/** "<value>:<unix seconds>" as {value, seconds}; null when it doesn't parse or has no time. */
	private static long[] saved(String s)
	{
		if (s == null)
		{
			return null;
		}
		int colon = s.indexOf(':');
		if (colon < 0)
		{
			return null;
		}
		Long value = number(s.substring(0, colon)), time = number(s.substring(colon + 1));
		if (value == null || time == null || time <= 0 || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
		{
			return null;
		}
		return new long[]{value, time};
	}

	private static Long number(String s)
	{
		if (s == null)
		{
			return null;
		}
		try
		{
			return Long.parseLong(s.trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
