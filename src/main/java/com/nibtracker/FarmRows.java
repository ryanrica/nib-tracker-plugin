package com.nibtracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** What a farm patch row says, and the Farming tab picker's ticking and saving. Pure, so it can be tested. */
final class FarmRows
{
	private FarmRows()
	{
	}

	/** Ready to harvest: not diseased or dead, not empty or unknown, and its timer is up (or the feed says ready). */
	static boolean ready(Routines.Patch p, long now)
	{
		if (p.needsAttention() || "empty".equals(p.state) || "unknown".equals(p.state))
		{
			return false;
		}
		return p.readyAt(now) || "ready".equals(p.state);
	}

	/** The row's value: Diseased, Dead, Ready, Empty, Unknown, the time left, or Growing when there's no timer. */
	static String status(Routines.Patch p, long now)
	{
		if ("diseased".equals(p.state))
		{
			return "Diseased";
		}
		if ("dead".equals(p.state))
		{
			return "Dead";
		}
		if (ready(p, now))
		{
			return "Ready";
		}
		if ("empty".equals(p.state))
		{
			return "Empty";
		}
		if ("unknown".equals(p.state))
		{
			return "Unknown";
		}
		return p.readyAt > now ? Routines.left(p.readyAt - now) : "Growing";
	}

	/** The row's title: the crop, "Unknown" for an unknown patch, or the kind (Herbs) when nothing is planted. */
	static String title(Routines.Patch p)
	{
		if ("unknown".equals(p.state))
		{
			return "Unknown";
		}
		if (p.crop != null && !p.crop.trim().isEmpty())
		{
			return p.crop;
		}
		return p.kindLabel != null && !p.kindLabel.isEmpty() ? p.kindLabel : "Patch";
	}

	/** The second line: "region · name · travel", leaving out a name that is just the region and an unknown travel. */
	static String sub(String region, String name, String travel)
	{
		StringBuilder s = new StringBuilder(region);
		if (name != null && !name.isEmpty() && !name.equals(region))
		{
			s.append(" · ").append(name);
		}
		if (travel != null && !travel.isEmpty())
		{
			s.append(" · ").append(travel);
		}
		return s.toString();
	}

	/** The same list with the ready rows first, otherwise in the order given. */
	static <T> List<T> readyFirst(List<T> rows, Predicate<T> ready)
	{
		List<T> out = new ArrayList<>();
		for (T t : rows)
		{
			if (ready.test(t))
			{
				out.add(t);
			}
		}
		for (T t : rows)
		{
			if (!ready.test(t))
			{
				out.add(t);
			}
		}
		return out;
	}

	/**
	 * A shift-click in the picker: every row from the last one clicked (anchor) to this one takes this one's new state.
	 * With no anchor (-1) or one out of range, only this row changes.
	 */
	static void tickRange(List<String> ids, int anchor, int index, boolean on, Set<String> ticked)
	{
		int from = anchor >= 0 && anchor < ids.size() ? Math.min(anchor, index) : index;
		int to = anchor >= 0 && anchor < ids.size() ? Math.max(anchor, index) : index;
		for (int i = from; i <= to; i++)
		{
			if (on)
			{
				ticked.add(ids.get(i));
			}
			else
			{
				ticked.remove(ids.get(i));
			}
		}
	}

	/**
	 * The hidden regions after the All patches picker: the offered regions left unticked, plus regions hidden before that
	 * have no timers now (they weren't offered, so they stay as they were).
	 */
	static Set<String> hiddenAfter(Collection<String> offered, Collection<String> hiddenBefore, Set<String> shown)
	{
		Set<String> out = new LinkedHashSet<>();
		for (String r : hiddenBefore)
		{
			if (!offered.contains(r))
			{
				out.add(r);
			}
		}
		for (String r : offered)
		{
			if (!shown.contains(r))
			{
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * The favourites after the Favourites picker, in the order saved: the ones kept stay where they were, new ones go on
	 * the end in the picker's order, and saved ids the picker didn't offer (a patch with no data now) are kept.
	 */
	static List<String> favouritesAfter(Collection<String> saved, List<String> offered, Set<String> ticked)
	{
		Set<String> out = new LinkedHashSet<>();
		for (String id : saved)
		{
			if (!offered.contains(id) || ticked.contains(id))
			{
				out.add(id);
			}
		}
		for (String id : offered)
		{
			if (ticked.contains(id))
			{
				out.add(id);
			}
		}
		return new ArrayList<>(out);
	}
}
