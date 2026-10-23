package com.nibtracker;

import java.util.ArrayList;
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

	@Test
	public void regionPinIsDistinct()
	{
		assertEquals("farm:region:Farming Guild", Favourites.regionPin("Farming Guild"));
		assertFalse(Favourites.isLegacyKindPin(Favourites.regionPin("Farming Guild")));
	}

	@Test
	public void onlyKeepsFavouritesInOrder()
	{
		Set<String> favs = new HashSet<>(Arrays.asList("b", "d"));
		assertEquals(Arrays.asList("b", "d"), Favourites.only(Arrays.asList("a", "b", "c", "d"), s -> s, favs));
		assertTrue(Favourites.only(Arrays.asList("a", "c"), s -> s, favs).isEmpty());
		assertTrue(Favourites.only(Arrays.asList("a"), s -> s, Collections.emptySet()).isEmpty());
	}

	@Test
	public void oldTreeKindPinsBecomeAllTrees()
	{
		Set<String> pins = new LinkedHashSet<>(Arrays.asList("g1", "farming:Trees", "farming:kind:herb", "farming:kind:hardwood",
			"farming:Redwood", "farming:Herbs", "farming:kind:redwood", "farming:Hardwood", "farming:kind:tree", "farming:special"));
		Set<String> out = Favourites.migrateTreePins(pins);
		assertEquals(new LinkedHashSet<>(Arrays.asList("g1", Favourites.TREES_PIN, "farming:kind:herb", "farming:Herbs", "farming:special")), out);
		assertEquals(Arrays.asList("g1", Favourites.TREES_PIN, "farming:kind:herb", "farming:Herbs", "farming:special"), new ArrayList<>(out));
		// Again changes nothing; an All trees pin already there isn't doubled.
		assertEquals(out, Favourites.migrateTreePins(out));
		Set<String> both = Favourites.migrateTreePins(new LinkedHashSet<>(Arrays.asList(Favourites.TREES_PIN, "farming:kind:tree")));
		assertEquals(Collections.singleton(Favourites.TREES_PIN), both);
		Set<String> none = new LinkedHashSet<>(Arrays.asList("dailies", "farming:contract", "farm:patch:x"));
		assertEquals(none, Favourites.migrateTreePins(none));
	}
}
