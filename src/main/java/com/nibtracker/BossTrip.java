package com.nibtracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.client.util.Text;

/**
 * One trip, from leaving the bank (or logging in, or respawning) to the next bank, death or logout. Only facts are
 * kept: the kill count messages, every item that went down or up in the inventory and worn equipment, and the damage
 * the player took. The server works out supplies used (folding potion doses, leaving out loot) from {@code lost} and
 * {@code gained}. Each kill also gets its fight's figures, from the player's own hitsplats on the boss (see
 * {@link Fight}). Nothing here is shown in game; the totals are sent once the trip is over.
 */
final class BossTrip
{
	/* A trip with no kill for this long is over ("idle"); one that hasn't had a kill yet starts again. */
	static final long IDLE_MS = 30 * 60_000L;
	/* A "Fight duration" line belongs to the kill count line it comes with (the same tick, either order). */
	private static final long PAIR_MS = 10_000L;
	/* No hitsplat of the player's on a boss for a minute: they left that attempt (ran, hopped), so the next hit starts over. */
	static final int GIVEN_UP_TICKS = 100;

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
	/* The player's fight with each NPC they hit, by name (a boss's forms share one), until its kill count line. */
	private final Map<String, Fight> fights = new HashMap<>();
	/* A kill count line can come before its kill's last hitsplat in the same tick, so its figures are added a tick later. */
	private JsonObject timedKill;
	private String timedName;
	private int timedTick;

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

	void kill(String boss, int kc, long at, int region, int tick)
	{
		addFigures();
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
		timedKill = k;
		timedName = name(boss);
		timedTick = tick;
	}

	/** The player's own hitsplat (a miss too) on an NPC; {@code speed} is their weapon's attack speed in ticks. */
	void hit(String npc, int tick, int amount, int speed)
	{
		String n = name(npc);
		Fight f = fights.get(n);
		if (f == null || tick - f.last > GIVEN_UP_TICKS || tick < f.last) // (the tick count can start again after a hop)
		{
			fights.put(n, f = new Fight(tick));
		}
		f.speed = speed;
		f.until(tick);
		f.dealt += amount;
	}

	/** A heal hitsplat on an NPC, from anything. */
	void healed(String npc, int amount)
	{
		Fight f = fights.get(name(npc));
		if (f != null)
		{
			f.healed += amount;
		}
	}

	void died(String npc, int tick)
	{
		Fight f = fights.get(name(npc));
		if (f != null)
		{
			f.died = tick;
		}
	}

	/** Once a tick: a kill count line from an earlier tick gets its fight's figures. */
	void tick(int tick)
	{
		if (timedKill != null && tick > timedTick)
		{
			addFigures();
		}
	}

	private void addFigures()
	{
		Fight f = timedKill == null ? null : fights.remove(timedName);
		if (f != null)
		{
			f.addTo(timedKill);
		}
		timedKill = null;
	}

	/** Kill count lines and NPCs name a boss the same way, but for case, tags and a leading "The" ("The Nightmare"). */
	static String name(String s)
	{
		String n = s == null ? "" : Text.standardize(s);
		return n.startsWith("the ") ? n.substring(4) : n;
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
		addFigures();
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

	/**
	 * One kill's fight, for timing boss mechanics: game ticks from the player's first hitsplat on the boss to its death
	 * ({@code ticks}), the ticks in that span spent in gaps longer than their weapon's attack speed + 2 with no hitsplat of
	 * theirs on it ({@code idle}, each such gap in full), the damage they dealt ({@code dealt}) and the healing the boss
	 * got ({@code healed}).
	 */
	private static final class Fight
	{
		final int first;
		int last;
		int speed;
		int idle;
		int dealt;
		int healed;
		int died = -1;

		Fight(int tick)
		{
			first = last = tick;
		}

		void until(int tick)
		{
			if (tick - last > speed + 2)
			{
				idle += tick - last;
			}
			last = tick;
		}

		void addTo(JsonObject k)
		{
			if (died > last)
			{
				until(died); // finished off by something else: a thrall, venom, recoil
			}
			k.addProperty("ticks", last - first);
			k.addProperty("idle", idle);
			k.addProperty("dealt", dealt);
			k.addProperty("healed", healed);
		}
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
