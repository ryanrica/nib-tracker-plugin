package com.nibtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

public class DailySignalsTest
{
	private static IntUnaryOperator vars(Map<Integer, Integer> m)
	{
		return id -> m.getOrDefault(id, 0);
	}

	private static final IntUnaryOperator NONE = id -> 0;

	private static Routines.Daily daily(String id, String key)
	{
		Routines.Daily d = new Routines.Daily(id, id, "daily", 1, 0);
		d.key = key;
		return d;
	}

	@Test
	public void zaffClaimedIsDone()
	{
		Map<Integer, Integer> vb = new HashMap<>();
		vb.put(VarbitID.ZAFF_LAST_CLAIMED, 1);
		Set<String> c = DailySignals.claimedToday(vars(vb), NONE);
		assertTrue(c.contains("zaff_staves"));
		assertFalse(c.contains("bert_sand"));
	}

	@Test
	public void slimeNeedsTheDiaryCap()
	{
		Map<Integer, Integer> vb = new HashMap<>();
		vb.put(VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, 1);
		vb.put(VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, 1);
		vb.put(VarbitID.MORYTANIA_SLIME_CLAIMED, 13);
		assertFalse(DailySignals.claimedToday(vars(vb), NONE).contains("robin_slime"));
		vb.put(VarbitID.MORYTANIA_SLIME_CLAIMED, 26);
		assertTrue(DailySignals.claimedToday(vars(vb), NONE).contains("robin_slime"));
		// No diary, nothing to claim: never done.
		assertFalse(DailySignals.claimedToday(NONE, NONE).contains("robin_slime"));
	}

	@Test
	public void herbBoxesAt15()
	{
		Map<Integer, Integer> vb = new HashMap<>();
		vb.put(VarbitID.NZONE_HERBBOXES_PURCHASED, 14);
		assertFalse(DailySignals.claimedToday(vars(vb), NONE).contains("nmz_herb_boxes"));
		vb.put(VarbitID.NZONE_HERBBOXES_PURCHASED, 15);
		assertTrue(DailySignals.claimedToday(vars(vb), NONE).contains("nmz_herb_boxes"));
	}

	@Test
	public void explorersRingAt30Alchs()
	{
		Map<Integer, Integer> vb = new HashMap<>();
		vb.put(VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, 1);
		vb.put(VarbitID.LUMBRIDGE_FREE_ALCHS, 29);
		assertFalse(DailySignals.claimedToday(vars(vb), NONE).contains("explorers_ring"));
		vb.put(VarbitID.LUMBRIDGE_FREE_ALCHS, 30);
		assertTrue(DailySignals.claimedToday(vars(vb), NONE).contains("explorers_ring"));
		vb.put(VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, 0);
		assertFalse(DailySignals.claimedToday(vars(vb), NONE).contains("explorers_ring"));
	}

	@Test
	public void tearsPlayedWhenCollectingEnds()
	{
		assertTrue(DailySignals.tearsPlayed(1, 0));
		assertFalse(DailySignals.tearsPlayed(0, 1));
		assertFalse(DailySignals.tearsPlayed(0, 0));
	}

	@Test
	public void miscellaniaBelow90IsDue()
	{
		Map<Integer, Integer> vb = new HashMap<>(), vp = new HashMap<>();
		vp.put(VarPlayerID.MISC_QUEST, 100);
		vb.put(VarbitID.MISC_APPROVAL, 100);
		assertEquals(Boolean.TRUE, DailySignals.miscellaniaDue(vars(vb), vars(vp)));
		vb.put(VarbitID.MISC_APPROVAL, 120);
		assertEquals(Boolean.FALSE, DailySignals.miscellaniaDue(vars(vb), vars(vp)));
		assertNull(DailySignals.miscellaniaDue(vars(vb), NONE));
		// Approval and coffer both 0: not read yet, so not known.
		vb.put(VarbitID.MISC_APPROVAL, 0);
		assertNull(DailySignals.miscellaniaDue(vars(vb), vars(vp)));
	}

	@Test
	public void loginWithClaimedFlagIsQuietAndOnce()
	{
		List<Routines.Daily> dailies = Arrays.asList(daily("z", "zaff_staves"), daily("b", "bert_sand"));
		Set<String> claimed = Collections.singleton("zaff_staves");
		Set<String> sent = new HashSet<>();
		List<Routines.Daily> first = DailySignals.newlyDone(claimed, sent, dailies);
		assertEquals(1, first.size());
		assertEquals("z", first.get(0).id);
		assertTrue(DailySignals.newlyDone(claimed, sent, dailies).isEmpty());
		sent.clear(); // the daily reset
		assertEquals(1, DailySignals.newlyDone(claimed, sent, dailies).size());
	}

	@Test
	public void taskWithoutKeyIsNeverAutoDone()
	{
		List<Routines.Daily> dailies = Collections.singletonList(daily("x", ""));
		Set<String> claimed = new HashSet<>(Collections.singletonList(""));
		assertTrue(DailySignals.newlyDone(claimed, new HashSet<>(), dailies).isEmpty());
	}

	@Test
	public void iconFromFeedThenTableThenCoins()
	{
		Routines.Daily d = daily("z", "zaff_staves");
		assertEquals(ItemID.BATTLESTAFF, DailySignals.icon(d));
		d.itemId = ItemID.BOW_STRING;
		assertEquals(ItemID.BOW_STRING, DailySignals.icon(d));
		assertEquals(ItemID.COINS, DailySignals.icon(daily("x", "")));
		assertEquals(ItemID.POH_TREASURE_TEAK, (int) DailySignals.ICONS.get("miscellania"));
	}
}
