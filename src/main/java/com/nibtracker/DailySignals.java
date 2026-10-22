package com.nibtracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Which daily tasks the game says are done, and each task's icon. Kept free of client calls so it can be unit tested:
 * callers pass client::getVarbitValue and client::getVarpValue.
 *
 * The signals. The claim flags are the ones RuneLite's Daily Task Indicators plugin reads (dailytaskindicators/
 * DailyTasksPlugin.java): set when claimed, back to 0 at the daily reset. The rest:
 * - Explorer's ring: LUMBRIDGE_FREE_ALCHS counts today's free alchs used, out of 30 for every ring tier (RuneLite's
 *   ItemChargePlugin, MAX_EXPLORER_RING_CHARGES; the game's own lumbridge_alchemy_vartransmit script shows
 *   "30 - lumbridge_free_alchs"). The ring needs the easy Lumbridge & Draynor diary.
 * - Tears of Guthix: no var says "played this week" (the 7-day lock is kept by the server). TOG_QP_BEFORE_RETURN can't
 *   be used either: it's rewritten to the same value when you play again without a new quest point. So it's the game
 *   ending: TOG_MINIGAME_COLLECTING (the cache's tog_minigame_collecting) going from set back to 0, seen live. Its
 *   meaning comes from its name only, so the edge counts only if TOG_TEARS_COLLECTED went above 0 while collecting:
 *   tears in the bowl prove a real game was played (a countdown alone would not), and a stray reset of the collecting
 *   flag outside a game is ignored. A game with no blue tears at all isn't counted; that's left to a manual tick.
 * - Kingdom of Miscellania: approval is MISC_APPROVAL out of 127 and the coffer MISC_COFFERS; unlocked once the
 *   MISC_QUEST varp is above 0. As in RuneLite's kingdomofmiscellania/KingdomPlugin.java, the varbits don't decay
 *   while you're away, so a reading is trusted only in the kingdom (regions 10044 and 10300) or when approval and
 *   coffer are both above 0; it's saved with its time, and today's approval is estimated from it: 2.5% of 127 off per
 *   whole UTC day (1% after Royal Trouble).
 */
final class DailySignals
{
	private DailySignals()
	{
	}

	/* Robin gives 13 buckets per Morytania diary tier from medium up; NMZ sells 15 herb boxes a day; 30 free alchs. */
	private static final int SLIME_PER_DIARY = 13;
	private static final int HERB_BOXES = 15;
	private static final int FREE_ALCHS = 30;
	/* Miscellania approval runs 0 to 127; under 90% it needs a top-up. */
	static final int MAX_APPROVAL = 127;
	static final int APPROVAL_THRESHOLD = 90;
	private static final double DECAY = 0.025;
	private static final double DECAY_ROYAL_TROUBLE = 0.010;
	/* Miscellania and Etceteria, where the approval varbits are current (RuneLite's KINGDOM_REGION). */
	static final Set<Integer> KINGDOM_REGIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(10044, 10300)));

	static final String TEARS = "tears_of_guthix";
	static final String MISCELLANIA = "miscellania";

	/* Each built-in task's icon, for when the feed has no itemId. */
	static final Map<String, Integer> ICONS;

	static
	{
		Map<String, Integer> m = new HashMap<>();
		m.put(TEARS, ItemID.THOSF_REWARD_LAMP);
		m.put(MISCELLANIA, ItemID.POH_TREASURE_TEAK);
		m.put("zaff_staves", ItemID.BATTLESTAFF);
		m.put("bert_sand", ItemID.BUCKET_SAND);
		m.put("cromperty_essence", ItemID.BLANKRUNE_HIGH);
		m.put("seers_flax", ItemID.BOW_STRING);
		m.put("lundail_runes", ItemID.MINDRUNE);
		m.put("rantz_arrows", ItemID.OGRE_ARROW);
		m.put("nmz_herb_boxes", ItemID.NZONE_HERBBOX);
		m.put("thirus_dynamite", ItemID.LOVAKENGJ_DYNAMITE_FUSED);
		m.put("explorers_ring", ItemID.LUMBRIDGE_RING_ELITE);
		m.put("robin_slime", ItemID.BUCKET_ECTOPLASM);
		ICONS = Collections.unmodifiableMap(m);
	}

	/* The claim flags, done when set: task key -> varbit. */
	private static final Map<String, Integer> FLAGS;

	static
	{
		Map<String, Integer> m = new HashMap<>();
		m.put("zaff_staves", VarbitID.ZAFF_LAST_CLAIMED);
		m.put("cromperty_essence", VarbitID.ARDOUGNE_FREE_ESSENCE);
		m.put("lundail_runes", VarbitID.LUNDAIL_LAST_CLAIMED);
		m.put("bert_sand", VarbitID.YANILLE_SAND_CLAIMED);
		m.put("seers_flax", VarbitID.SEERS_FREE_FLAX);
		m.put("rantz_arrows", VarbitID.WESTERN_RANTZ_ARROWS);
		m.put("thirus_dynamite", VarbitID.KOUREND_FREE_DYNAMITE);
		FLAGS = Collections.unmodifiableMap(m);
	}

	/* Every varbit and varp that can change a task, so VarbitChanged can skip the rest. */
	static final Set<Integer> VARBITS;
	static final Set<Integer> VARPS = Collections.singleton(VarPlayerID.MISC_QUEST);

	static
	{
		Set<Integer> s = new LinkedHashSet<>(FLAGS.values());
		Collections.addAll(s, VarbitID.MORYTANIA_SLIME_CLAIMED, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE, VarbitID.NZONE_HERBBOXES_PURCHASED,
			VarbitID.LUMBRIDGE_FREE_ALCHS, VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.TOG_MINIGAME_COLLECTING,
			VarbitID.MISC_APPROVAL, VarbitID.MISC_COFFERS);
		VARBITS = Collections.unmodifiableSet(s);
	}

	/** A task's icon item: the feed's, else the table's, else coins. */
	static int icon(Routines.Daily d)
	{
		if (d.itemId > 0)
		{
			return d.itemId;
		}
		return ICONS.getOrDefault(d.key, ItemID.COINS);
	}

	/** The keys of the tasks whose claim flag says they're done today. */
	static Set<String> claimedToday(IntUnaryOperator varbit, IntUnaryOperator varp)
	{
		Set<String> out = new LinkedHashSet<>();
		for (Map.Entry<String, Integer> e : FLAGS.entrySet())
		{
			if (varbit.applyAsInt(e.getValue()) != 0)
			{
				out.add(e.getKey());
			}
		}
		int slimeCap = SLIME_PER_DIARY * (varbit.applyAsInt(VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE)
			+ varbit.applyAsInt(VarbitID.MORYTANIA_DIARY_HARD_COMPLETE) + varbit.applyAsInt(VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE));
		if (slimeCap > 0 && varbit.applyAsInt(VarbitID.MORYTANIA_SLIME_CLAIMED) >= slimeCap)
		{
			out.add("robin_slime");
		}
		if (varbit.applyAsInt(VarbitID.NZONE_HERBBOXES_PURCHASED) >= HERB_BOXES)
		{
			out.add("nmz_herb_boxes");
		}
		if (varbit.applyAsInt(VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE) != 0 && varbit.applyAsInt(VarbitID.LUMBRIDGE_FREE_ALCHS) >= FREE_ALCHS)
		{
			out.add("explorers_ring");
		}
		return out;
	}

	/**
	 * True when a Tears of Guthix game has just ended: collecting went from set back to 0, and tears were collected
	 * during it (sawTears).
	 */
	static boolean tearsPlayed(int before, int after, boolean sawTears)
	{
		return before != 0 && after == 0 && sawTears;
	}

	/** Whether a Miscellania reading is current: in the kingdom, or approval and coffer both above 0 (as RuneLite). */
	static boolean trusted(boolean inKingdom, int approval, int coffer)
	{
		return inKingdom || approval > 0 && coffer > 0;
	}

	/**
	 * Today's approval in percent, estimated from one read at lastChangedMs: it drops 2.5% of the maximum per whole UTC
	 * day since (1% after Royal Trouble), never below 0.
	 */
	static double estimatedApproval(int approval, long lastChangedMs, long nowMs, boolean royalTrouble)
	{
		long days = Math.max(0, nowMs / Routines.DAY - lastChangedMs / Routines.DAY);
		double left = approval - days * (royalTrouble ? DECAY_ROYAL_TROUBLE : DECAY) * MAX_APPROVAL;
		return Math.max(0, left) * 100 / MAX_APPROVAL;
	}

	/**
	 * Miscellania: due while today's estimated approval is under 90%, not due once topped up. Null when it isn't
	 * unlocked, or no trusted reading has been saved yet.
	 */
	static Boolean miscellaniaDue(boolean unlocked, Integer approval, long lastChangedMs, long nowMs, boolean royalTrouble)
	{
		if (!unlocked || approval == null)
		{
			return null;
		}
		return estimatedApproval(approval, lastChangedMs, nowMs, royalTrouble) < APPROVAL_THRESHOLD;
	}

	/**
	 * The tasks with a key in `claimed` not yet in `sent`; their keys are added to `sent`. The caller takes a key back
	 * out of `sent` when its flag goes back to 0 (the daily reset).
	 */
	static List<Routines.Daily> newlyDone(Set<String> claimed, Set<String> sent, List<Routines.Daily> dailies)
	{
		List<Routines.Daily> out = new ArrayList<>();
		for (Routines.Daily d : dailies)
		{
			if (!d.key.isEmpty() && claimed.contains(d.key) && sent.add(d.key))
			{
				out.add(d);
			}
		}
		return out;
	}
}
