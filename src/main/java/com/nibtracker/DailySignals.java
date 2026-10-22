package com.nibtracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 *   ending: TOG_MINIGAME_COLLECTING (the cache's tog_minigame_collecting) going from set back to 0, seen live.
 * - Kingdom of Miscellania: approval is MISC_APPROVAL out of 127 and the coffer MISC_COFFERS; unlocked once the
 *   MISC_QUEST varp is above 0 (RuneLite's kingdomofmiscellania/KingdomPlugin.java).
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
	private static final int MAX_APPROVAL = 127;
	static final int APPROVAL_THRESHOLD = 90;

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

	/** True when a Tears of Guthix game has just ended: collecting went from set back to 0. */
	static boolean tearsPlayed(int before, int after)
	{
		return before != 0 && after == 0;
	}

	/**
	 * Miscellania: due while approval is under 90%, not due once topped up. Null when it isn't unlocked, or nothing has
	 * been read (approval and coffer both 0, which RuneLite's Kingdom plugin also skips).
	 */
	static Boolean miscellaniaDue(IntUnaryOperator varbit, IntUnaryOperator varp)
	{
		if (varp.applyAsInt(VarPlayerID.MISC_QUEST) <= 0)
		{
			return null;
		}
		int approval = varbit.applyAsInt(VarbitID.MISC_APPROVAL);
		if (approval == 0 && varbit.applyAsInt(VarbitID.MISC_COFFERS) == 0)
		{
			return null;
		}
		return approval * 100 / MAX_APPROVAL < APPROVAL_THRESHOLD;
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
