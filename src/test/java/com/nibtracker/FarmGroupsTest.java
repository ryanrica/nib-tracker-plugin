package com.nibtracker;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FarmGroupsTest
{
	private static final long NOW = 1_000_000_000L;
	private static final long MIN = 60_000L;

	private static Routines.Patch guild(String kind, String crop, String state, long readyAt)
	{
		Routines.Patch p = new Routines.Patch("Farming Guild", "Guild " + kind, kind, crop, 0, readyAt, "ready".equals(state));
		p.kind = kind;
		p.state = state;
		return p;
	}

	private static FarmGroups.Contract ranarr()
	{
		return FarmGroups.contract(ItemID.RANARR_WEED);
	}

	private static FarmGroups.ContractView view(FarmGroups.Contract c, Routines.Patch... patches)
	{
		List<Routines.Patch> farm = Arrays.asList(patches);
		return FarmGroups.contractView(c, farm, NOW);
	}

	@Test
	public void specialKinds()
	{
		assertTrue(FarmGroups.special("seaweed"));
		assertFalse(FarmGroups.special("herb"));
		assertFalse(FarmGroups.special("celastrus"));
		for (String k : new String[]{"hespori", "anima", "belladonna", "cactus", "seaweed", "mushroom", "calquat"})
		{
			assertTrue(k, FarmGroups.special(k));
		}
	}

	@Test
	public void treeKinds()
	{
		assertTrue(FarmGroups.tree("redwood"));
		assertTrue(FarmGroups.tree("hardwood"));
		assertFalse(FarmGroups.tree("fruit_tree"));
	}

	@Test
	public void contractIdIgnoresCrop()
	{
		assertEquals("farming:contract", FarmGroups.CONTRACT_ID);
	}

	@Test
	public void contractLookup()
	{
		assertEquals("herb", FarmGroups.contract(ItemID.RANARR_WEED).kind);
		assertEquals("Ranarr", FarmGroups.contract(ItemID.RANARR_WEED).crop);
		assertEquals("redwood", FarmGroups.contract(ItemID.REDWOOD_LOGS).kind);
		assertEquals("fruit_tree", FarmGroups.contract(ItemID.COOKING_APPLE).kind);
		assertEquals("cactus", FarmGroups.contract(ItemID.CACTUS_POTATO).kind);
		assertNull(FarmGroups.contract(ItemID.TEAK_LOGS));
	}

	@Test
	public void noContract()
	{
		FarmGroups.ContractView v = FarmGroups.contractView(null, Collections.<Routines.Patch>emptyList(), NOW);
		assertEquals("No contract · talk to Jane", v.value);
		assertFalse(v.ready);
		assertFalse(v.attention);
	}

	@Test
	public void readyPatch()
	{
		FarmGroups.ContractView v = view(ranarr(), guild("herb", "Ranarr", "ready", 0));
		assertTrue(v.ready);
		assertEquals("Ready · then see Jane for a new contract", v.value);
		assertEquals(ItemID.RANARR_WEED, v.itemId);
	}

	@Test
	public void contractOccupiedAndEmpty()
	{
		assertEquals("Other crop planted", view(ranarr(), guild("herb", "Guam", "growing", NOW + MIN)).value);
		FarmGroups.ContractView none = view(ranarr());
		assertEquals("Not planted", none.value);
		assertEquals("Harvested? See Jane for a new contract", none.sub);
		assertEquals("Not planted", view(ranarr(), guild("herb", "", "empty", 0)).value);
		assertEquals("Not planted", view(ranarr(), guild("tree", "Oak", "growing", 0)).value);
	}

	private static Routines.Patch allotment(String name, String crop, String state, long readyAt)
	{
		Routines.Patch p = new Routines.Patch("Farming Guild", name, "Allotment", crop, 0, readyAt, "ready".equals(state));
		p.kind = "allotment";
		p.state = state;
		return p;
	}

	@Test
	public void contractCropInTheSecondAllotment()
	{
		FarmGroups.Contract potato = FarmGroups.contract(ItemID.POTATO);
		Routines.Patch south = allotment("South", "Potatoes", "growing", NOW + 90 * MIN);
		south.progress = 0.4;
		FarmGroups.ContractView v = view(potato, allotment("North", "Onions", "growing", NOW + MIN), south);
		assertEquals("1h 30m", v.value);
		assertEquals(0.4, v.progress, 0.0001);
		assertEquals("1h 30m", view(potato, allotment("North", "", "empty", 0), south).value);
		// The more advanced of two matching crops wins: ready over growing over diseased.
		FarmGroups.ContractView ready = view(potato, allotment("North", "Potatoes", "diseased", 0),
			south, allotment("South", "Potatoes", "ready", 0));
		assertTrue(ready.ready);
		assertEquals("Ready · then see Jane for a new contract", ready.value);
		assertEquals(1, ready.progress, 0.0001);
		assertEquals("1h 30m", view(potato, allotment("North", "Potatoes", "diseased", 0), south).value);
	}

	@Test
	public void contractEmptyAllotmentBeatsOtherCrop()
	{
		FarmGroups.Contract potato = FarmGroups.contract(ItemID.POTATO);
		FarmGroups.ContractView v = view(potato, allotment("North", "Onions", "growing", NOW + MIN), allotment("South", "", "empty", 0));
		assertEquals("Not planted", v.value);
		assertEquals("Harvested? See Jane for a new contract", v.sub);
		assertEquals("Other crop planted", view(potato, allotment("North", "Onions", "growing", NOW + MIN),
			allotment("South", "Cabbages", "growing", NOW + MIN)).value);
	}

	@Test
	public void diseasedNeedsAttention()
	{
		FarmGroups.ContractView v = view(ranarr(), guild("herb", "Ranarr", "diseased", 0));
		assertTrue(v.attention);
		assertEquals("Diseased", v.value);
	}

	@Test
	public void growingShowsTimeLeft()
	{
		Routines.Patch p = guild("herb", "Ranarr", "growing", NOW + 90 * MIN);
		p.progress = 0.4;
		FarmGroups.ContractView v = view(ranarr(), p);
		assertEquals("1h 30m", v.value);
		assertEquals(0.4, v.progress, 0.0001);
		assertFalse(v.ready);
		assertEquals("Growing", view(ranarr(), guild("herb", "Ranarr", "growing", 0)).value);
	}

	@Test
	public void cropMatchIgnoresCaseAndPlural()
	{
		FarmGroups.Contract potato = FarmGroups.contract(ItemID.POTATO);
		assertNotNull(potato);
		assertEquals("Potatoes", potato.crop);
		assertEquals("Ready · then see Jane for a new contract", view(potato, guild("allotment", "potato", "ready", 0)).value);
	}

	@Test
	public void title()
	{
		assertEquals("Contract: Ranarr · Farming Guild herb patch", view(ranarr()).title);
		assertEquals("Contract: Apple · Farming Guild fruit tree patch", view(FarmGroups.contract(ItemID.COOKING_APPLE)).title);
	}

	private static GoalsPanel.PatchView view(String kind, String kindLabel)
	{
		GoalsPanel.PatchView v = new GoalsPanel.PatchView("Catherby", "Crop", "", "", "", "", false, 0);
		v.kind = kind;
		v.kindLabel = kindLabel;
		return v;
	}

	@Test
	public void kindChipsLeaveOutTreesAndGroups()
	{
		List<GoalsPanel.PatchView> ps = Arrays.asList(view("herb", "Herbs"), view("tree", "Trees"), view("hardwood", "Hardwood"),
			view("redwood", "Redwood"), view("fruit_tree", "Fruit trees"), view("herb", "Herbs"), view("hespori", "Hespori"),
			view("x", ""));
		assertEquals(Arrays.asList("Fruit trees", "Herbs", "Hespori"), GoalsPanel.kindChips(ps));
	}
}
