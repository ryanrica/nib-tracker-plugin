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
	public void tearsPlayedWhenCollectingEndsAfterTears()
	{
		assertTrue(DailySignals.tearsPlayed(1, 0, true));
		// Collecting ending with no tears seen this game: not counted.
		assertFalse(DailySignals.tearsPlayed(1, 0, false));
		assertFalse(DailySignals.tearsPlayed(0, 1, true));
		assertFalse(DailySignals.tearsPlayed(0, 0, true));
	}

	private static final long NOW = 20_000 * Routines.DAY + 12 * 60 * Routines.MINUTE;

	@Test
	public void approvalDecaysByTheDay()
	{
		assertEquals(100, DailySignals.estimatedApproval(127, NOW, NOW, false), 0.001);
		assertEquals(87.5, DailySignals.estimatedApproval(127, NOW - 5 * Routines.DAY, NOW, false), 0.001);
		assertEquals(95, DailySignals.estimatedApproval(127, NOW - 5 * Routines.DAY, NOW, true), 0.001);
		assertEquals(0, DailySignals.estimatedApproval(10, NOW - 100 * Routines.DAY, NOW, false), 0.001);
	}

	@Test
	public void miscellaniaBelow90IsDue()
	{
		long fiveDays = NOW - 5 * Routines.DAY;
		assertEquals(Boolean.FALSE, DailySignals.miscellaniaDue(true, 127, NOW, NOW, false));
		assertEquals(Boolean.TRUE, DailySignals.miscellaniaDue(true, 127, fiveDays, NOW, false));
		assertEquals(Boolean.FALSE, DailySignals.miscellaniaDue(true, 127, fiveDays, NOW, true));
		assertEquals(Boolean.TRUE, DailySignals.miscellaniaDue(true, 100, NOW, NOW, false));
		assertNull(DailySignals.miscellaniaDue(false, 127, NOW, NOW, false)); // not unlocked
		assertNull(DailySignals.miscellaniaDue(true, null, 0, NOW, false)); // never read
	}

	@Test
	public void readingTrustedInTheKingdomOrWhenBothSet()
	{
		assertTrue(DailySignals.trusted(true, 0, 0));
		assertTrue(DailySignals.trusted(false, 120, 50_000));
		assertFalse(DailySignals.trusted(false, 120, 0)); // outside, coffer 0: ignored
		assertFalse(DailySignals.trusted(false, 0, 50_000));
	}

	@Test
	public void approvalNewsOnlyWhenTheVarbitMoves()
	{
		long yesterday = NOW - Routines.DAY;
		// Ticked by hand (saved 127) with the varbit still at its old 100: the same 100 again is not news.
		assertFalse(DailySignals.approvalNews(false, 100, 100, 127, NOW, NOW));
		assertFalse(DailySignals.approvalNews(false, 100, 100, 127, yesterday, NOW));
		// Outside, the raw value moved: news.
		assertTrue(DailySignals.approvalNews(false, 110, 100, 127, NOW, NOW));
		// Nothing seen yet (first run): only remembered as seen.
		assertFalse(DailySignals.approvalNews(false, 100, null, 127, NOW, NOW));
		// Nothing saved: any trusted reading counts.
		assertTrue(DailySignals.approvalNews(false, 100, null, null, 0, NOW));
		// In the kingdom: a different value, or the same one saved on an earlier day, is today's reading.
		assertTrue(DailySignals.approvalNews(true, 100, 100, 127, NOW, NOW));
		assertFalse(DailySignals.approvalNews(true, 127, 100, 127, NOW, NOW));
		assertTrue(DailySignals.approvalNews(true, 127, 127, 127, yesterday, NOW));
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

	private static Routines.Daily labelled(String label)
	{
		return new Routines.Daily(label, label, "daily", 1, 0);
	}

	@Test
	public void exactLabelWithoutKeyGetsTheKey()
	{
		assertEquals("bert_sand", DailySignals.keyFor(labelled("Bert's buckets of sand")));
		assertEquals("miscellania", DailySignals.keyFor(labelled("kingdom of miscellania (keep approval up)")));
		assertEquals("robin_slime", DailySignals.keyFor(labelled("Robin's buckets of slime (Port Phasmatys)")));
		assertEquals("tears_of_guthix", DailySignals.keyFor(labelled(" TEARS OF GUTHIX ")));
		Routines.Daily keyed = labelled("Whatever");
		keyed.key = "zaff_staves";
		assertEquals("zaff_staves", DailySignals.keyFor(keyed));
		assertEquals("", DailySignals.keyFor(labelled("Sand mining")));
	}

	@Test
	public void exactLabelWithoutKeyGetsIconAndIsAutoDone()
	{
		Routines.Daily d = labelled("Bert's buckets of sand");
		assertEquals(ItemID.BUCKET_SAND, DailySignals.icon(d));
		List<Routines.Daily> done = DailySignals.newlyDone(Collections.singleton("bert_sand"), new HashSet<>(),
			Collections.singletonList(d));
		assertEquals(1, done.size());
	}

	@Test
	public void customLabelGetsIconButIsNeverAutoDone()
	{
		Routines.Daily d = labelled("Sand mining");
		assertEquals(ItemID.BUCKET_SAND, DailySignals.icon(d));
		Set<String> all = new HashSet<>(DailySignals.ICONS.keySet());
		assertTrue(DailySignals.newlyDone(all, new HashSet<>(), Collections.singletonList(d)).isEmpty());
		assertEquals(ItemID.LOVAKENGJ_DYNAMITE_FUSED, DailySignals.icon(labelled("Buy Thirus stuff")));
		assertEquals(ItemID.BATTLESTAFF, DailySignals.icon(labelled("Battlestaff run")));
		assertEquals(ItemID.NZONE_HERBBOX, DailySignals.icon(labelled("Nightmare Zone boxes")));
	}
}
