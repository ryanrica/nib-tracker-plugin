package com.nibtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PatchPredictorTest
{
	private static final PatchPredictor TT = PatchPredictor.load(new Gson());
	/* A farming tick boundary (20 minutes), in unix seconds. */
	private static final long T = 1_441_333L * 1200;
	private static final long HOUR = 60 * 60 * 1000L;

	private static List<Routines.Patch> predict(String key, String value, long nowMs)
	{
		Map<String, String> e = new HashMap<>();
		e.put(key, value);
		return TT.predict(e, nowMs);
	}

	@Test
	public void herbGrowingThenReady()
	{
		// HERB 32..35 is Ranarr growing (stage = value - 32); Ranarr grows every 20 minutes over 5 stages.
		List<Routines.Patch> at = predict("11062.4774", "32:" + T, T * 1000);
		assertEquals(1, at.size());
		Routines.Patch p = at.get(0);
		assertEquals("Catherby", p.region);
		assertEquals("", p.name);
		assertEquals("Herb", p.type);
		assertEquals("Ranarr", p.crop);
		assertEquals(257, p.itemId);
		assertEquals("growing", p.state);
		assertFalse(p.ready);
		assertTrue(p.readyAt > T * 1000);
		assertEquals((T + 80 * 60) * 1000, p.readyAt);
		assertEquals(0, p.progress, 0);
		assertEquals("herb", p.kind);
		assertEquals("Herbs", p.kindLabel);
		assertEquals("herb", p.run);
		assertEquals("Catherby||herb", p.id());

		Routines.Patch later = predict("11062.4774", "32:" + T, T * 1000 + 2 * HOUR).get(0);
		assertEquals("ready", later.state);
		assertTrue(later.ready);
		assertEquals(1, later.progress, 0);
	}

	@Test
	public void farmingGuildSpiritTreeIsLeftOut()
	{
		assertTrue(predict("4922.4771", "20:" + T, T * 1000).isEmpty());
	}

	@Test
	public void weedsAreEmpty()
	{
		Routines.Patch p = predict("11062.4774", "3:" + T, T * 1000).get(0);
		assertEquals("empty", p.state);
		assertEquals("Weeds", p.crop);
		assertEquals(0, p.itemId);
		assertFalse(p.ready);
	}

	@Test
	public void idMatchesFeed()
	{
		Routines.Patch p = predict("4922.7910", "8:" + T, T * 1000).get(0);
		assertEquals("Farming Guild||celastrus", p.id());
		assertEquals("Celastrus", p.type);
		assertEquals("Celastrus", p.kindLabel);
	}

	@Test
	public void unknownPatchOrValueIsSkipped()
	{
		Map<String, String> e = new HashMap<>();
		e.put("99999.1", "32:" + T);
		e.put("11062.4774", "220:" + T); // no HERB range has 220
		e.put("11062.4773", "junk");
		e.put("farmTickOffset", "x");
		assertTrue(TT.predict(e, T * 1000).isEmpty());
	}

	@Test
	public void seededBirdHouseIsReadyAfterFiftyMinutes()
	{
		// 6 is an oak bird house (values 4..6), seeded (a multiple of 3).
		Routines.Patch p = predict("birdhouse.1626", "6:" + T, T * 1000).get(0);
		assertEquals("Fossil Island|Mushroom Meadow (North)|birdhouse", p.id());
		assertEquals("Oak Bird House", p.crop);
		assertEquals("growing", p.state);
		assertEquals((T + 50 * 60) * 1000, p.readyAt);
		assertEquals("ready", predict("birdhouse.1626", "6:" + T, T * 1000 + HOUR).get(0).state);
		// Built but not seeded counts as empty, and the feed leaves those out.
		assertTrue(predict("birdhouse.1626", "5:" + T, T * 1000).isEmpty());
	}

	@Test
	public void tickOffsetMovesTheReadyTime()
	{
		Map<String, String> e = new HashMap<>();
		e.put("11062.4774", "32:" + T);
		e.put("farmTickOffset", "5");
		e.put("farmTickOffsetPrecision", "40");
		// With a 5 minute offset the ticks fall 5 minutes earlier: T - 5 min is the boundary, ready 4 ticks later.
		assertEquals((T - 5 * 60 + 80 * 60) * 1000, TT.predict(e, T * 1000).get(0).readyAt);
	}

	private static PatchPredictor.SpiritTree spirit(List<PatchPredictor.SpiritTree> trees, String region)
	{
		for (PatchPredictor.SpiritTree t : trees)
		{
			if (region.equals(t.region))
			{
				return t;
			}
		}
		throw new AssertionError("no spirit tree spot " + region);
	}

	@Test
	public void spiritTreesEveryStateAndSpot()
	{
		Map<String, String> e = new HashMap<>();
		e.put("12082.4771", "3:" + T); // Port Sarim: weeds
		e.put("11058.4772", "20:" + T); // Brimhaven: grown
		e.put("10300.4772", "10:" + T); // Etceteria: growing, stage 2 of 12
		e.put("6967.7904", "25:" + T); // Kourend (Hosidius): diseased
		List<PatchPredictor.SpiritTree> trees = TT.spiritTrees(e, T * 1000);
		assertEquals(5, trees.size());
		assertEquals("Not planted", spirit(trees, "Port Sarim").state);
		assertEquals("Grown", spirit(trees, "Brimhaven").state);
		assertEquals(1, spirit(trees, "Brimhaven").progress, 0);
		PatchPredictor.SpiritTree growing = spirit(trees, "Etceteria");
		assertEquals("Growing", growing.state);
		assertEquals(2 / 12.0, growing.progress, 1e-9);
		assertEquals("Diseased", spirit(trees, "Hosidius").state);
		assertEquals("Unknown", spirit(trees, "Farming Guild").state);
		assertEquals(-1, spirit(trees, "Farming Guild").progress, 0);

		// Spirit trees grow every 320 minutes; ten more ticks and it's grown.
		long later = (T + 10 * 320 * 60) * 1000;
		assertEquals("Grown", spirit(TT.spiritTrees(e, later), "Etceteria").state);
		assertEquals("Growing", spirit(TT.spiritTrees(e, later - 320 * 60 * 1000L), "Etceteria").state);
	}

	@Test
	public void spiritTreeDeadAndLateGrownStage()
	{
		Map<String, String> e = new HashMap<>();
		e.put("4922.4771", "35:" + T);
		e.put("11058.4772", "44:" + T);
		List<PatchPredictor.SpiritTree> trees = TT.spiritTrees(e, T * 1000);
		assertEquals("Dead", spirit(trees, "Farming Guild").state);
		assertEquals("Grown", spirit(trees, "Brimhaven").state);
	}
}
