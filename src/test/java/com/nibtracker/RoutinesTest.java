package com.nibtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class RoutinesTest
{
	private static final long NOON = 20_000 * Routines.DAY + 12 * 60 * Routines.MINUTE;

	@Test
	public void readsDailiesAndPatchesAndSkipsJunk()
	{
		List<Routines.Daily> d = Routines.dailies(new JsonParser().parse(
			"[{\"id\":\"a\",\"label\":\"Battlestaves\",\"cadence\":\"daily\",\"every\":1,\"dueAt\":5}, {\"id\":\"\"}, 3]"));
		assertEquals(1, d.size());
		assertEquals(5, d.get(0).dueAt);
		List<Routines.Patch> f = Routines.farm(new JsonParser().parse(
			"[{\"region\":\"Catherby\",\"type\":\"Herb\",\"crop\":\"Ranarr\",\"itemId\":257,\"readyAt\":100,\"ready\":false}, {\"crop\":\"x\"}]"));
		assertEquals(1, f.size());
		assertEquals(257, f.get(0).itemId);
		assertTrue(Routines.dailies(null).isEmpty());
	}

	@Test
	public void readsKeyItemIdAndCondition()
	{
		List<Routines.Daily> d = Routines.dailies(new JsonParser().parse(
			"[{\"id\":\"m\",\"key\":\"miscellania\",\"label\":\"Miscellania\",\"cadence\":\"condition\",\"every\":1,\"due\":true,\"itemId\":8150},"
				+ "{\"id\":\"z\",\"label\":\"Zaff\",\"cadence\":\"daily\",\"dueAt\":5}]"));
		Routines.Daily m = d.get(0);
		assertEquals("miscellania", m.key);
		assertEquals(8150, m.itemId);
		assertEquals("condition", m.cadence);
		assertEquals(Boolean.TRUE, m.due);
		Routines.Daily z = d.get(1);
		assertEquals("", z.key);
		assertEquals(0, z.itemId);
		assertEquals(null, z.due);
	}

	@Test
	public void feedWithoutStateIsGrowingOrReady()
	{
		List<Routines.Patch> f = Routines.farm(new JsonParser().parse(
			"[{\"region\":\"Catherby\",\"crop\":\"Ranarr\",\"ready\":true},{\"region\":\"Ardougne\",\"crop\":\"Guam\"}]"));
		assertEquals("ready", f.get(0).state);
		assertEquals("growing", f.get(1).state);
		assertEquals(-1, f.get(1).progress, 0);
	}

	@Test
	public void readsStateProgressAndId()
	{
		Routines.Patch p = Routines.farm(new JsonParser().parse(
			"[{\"region\":\"Farming Guild\",\"name\":\"\",\"kind\":\"anima\",\"state\":\"diseased\",\"progress\":0.5}]")).get(0);
		assertEquals("diseased", p.state);
		assertEquals(0.5, p.progress, 0);
		assertTrue(p.needsAttention());
		assertEquals("Farming Guild||anima", p.id());
		assertFalse(p.readyAt(Long.MAX_VALUE)); // no readyAt, not ready
	}

	@Test
	public void nextDueFollowsTheCadence()
	{
		Routines.Daily daily = new Routines.Daily("a", "x", "daily", 1, 0);
		assertEquals(20_001 * Routines.DAY, Routines.nextDue(daily, NOON));
		Routines.Daily tears = new Routines.Daily("b", "Tears of Guthix", "rolling_days", 7, 0);
		assertEquals(20_007 * Routines.DAY, Routines.nextDue(tears, NOON));
		Routines.Daily timer = new Routines.Daily("c", "x", "interval_min", 90, 0);
		assertEquals(NOON + 90 * Routines.MINUTE, Routines.nextDue(timer, NOON));
	}

	@Test
	public void shortTimesAndRegionSummaries()
	{
		assertEquals("1m", Routines.left(20_000));
		assertEquals("34m", Routines.left(34 * Routines.MINUTE));
		assertEquals("5h 12m", Routines.left(312 * Routines.MINUTE));
		assertEquals("2d 3h", Routines.left(51 * 60 * Routines.MINUTE));
		Routines.Patch ready = new Routines.Patch("Catherby", "", "Herb", "Ranarr", 0, NOON - 1, false);
		Routines.Patch later = new Routines.Patch("Catherby", "", "Allotment", "Potato", 0, NOON + 34 * Routines.MINUTE, false);
		assertTrue(ready.readyAt(NOON));
		assertFalse(later.readyAt(NOON));
		assertEquals("1 ready", Routines.summary(Arrays.asList(ready, later), NOON));
		assertEquals("next 34m", Routines.summary(Arrays.asList(later), NOON));
	}

	@Test
	public void routeStopsWithTheirTeleportsBestFirst()
	{
		List<Routines.Stop> r = Routines.route(new JsonParser().parse(
			"[{\"stop\":1,\"place\":\"Ardougne\",\"teles\":[{\"travel\":\"Ardougne cloak 2\",\"cat\":\"ITEM\",\"item\":\"Ardougne cloak 2\"},{\"travel\":\"Ardougne Teleport\",\"cat\":\"SPELLBOOK\"}]},"
				+ "{\"stop\":0,\"place\":\"Nowhere\"},{\"stop\":2,\"place\":\"\"}]"));
		assertEquals(1, r.size());
		assertEquals("Ardougne", r.get(0).place);
		assertEquals(Arrays.asList("Ardougne cloak 2", "Ardougne Teleport"), Arrays.asList(r.get(0).teles.get(0).travel, r.get(0).teles.get(1).travel));
		assertEquals("", r.get(0).teles.get(1).item);
		assertTrue(Routines.route(null).isEmpty());
	}

	@Test
	public void bingoBoardsKeepOnlyWhatFits()
	{
		List<Routines.Board> b = Routines.bingo(new JsonParser().parse(
			"[{\"id\":\"a\",\"name\":\"Clan bingo\",\"size\":3,\"tiles\":["
				+ "{\"idx\":0,\"label\":\"Tanzanite fang\",\"itemId\":12922,\"done\":true},"
				+ "{\"idx\":4,\"label\":\"80 Slayer\",\"skill\":\"Slayer\"},"
				+ "{\"idx\":9,\"label\":\"Off the board\"},{\"label\":\"No idx\"}]},"
				+ "{\"name\":\"Too big\",\"size\":8},{\"name\":\"\",\"size\":4}]"));
		assertEquals(1, b.size());
		assertEquals(9, b.get(0).tiles.length);
		assertEquals("Tanzanite fang", b.get(0).tiles[0].label);
		assertTrue(b.get(0).tiles[0].done);
		assertEquals("Slayer", b.get(0).tiles[4].skill);
		assertFalse(b.get(0).tiles[4].done);
		// Empty slots, a tile past the board and one with no idx are left out.
		assertEquals(null, b.get(0).tiles[1]);
		assertEquals(2, java.util.Arrays.stream(b.get(0).tiles).filter(java.util.Objects::nonNull).count());
		assertTrue(Routines.bingo(null).isEmpty());
	}
}
