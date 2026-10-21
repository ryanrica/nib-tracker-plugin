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
}
