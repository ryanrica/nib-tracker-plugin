package com.nibtracker;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FarmRowsTest
{
	private static final long NOW = 1_000_000_000L;
	private static final long MIN = 60_000L;

	private static Routines.Patch patch(String crop, String state, long readyAt)
	{
		Routines.Patch p = new Routines.Patch("Catherby", "Catherby", "Herb", crop, 0, readyAt, "ready".equals(state));
		p.kind = "herb";
		p.kindLabel = "Herbs";
		p.state = state;
		return p;
	}

	@Test
	public void statusForEachState()
	{
		assertEquals("Diseased", FarmRows.status(patch("Ranarr", "diseased", NOW - MIN), NOW));
		assertEquals("Dead", FarmRows.status(patch("Ranarr", "dead", 0), NOW));
		assertEquals("Ready", FarmRows.status(patch("Ranarr", "ready", 0), NOW));
		assertEquals("Ready", FarmRows.status(patch("Ranarr", "growing", NOW - MIN), NOW));
		assertEquals("Empty", FarmRows.status(patch("", "empty", 0), NOW));
		assertEquals("Unknown", FarmRows.status(patch("", "unknown", 0), NOW));
		assertEquals("30m", FarmRows.status(patch("Ranarr", "growing", NOW + 30 * MIN), NOW));
		assertEquals("Growing", FarmRows.status(patch("Ranarr", "growing", 0), NOW));
	}

	@Test
	public void diseasedIsNeverReady()
	{
		assertFalse(FarmRows.ready(patch("Ranarr", "diseased", NOW - MIN), NOW));
		assertFalse(FarmRows.ready(patch("", "empty", NOW - MIN), NOW));
		assertTrue(FarmRows.ready(patch("Ranarr", "ready", 0), NOW));
	}

	@Test
	public void titles()
	{
		assertEquals("Ranarr", FarmRows.title(patch("Ranarr", "growing", 0)));
		assertEquals("Unknown", FarmRows.title(patch("Ranarr", "unknown", 0)));
		assertEquals("Herbs", FarmRows.title(patch("", "empty", 0)));
	}

	@Test
	public void subLeavesOutRepeatsAndBlanks()
	{
		assertEquals("Catherby · Camelot Teleport", FarmRows.sub("Catherby", "Catherby", "Camelot Teleport"));
		assertEquals("Farming Guild · Tree patch · Skills necklace", FarmRows.sub("Farming Guild", "Tree patch", "Skills necklace"));
		assertEquals("Ardougne", FarmRows.sub("Ardougne", "", ""));
	}

	@Test
	public void readyFirstKeepsOrderOtherwise()
	{
		List<String> rows = Arrays.asList("a", "B", "c", "D");
		assertEquals(Arrays.asList("B", "D", "a", "c"), FarmRows.readyFirst(rows, s -> Character.isUpperCase(s.charAt(0))));
	}

	@Test
	public void shiftClickTicksTheRange()
	{
		List<String> ids = Arrays.asList("a", "b", "c", "d", "e");
		Set<String> ticked = new HashSet<>(Collections.singleton("a"));
		FarmRows.tickRange(ids, 0, 3, true, ticked);
		assertEquals(new HashSet<>(Arrays.asList("a", "b", "c", "d")), ticked);
		// Upwards, and unticking.
		FarmRows.tickRange(ids, 3, 1, false, ticked);
		assertEquals(new HashSet<>(Collections.singletonList("a")), ticked);
	}

	@Test
	public void clickWithNoAnchorTicksOne()
	{
		List<String> ids = Arrays.asList("a", "b", "c");
		Set<String> ticked = new HashSet<>();
		FarmRows.tickRange(ids, -1, 2, true, ticked);
		assertEquals(Collections.singleton("c"), ticked);
	}

	@Test
	public void hiddenRegionsKeepThoseNotOffered()
	{
		List<String> offered = Arrays.asList("Ardougne", "Catherby", "Falador");
		Set<String> before = new LinkedHashSet<>(Arrays.asList("Catherby", "Trollheim"));
		Set<String> shown = new HashSet<>(Arrays.asList("Catherby"));
		assertEquals(new LinkedHashSet<>(Arrays.asList("Trollheim", "Ardougne", "Falador")), FarmRows.hiddenAfter(offered, before, shown));
	}

	@Test
	public void favouritesKeepSavedOrderAndAppendNew()
	{
		List<String> saved = Arrays.asList("x", "c", FarmGroups.CONTRACT_ID, "a");
		List<String> offered = Arrays.asList("a", "b", "c", FarmGroups.CONTRACT_ID);
		Set<String> ticked = new HashSet<>(Arrays.asList("a", "b", FarmGroups.CONTRACT_ID));
		// "x" wasn't offered (no data now), so it stays; "c" was unticked; "b" is new and goes last.
		assertEquals(Arrays.asList("x", FarmGroups.CONTRACT_ID, "a", "b"), FarmRows.favouritesAfter(saved, offered, ticked));
	}

	@Test
	public void summaryIgnoresPatchesThatArentGrowing()
	{
		// A diseased crop with a timer that has run out isn't "1 ready", and one still timed isn't "next".
		assertEquals("Diseased", Routines.summary(Arrays.asList(patch("Ranarr", "diseased", NOW - MIN)), NOW));
		assertEquals("Dead", Routines.summary(Arrays.asList(patch("Ranarr", "dead", NOW + MIN)), NOW));
		assertEquals("Empty", Routines.summary(Arrays.asList(patch("", "empty", 0), patch("", "empty", NOW + MIN)), NOW));
		assertEquals("Unknown", Routines.summary(Arrays.asList(patch("", "unknown", NOW + MIN)), NOW));
		assertEquals("Diseased", Routines.summary(Arrays.asList(patch("", "empty", 0), patch("Ranarr", "diseased", NOW + MIN)), NOW));
		assertEquals("Growing", Routines.summary(Arrays.asList(patch("", "empty", 0), patch("Guam", "growing", 0)), NOW));
		assertEquals("next 30m", Routines.summary(Arrays.asList(patch("Ranarr", "diseased", NOW + 5 * MIN),
			patch("", "empty", NOW + 10 * MIN), patch("Guam", "growing", NOW + 30 * MIN)), NOW));
		assertEquals("1 ready", Routines.summary(Arrays.asList(patch("Ranarr", "dead", NOW - MIN), patch("Guam", "ready", 0)), NOW));
	}

	@Test
	public void soonestAndNextSkipPatchesThatArentGrowing()
	{
		Routines.Patch diseased = patch("Ranarr", "diseased", NOW + 5 * MIN);
		Routines.Patch empty = patch("", "empty", NOW + 10 * MIN);
		Routines.Patch growing = patch("Guam", "growing", NOW + 30 * MIN);
		Routines.Patch untimed = patch("Guam", "growing", 0);
		assertEquals(0, FarmRows.soonest(patch("Guam", "ready", 0), NOW));
		assertEquals(NOW + 30 * MIN, FarmRows.soonest(growing, NOW));
		assertEquals(Long.MAX_VALUE, FarmRows.soonest(diseased, NOW));
		assertEquals(Long.MAX_VALUE, FarmRows.soonest(empty, NOW));
		// A growing crop with no timer still sorts before a region with nothing growing.
		assertTrue(FarmRows.soonest(untimed, NOW) < FarmRows.soonest(empty, NOW));
		assertEquals(NOW + 30 * MIN, FarmRows.soonest(Arrays.asList(diseased, empty, growing), NOW));
		assertEquals(growing, FarmRows.next(Arrays.asList(diseased, empty, growing), NOW));
		assertEquals(null, FarmRows.next(Arrays.asList(diseased, empty), NOW));
	}

	@Test
	public void readyShareCountsOnlyGrowingAndReady()
	{
		List<Routines.Patch> ps = Arrays.asList(patch("Guam", "ready", 0), patch("Ranarr", "growing", NOW + MIN),
			patch("", "empty", 0), patch("", "unknown", 0), patch("Torstol", "diseased", 0));
		assertEquals(0.5, FarmRows.readyShare(ps, NOW), 0.0001);
		assertTrue(FarmRows.anyGrowing(ps));
		assertFalse(FarmRows.anyGrowing(Arrays.asList(patch("", "empty", 0), patch("Torstol", "dead", 0))));
		assertEquals(0, FarmRows.readyShare(Arrays.asList(patch("", "empty", 0)), NOW), 0.0001);
	}

	@Test
	public void excludedTeleportsAreParsedAndMatchedInAnyCase()
	{
		List<String> skip = FarmRows.excluded(" Book of the dead , ,Ectophial\n");
		assertEquals(Arrays.asList("book of the dead", "ectophial"), skip);
		assertTrue(FarmRows.excluded("Book of the Dead (Kourend)", skip));
		assertFalse(FarmRows.excluded("Camelot Teleport", skip));
		assertFalse(FarmRows.excluded("Camelot Teleport", Collections.emptyList()));
		// However it is written: capitals, extra spaces, apostrophes.
		assertTrue(FarmRows.excluded("Xeric’s talisman", FarmRows.excluded("XERICS   Talisman")));
		assertTrue(FarmRows.excluded("Book of the dead", FarmRows.excluded("book-of-the-DEAD")));
	}

	@Test
	public void aStopUsesItsBestTeleportNotExcluded()
	{
		Routines.Stop s = new Routines.Stop(3, "Hosidius");
		s.teles.add(new Routines.Tele("Book of the dead", "Rub it", "ITEM", "Book of the dead"));
		s.teles.add(new Routines.Tele("Xeric's talisman", "Rub it", "ITEM", "Xeric's talisman"));
		assertEquals("Book of the dead", FarmRows.pick(s, Collections.emptyList()).travel);
		assertEquals("Xeric's talisman", FarmRows.pick(s, FarmRows.excluded("book of the dead")).travel);
		assertEquals(null, FarmRows.pick(s, FarmRows.excluded("book of the dead, xeric")));
	}

	@Test
	public void excludedTeleportsGiveWayToTheStopsNextBest()
	{
		Routines.Stop s = new Routines.Stop(1, "Catherby");
		s.teles.add(new Routines.Tele("Camelot Teleport", "Cast it", "SPELLBOOK", ""));
		s.teles.add(new Routines.Tele("Catherby tablet", "Break it", "ITEM", "Catherby teleport"));
		Routines.Patch p = patch("Ranarr", "growing", NOW + MIN);
		p.stop = 1;
		p.travel = "Camelot Teleport";
		Routines.Patch other = patch("Guam", "growing", NOW + MIN);
		other.travel = "Camelot Teleport";
		List<Routines.Patch> out = FarmRows.skipTeleports(Arrays.asList(p, other), Collections.singletonList(s), FarmRows.excluded("camelot"));
		assertEquals("Catherby tablet", out.get(0).travel);
		assertEquals("ITEM", out.get(0).travelCat);
		assertEquals("Camelot Teleport", p.travel);
		// No stop to fall back on: it keeps what it had.
		assertEquals("Camelot Teleport", out.get(1).travel);
		assertTrue(FarmRows.skipTeleports(Arrays.asList(p), Collections.singletonList(s), Collections.emptyList()).get(0) == p); // nothing excluded: as given
	}
}
