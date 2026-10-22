package com.nibtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OverlayBoxesTest
{
	private static GoalOverlay.Entry entry(String box, String title, boolean ready)
	{
		return new GoalOverlay.Entry(box, null, title, "1", "", -1, ready, null);
	}

	@Test
	public void boxesGroupByBoxInFirstSeenOrder()
	{
		GoalOverlay.Entry e1 = entry("A", "one", false);
		GoalOverlay.Entry e2 = entry("B", "two", false);
		GoalOverlay.Entry e3 = entry("A", "three", false);
		List<Map.Entry<String, List<GoalOverlay.Entry>>> boxes = GoalOverlay.boxes(Arrays.asList(e1, e2, e3));
		assertEquals(2, boxes.size());
		assertEquals("A", boxes.get(0).getKey());
		assertEquals(Arrays.asList(e1, e3), boxes.get(0).getValue());
		assertEquals("B", boxes.get(1).getKey());
		assertEquals(Collections.singletonList(e2), boxes.get(1).getValue());
		assertSame(e2, boxes.get(1).getValue().get(0));
	}

	@Test
	public void anOldGroupPinKeepsItsOwnBoxBesideItsTabsSinglePins()
	{
		GoalOverlay.Entry oldDailies = entry("legacy:dailies", "Daily tasks", false);
		GoalOverlay.Entry oneDaily = entry("Daily tasks", "Herb run", true);
		GoalOverlay.Entry oldFarming = entry("legacy:farming", "Farming", true);
		GoalOverlay.Entry onePatch = entry("Farming", "Ranarr", true);
		GoalOverlay.Entry oldHerbs = entry("legacy:farming:Herbs", "Herbs", true);
		GoalOverlay.Entry herbKind = entry("Herbs", "Herbs", true);
		List<Map.Entry<String, List<GoalOverlay.Entry>>> boxes = GoalOverlay.boxes(
			Arrays.asList(oldDailies, oneDaily, oldFarming, onePatch, oldHerbs, herbKind));
		assertEquals(6, boxes.size());
		for (Map.Entry<String, List<GoalOverlay.Entry>> b : boxes)
		{
			assertEquals(1, b.getValue().size());
			// One entry: no title line, so the legacy key never shows and old pins look as before.
			assertNull(GoalOverlay.heading(b.getKey(), b.getValue()));
		}
	}

	@Test
	public void noEntriesNoBoxes()
	{
		assertTrue(GoalOverlay.boxes(Collections.emptyList()).isEmpty());
	}

	@Test
	public void headingCarriesTheReadyOrDueCount()
	{
		assertEquals("Farming · 2 ready", GoalOverlay.heading("Farming",
			Arrays.asList(entry("Farming", "a", true), entry("Farming", "b", true), entry("Farming", "c", false))));
		assertEquals("Daily tasks · 1 due", GoalOverlay.heading("Daily tasks",
			Arrays.asList(entry("Daily tasks", "a", true), entry("Daily tasks", "b", false))));
		assertEquals("Skills", GoalOverlay.heading("Skills",
			Arrays.asList(entry("Skills", "a", false), entry("Skills", "b", false))));
	}

	@Test
	public void aSinglePinIsAOneLineBoxButAFavouritesListKeepsItsTitle()
	{
		assertNull(GoalOverlay.heading("Farming", Collections.singletonList(entry("Farming", "Ranarr", true))));
		assertEquals("★ Farming favourites · 1 ready", GoalOverlay.heading("★ Farming favourites",
			Collections.singletonList(entry("★ Farming favourites", "Ranarr", true))));
	}

	private static Routines.Patch patch(String region, String name)
	{
		return new Routines.Patch(region, name, "herb", "Ranarr", 0, 0, true);
	}

	@Test
	public void regionPinShowsOnlyThatRegion()
	{
		List<Routines.Patch> farm = Arrays.asList(patch("Farming Guild", "Herb"), patch("Catherby", "Herb"), patch("Farming Guild", "Tree"));
		Map<String, List<Routines.Patch>> shown = GoalTracker.pinnedRegions(
			Collections.singleton(Favourites.regionPin("Farming Guild")), farm);
		assertEquals(1, shown.size());
		assertEquals(Arrays.asList(farm.get(0), farm.get(2)), shown.get("Farming Guild"));
		assertTrue(GoalTracker.pinnedRegions(Collections.singleton(Favourites.regionPin("Falador")), farm).isEmpty());
	}
}
