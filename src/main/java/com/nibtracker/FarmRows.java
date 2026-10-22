package com.nibtracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

	/** A crop growing or ready: not empty, unknown, diseased or dead. Only these count towards timers and ready shares. */
	static boolean growing(Routines.Patch p)
	{
		return !p.needsAttention() && !"empty".equals(p.state) && !"unknown".equals(p.state);
	}

	/**
	 * For sorting soonest first: 0 when ready, its time when growing with a timer, then growing with no timer, and last
	 * a patch with nothing growing (a timer on an empty, unknown, diseased or dead patch means nothing).
	 */
	static long soonest(Routines.Patch p, long now)
	{
		if (ready(p, now))
		{
			return 0;
		}
		if (!growing(p))
		{
			return Long.MAX_VALUE;
		}
		return p.readyAt > now ? p.readyAt : Long.MAX_VALUE - 1;
	}

	static long soonest(List<Routines.Patch> ps, long now)
	{
		long best = Long.MAX_VALUE;
		for (Routines.Patch p : ps)
		{
			best = Math.min(best, soonest(p, now));
		}
		return best;
	}

	/** The growing crop that's ready soonest, by its timer; null when none has a timer. */
	static Routines.Patch next(List<Routines.Patch> ps, long now)
	{
		Routines.Patch next = null;
		for (Routines.Patch p : ps)
		{
			if (growing(p) && !ready(p, now) && p.readyAt > now && (next == null || p.readyAt < next.readyAt))
			{
				next = p;
			}
		}
		return next;
	}

	static boolean anyGrowing(List<Routines.Patch> ps)
	{
		return ps.stream().anyMatch(FarmRows::growing);
	}

	/** How many of the growing or ready crops are ready, 0 to 1; empty, unknown, diseased and dead don't count. */
	static double readyShare(List<Routines.Patch> ps, long now)
	{
		long all = ps.stream().filter(FarmRows::growing).count();
		return all == 0 ? 0 : (double) ps.stream().filter(p -> ready(p, now)).count() / all;
	}

	/** What a group says when nothing is ready or timed: Growing, or else Diseased, Dead, Empty or Unknown. */
	static String idle(List<Routines.Patch> ps)
	{
		if (anyGrowing(ps))
		{
			return "Growing";
		}
		for (String state : new String[]{"diseased", "dead", "empty"})
		{
			if (ps.stream().anyMatch(p -> state.equals(p.state)))
			{
				return Character.toUpperCase(state.charAt(0)) + state.substring(1);
			}
		}
		return "Unknown";
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

	/**
	 * The patches worked out here, each with the way there and its stop in the run from the feed row with the same id,
	 * then the feed's rows with no patch here, as they are. When there are patches here, only the feed's unknown rows
	 * (special patches it lists with no data) are added: any other feed-only row is out of date, like an emptied bird
	 * house that no longer has a row here. With no patches here (no Time Tracking data) every feed row shows. With no
	 * feed, or one that's older, the rows still show, without travel tips.
	 */
	static List<Routines.Patch> merge(List<Routines.Patch> local, List<Routines.Patch> feed)
	{
		Map<String, Routines.Patch> byId = new HashMap<>();
		for (Routines.Patch f : feed)
		{
			byId.putIfAbsent(f.id(), f);
		}
		List<Routines.Patch> out = new ArrayList<>();
		Set<String> mine = new HashSet<>();
		for (Routines.Patch p : local)
		{
			mine.add(p.id());
			Routines.Patch f = byId.get(p.id());
			if (f != null)
			{
				p = copy(p);
				p.travel = f.travel;
				p.travelHow = f.travelHow;
				p.travelCat = f.travelCat;
				p.travelItem = f.travelItem;
				p.order = f.order;
			}
			out.add(p);
		}
		for (Routines.Patch f : feed)
		{
			if (!mine.contains(f.id()) && (local.isEmpty() || "unknown".equals(f.state)))
			{
				out.add(f);
			}
		}
		return out;
	}

	/** A copy, so merging leaves the rows it was given as they were. */
	private static Routines.Patch copy(Routines.Patch p)
	{
		Routines.Patch c = new Routines.Patch(p.region, p.name, p.type, p.crop, p.itemId, p.readyAt, p.ready);
		c.travel = p.travel;
		c.travelHow = p.travelHow;
		c.kind = p.kind;
		c.kindLabel = p.kindLabel;
		c.run = p.run;
		c.order = p.order;
		c.travelCat = p.travelCat;
		c.travelItem = p.travelItem;
		c.state = p.state;
		c.progress = p.progress;
		return c;
	}
}
