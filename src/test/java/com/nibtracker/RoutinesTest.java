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
}
