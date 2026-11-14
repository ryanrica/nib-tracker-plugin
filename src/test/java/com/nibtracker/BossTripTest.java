package com.nibtracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.HitsplatID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BossTripTest
{
	private static final int SHARK = 385, PRAYER_4 = 2434, PRAYER_3 = 139, WHIP = 4151;

	private static Map<Integer, Integer> items(int... idQty)
	{
		Map<Integer, Integer> m = new HashMap<>();
		for (int i = 0; i < idQty.length; i += 2)
		{
			m.put(idQty[i], idQty[i + 1]);
		}
		return m;
	}

	private static int qty(JsonArray list, int id)
	{
		for (int i = 0; i < list.size(); i++)
		{
			JsonObject o = list.get(i).getAsJsonObject();
			if (o.get("id").getAsInt() == id)
			{
				return o.get("qty").getAsInt();
			}
		}
		return 0;
	}

	@Test
	public void addsUpWhatWentDownAndUp()
	{
		BossTrip t = new BossTrip(0, items(SHARK, 10, PRAYER_4, 2, WHIP, 1));
		t.items(items(SHARK, 8, PRAYER_4, 1, PRAYER_3, 1, WHIP, 1)); // ate two sharks, drank a dose
		t.items(items(SHARK, 9, PRAYER_3, 1, WHIP, 1)); // picked up a shark, dropped the last (4)
		t.kill("Vorkath", 812, 1_000, 9023, 0);
		JsonObject d = t.end("banked", 60_000, 61, 22);
		assertEquals(2, qty(d.getAsJsonArray("lost"), SHARK));
		assertEquals(1, qty(d.getAsJsonArray("gained"), SHARK));
		assertEquals(2, qty(d.getAsJsonArray("lost"), PRAYER_4));
		assertEquals(1, qty(d.getAsJsonArray("gained"), PRAYER_3));
		assertEquals(0, qty(d.getAsJsonArray("lost"), WHIP));
	}

	@Test
	public void pairsTheFightDurationWithItsKillEitherWay()
	{
		BossTrip t = new BossTrip(0, items());
		t.kill("Vorkath", 1, 1_000, 9023, 0);
		t.time(101.4, null, 1_000); // after the kill count line
		t.time(99.0, null, 2_000); // a stray second time doesn't overwrite it
		t.time(95.0, "3", 50_000); // before the next kill count line, naming a delve level
		t.kill("Vorkath", 2, 50_000, 9023, 0);
		t.kill("Vorkath", 3, 120_000, 9023, 0); // no time said for this one
		JsonArray kills = t.end("banked", 130_000, 50, 10).getAsJsonArray("kills");
		assertEquals(3, kills.size());
		assertFalse(kills.get(0).getAsJsonObject().has("delve"));
		assertEquals("3", kills.get(1).getAsJsonObject().get("delve").getAsString());
		assertEquals(101.4, kills.get(0).getAsJsonObject().get("seconds").getAsDouble(), 0);
		assertEquals(95.0, kills.get(1).getAsJsonObject().get("seconds").getAsDouble(), 0);
		assertFalse(kills.get(2).getAsJsonObject().has("seconds"));
		assertEquals("1970-01-01T00:00:50Z", kills.get(1).getAsJsonObject().get("at").getAsString());
	}

	@Test
	public void sendsTheTotalsAndHowItEnded()
	{
		BossTrip t = new BossTrip(0, items());
		assertFalse(t.hasKills());
		t.damage(20);
		t.kill("Zulrah", 300, 5_000, 9007, 0);
		t.kill("Zulrah", 301, 6_000, 9008, 0);
		t.damage(35);
		JsonObject d = t.end("died", 90_000, 0, 3);
		assertEquals("1970-01-01T00:00:00Z", d.get("start").getAsString());
		assertEquals("1970-01-01T00:01:30Z", d.get("end").getAsString());
		assertEquals("died", d.get("endReason").getAsString());
		assertEquals(55, d.get("damageTaken").getAsInt());
		assertEquals(0, d.get("hpEnd").getAsInt());
		assertEquals(3, d.get("prayerEnd").getAsInt());
		assertEquals(9007, d.get("region").getAsInt()); // where the first kill was
	}

	@Test
	public void goesIdleHalfAnHourAfterTheLastKill()
	{
		BossTrip t = new BossTrip(0, items());
		assertFalse(t.idle(BossTrip.IDLE_MS));
		assertTrue(t.idle(BossTrip.IDLE_MS + 1)); // no kill yet: counted from the start
		t.kill("Vorkath", 1, BossTrip.IDLE_MS, 9023, 0);
		assertFalse(t.idle(BossTrip.IDLE_MS * 2));
		assertTrue(t.idle(BossTrip.IDLE_MS * 2 + 1));
	}

	private static JsonObject kill(JsonObject trip, int i)
	{
		return trip.getAsJsonArray("kills").get(i).getAsJsonObject();
	}

	@Test
	public void timesEachKillFromTheFirstHitToTheDeath()
	{
		BossTrip t = new BossTrip(0, items());
		t.hit("Vorkath", 100, 30, 5);
		t.hit("Vorkath", 105, 0, 5); // a miss is a hit too
		t.hit("Zombified Spawn", 120, 1, 5); // another NPC: not Vorkath's fight
		t.hit("Vorkath", 135, 40, 5); // 30 ticks with no hit on it (acid, the spawn): idle in full
		t.healed("Vorkath", 12);
		t.kill("Vorkath", 1, 85_000, 9023, 141); // the kill count line can come before the last hitsplat
		t.tick(141);
		t.hit("Vorkath", 141, 20, 5); // 6 ticks: within the weapon's speed + 2
		t.died("Vorkath", 141);
		t.tick(142);
		t.hit("Vorkath", 150, 99, 5); // the next kill's fight
		JsonObject k = kill(t.end("banked", 90_000, 50, 10), 0);
		assertEquals(41, k.get("ticks").getAsInt());
		assertEquals(30, k.get("idle").getAsInt());
		assertEquals(90, k.get("dealt").getAsInt());
		assertEquals(12, k.get("healed").getAsInt());
	}

	@Test
	public void followsABossThroughItsFormsByName()
	{
		BossTrip t = new BossTrip(0, items());
		t.hit("The Nightmare", 10, 30, 4);
		t.died("The Nightmare", 12); // a form's death doesn't end the fight
		t.hit("The Nightmare", 14, 30, 4);
		t.died("The Nightmare", 24); // finished off 10 ticks after the player's last hit (a thrall): idle
		t.kill("Nightmare", 5, 20_000, 15515, 24);
		JsonObject k = kill(t.end("banked", 30_000, 50, 10), 0);
		assertEquals(14, k.get("ticks").getAsInt());
		assertEquals(10, k.get("idle").getAsInt());
		assertEquals(60, k.get("dealt").getAsInt());
		assertEquals(0, k.get("healed").getAsInt());
	}

	@Test
	public void sendsNoFiguresWithoutAFightAndStartsOverAfterAGivenUpAttempt()
	{
		BossTrip t = new BossTrip(0, items());
		t.kill("Barrows chests", 1, 1_000, 14131, 1); // no NPC of that name was hit
		t.hit("Callisto", 100, 50, 4); // then ran off to hop
		t.hit("Callisto", 101 + BossTrip.GIVEN_UP_TICKS, 40, 4);
		t.hit("Callisto", 205, 40, 4);
		t.kill("Callisto", 2, 200_000, 13472, 205);
		JsonObject d = t.end("banked", 210_000, 50, 10);
		assertFalse(kill(d, 0).has("ticks"));
		assertEquals(4, kill(d, 1).get("ticks").getAsInt());
		assertEquals(0, kill(d, 1).get("idle").getAsInt());
		assertEquals(80, kill(d, 1).get("dealt").getAsInt());
	}

	@Test
	public void countsOnlyHitsplatsThatTakeHitpoints()
	{
		assertTrue(TripTracker.takesHitpoints(HitsplatID.DAMAGE_ME));
		assertTrue(TripTracker.takesHitpoints(HitsplatID.VENOM));
		assertTrue(TripTracker.takesHitpoints(HitsplatID.DAMAGE_MAX_ME_ORANGE));
		assertFalse(TripTracker.takesHitpoints(HitsplatID.BLOCK_ME));
		assertFalse(TripTracker.takesHitpoints(HitsplatID.HEAL));
		assertFalse(TripTracker.takesHitpoints(HitsplatID.PRAYER_DRAIN));
		assertFalse(TripTracker.takesHitpoints(HitsplatID.DAMAGE_OTHER));
	}
}
