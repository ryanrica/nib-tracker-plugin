package com.nibtracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How the Goals panel arranges goals: each main goal with its parts folded under it (Base 80 with its skills, a quest
 * cape with its quests), main goals in sections by kind, and finished main goals in a Completed section.
 * Kept free of Swing so it can be unit tested.
 */
final class GoalLayout
{
	private GoalLayout()
	{
	}

	/** A main goal and its parts (empty for a goal on its own). */
	static final class Group
	{
		final GoalsPanel.Row main;
		final List<GoalsPanel.Row> parts;
		/* Shown under the goal but not parts of it (the current Slayer task under a Slayer goal). */
		final List<GoalsPanel.Row> extras = new ArrayList<>();

		Group(GoalsPanel.Row main, List<GoalsPanel.Row> parts)
		{
			this.main = main;
			this.parts = parts;
		}

		/** Parts done: what the web app counted, plus parts done in game since it last sent them. */
		int partsDone()
		{
			int later = 0;
			for (GoalsPanel.Row p : parts)
			{
				if (p.done && !p.serverDone)
				{
					later++;
				}
			}
			return Math.min(partsTotal(), main.partsDone + later);
		}

		int partsTotal()
		{
			return Math.max(main.partsTotal, parts.size());
		}

		/** The main goal's own progress, or the share of parts done when it has none (a quest cape, say). */
		double progress()
		{
			if (main.done)
			{
				return 1;
			}
			if (main.progress >= 0 || partsTotal() == 0)
			{
				return main.progress;
			}
			return (double) partsDone() / partsTotal();
		}
	}

	static final class Layout
	{
		/** Open main goals by section, in display order; empty sections are left out. */
		final Map<String, List<Group>> sections = new LinkedHashMap<>();
		/** Finished main goals, most recent first as the server lists them. */
		final List<Group> completed = new ArrayList<>();
	}

	static Layout of(List<GoalsPanel.Row> rows)
	{
		Map<String, GoalsPanel.Row> byId = new HashMap<>();
		for (GoalsPanel.Row r : rows)
		{
			byId.put(r.id, r);
		}
		Map<String, Group> groups = new LinkedHashMap<>();
		for (GoalsPanel.Row r : rows)
		{
			// A part whose main goal isn't in the list stands on its own; an extra without its goal isn't shown.
			if (r.extra)
			{
				continue;
			}
			if (r.parent.isEmpty() || !byId.containsKey(r.parent) || r.parent.equals(r.id))
			{
				groups.put(r.id, new Group(r, new ArrayList<>()));
			}
		}
		for (GoalsPanel.Row r : rows)
		{
			Group g = r.parent.isEmpty() ? null : groups.get(r.parent);
			if (g != null && r.extra)
			{
				g.extras.add(r);
			}
			else if (g != null && !groups.containsKey(r.id))
			{
				g.parts.add(r);
			}
		}
		Layout out = new Layout();
		for (String c : GoalProgress.CATEGORIES)
		{
			out.sections.put(c, new ArrayList<>());
		}
		for (Group g : groups.values())
		{
			// Parts still to do first, closest to done first; finished parts after them.
			g.parts.sort(Comparator.comparing((GoalsPanel.Row r) -> r.done).thenComparing(r -> -r.progress));
			if (g.main.done)
			{
				out.completed.add(g);
			}
			else
			{
				out.sections.computeIfAbsent(g.main.category, k -> new ArrayList<>()).add(g);
			}
		}
		for (List<Group> s : out.sections.values())
		{
			s.sort(Comparator.comparing((Group g) -> -g.progress()));
		}
		out.sections.values().removeIf(List::isEmpty);
		return out;
	}

	/** What Overview shows of some goals: whole groups, and goals on their own. */
	static final class Picks
	{
		final List<Group> groups = new ArrayList<>();
		final List<GoalsPanel.Row> rows = new ArrayList<>();

		int size()
		{
			return groups.size() + rows.size();
		}

		boolean isEmpty()
		{
			return size() == 0;
		}
	}

	/**
	 * The favourites among some groups, each checked against its own tab's favourites (favs by category, as the ★
	 * saves them). A favourite main goal brings its group with its parts; otherwise each favourite part or extra comes
	 * on its own.
	 */
	static Picks favourites(List<Group> groups, Map<String, Set<String>> favs)
	{
		Picks out = new Picks();
		for (Group g : groups)
		{
			if (favourite(g.main, favs))
			{
				out.groups.add(g);
				continue;
			}
			for (GoalsPanel.Row r : g.extras)
			{
				if (favourite(r, favs))
				{
					out.rows.add(r);
				}
			}
			for (GoalsPanel.Row r : g.parts)
			{
				if (favourite(r, favs))
				{
					out.rows.add(r);
				}
			}
		}
		return out;
	}

	private static boolean favourite(GoalsPanel.Row r, Map<String, Set<String>> favs)
	{
		return favs.getOrDefault(r.category, Collections.emptySet()).contains(r.id);
	}

	/* Overview's sections, top to bottom, until the player drags them into their own order. Completed is always last. */
	static final List<String> OVERVIEW_ORDER = Collections.unmodifiableList(Arrays.asList("Farming", "Daily tasks",
		"Combat achievements", "Quests", "Collection log", "Skills", "Bosses", "Items", "Achievement diaries", "Other"));

	/**
	 * Overview's section order from the saved setting (names, comma separated): the saved ones it knows, in that order,
	 * then any it doesn't mention in the default order, so a new section still shows.
	 */
	static List<String> overviewOrder(String saved)
	{
		List<String> out = new ArrayList<>();
		for (String s : saved.split(","))
		{
			String t = s.trim();
			if (OVERVIEW_ORDER.contains(t) && !out.contains(t))
			{
				out.add(t);
			}
		}
		for (String t : OVERVIEW_ORDER)
		{
			if (!out.contains(t))
			{
				out.add(t);
			}
		}
		return out;
	}

	/**
	 * The order with a section dropped onto another: moving down it lands after that one, moving up before it, so it ends
	 * up where it was dropped either way.
	 */
	static List<String> move(List<String> order, String section, String onto)
	{
		List<String> out = new ArrayList<>(order);
		int from = out.indexOf(section), to = out.indexOf(onto);
		if (from < 0 || to < 0 || from == to)
		{
			return out;
		}
		out.remove(from);
		out.add(to, section);
		return out;
	}
}
