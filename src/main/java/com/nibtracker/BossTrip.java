package com.nibtracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * One trip, from leaving the bank (or logging in, or respawning) to the next bank, death or logout. Only facts are
 * kept: the kill count messages, every item that went down or up in the inventory and worn equipment, and the damage
 * the player took. The server works out supplies used (folding potion doses, leaving out loot) from {@code lost} and
 * {@code gained}. Nothing here is shown in game; the totals are sent once the trip is over.
 */
final class BossTrip
{
	/* A trip with no kill for this long is over ("idle"); one that hasn't had a kill yet starts again. */
	static final long IDLE_MS = 30 * 60_000L;
	/* A "Fight duration" line belongs to the kill count line it comes with (the same tick, either order). */
	private static final long PAIR_MS = 10_000L;

	final long start;
	private Map<Integer, Integer> items;
	private final Map<Integer, Integer> lost = new TreeMap<>();
	private final Map<Integer, Integer> gained = new TreeMap<>();
	private final JsonArray kills = new JsonArray();
	private JsonObject lastKill;
	private long lastKillAt;
	private double heldSeconds = -1;
	private long heldAt;
	private int damage;
	private int region = -1;

	BossTrip(long now, Map<Integer, Integer> items)
	{
		this.start = now;
		this.items = new HashMap<>(items);
	}

	/** The inventory and worn equipment now (item id to quantity); what changed since last time is added up. */
	void items(Map<Integer, Integer> now)
	{
		for (Map.Entry<Integer, Integer> e : now.entrySet())
		{
			int diff = e.getValue() - items.getOrDefault(e.getKey(), 0);
			if (diff > 0)
			{
				gained.merge(e.getKey(), diff, Integer::sum);
			}
			else if (diff < 0)
			{
				lost.merge(e.getKey(), -diff, Integer::sum);
			}
		}
		for (Map.Entry<Integer, Integer> e : items.entrySet())
		{
			if (!now.containsKey(e.getKey()))
			{
				lost.merge(e.getKey(), e.getValue(), Integer::sum);
			}
		}
		items = new HashMap<>(now);
	}

	void kill(String boss, int kc, long at, int region)
	{
		JsonObject k = new JsonObject();
		k.addProperty("boss", boss);
		k.addProperty("kc", kc);
		k.addProperty("at", Instant.ofEpochMilli(at).toString());
		if (heldSeconds >= 0 && at - heldAt <= PAIR_MS)
		{
			k.addProperty("seconds", heldSeconds);
		}
		heldSeconds = -1;
		kills.add(k);
		lastKill = k;
		lastKillAt = at;
		if (this.region < 0)
		{
			this.region = region;
		}
	}

	/** A "Fight duration" (or similar) line: goes with the kill it came with, whichever line came first. */
	void time(double seconds, long at)
	{
		if (lastKill != null && !lastKill.has("seconds") && at - lastKillAt <= PAIR_MS)
		{
			lastKill.addProperty("seconds", seconds);
			return;
		}
		heldSeconds = seconds;
		heldAt = at;
	}

	void damage(int amount)
	{
		damage += amount;
	}

	boolean hasKills()
	{
		return kills.size() > 0;
	}

	boolean idle(long now)
	{
		return now - (hasKills() ? lastKillAt : start) > IDLE_MS;
	}

	JsonObject end(String reason, long now, int hp, int prayer)
	{
		JsonObject d = new JsonObject();
		d.addProperty("start", Instant.ofEpochMilli(start).toString());
		d.addProperty("end", Instant.ofEpochMilli(now).toString());
		d.addProperty("endReason", reason);
		d.add("kills", kills);
		d.add("lost", list(lost));
		d.add("gained", list(gained));
		d.addProperty("damageTaken", damage);
		d.addProperty("hpEnd", hp);
		d.addProperty("prayerEnd", prayer);
		d.addProperty("region", region);
		return d;
	}

	private static JsonArray list(Map<Integer, Integer> m)
	{
		JsonArray a = new JsonArray();
		m.forEach((id, qty) -> {
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("qty", qty);
			a.add(o);
		});
		return a;
	}
}
