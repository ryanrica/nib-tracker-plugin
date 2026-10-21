package com.nibtracker;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FavouritesTest
{
	private static Set<String> set(String... ids)
	{
		return new LinkedHashSet<>(Arrays.asList(ids));
	}

	@Test
	public void roundTrips()
	{
		Map<String, Set<String>> m = new LinkedHashMap<>();
		m.put("farming", set("farm:patch:Ardougne|North|herb", "daily:a,b", "fav:x"));
		m.put("goals", set("g1"));
		assertEquals(m, Favourites.parse(Favourites.write(m)));
	}

	@Test
	public void oldPinsStillParse()
	{
		assertTrue(Favourites.isLegacyKindPin("farming:Herbs"));
		assertFalse(Favourites.isLegacyKindPin("farming:kind:herb"));
		assertFalse(Favourites.isLegacyKindPin("farming:contract"));
		assertFalse(Favourites.isLegacyKindPin("dailies"));
		assertFalse(Favourites.isLegacyKindPin("farming"));
		assertTrue(Favourites.parse(null).isEmpty());
		assertTrue(Favourites.parse("").isEmpty());
	}

	@Test
	public void pinIds()
	{
		assertEquals("farm:patch:a|b|c", Favourites.patchPin("a|b|c"));
		assertEquals("daily:x", Favourites.dailyPin("x"));
		assertEquals("fav:t", Favourites.groupPin("t"));
		assertEquals("farming:kind:herb", Favourites.kindPin("herb"));
	}

	@Test
	public void toggleAddsThenRemoves()
	{
		Map<String, Set<String>> m = new LinkedHashMap<>();
		Favourites.toggle(m, "farming", "a");
		assertEquals(set("a"), m.get("farming"));
		Favourites.toggle(m, "farming", "a");
		assertTrue(m.getOrDefault("farming", Collections.emptySet()).isEmpty());
	}

	@Test
	public void setAllReplacesOnlyThatTab()
	{
		Map<String, Set<String>> m = new LinkedHashMap<>();
		m.put("farming", set("a", "b"));
		m.put("goals", set("g"));
		Favourites.setAll(m, "farming", Arrays.asList("c"));
		assertEquals(set("c"), m.get("farming"));
		assertEquals(set("g"), m.get("goals"));
	}

	@Test
	public void hiddenRegionKeepsFavourites()
	{
		Routines.Patch p = new Routines.Patch("Ardougne", "North", "herb", "Ranarr", 0, 0, false);
		assertFalse(FarmGroups.shownInAll(p, new HashSet<>(Arrays.asList("Ardougne"))));
		assertTrue(FarmGroups.shownInAll(p, Collections.emptySet()));
	}
}
