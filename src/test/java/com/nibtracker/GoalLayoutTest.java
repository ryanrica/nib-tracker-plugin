package com.nibtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class GoalLayoutTest
{
	private static GoalsPanel.Row row(String id, String category, double progress, boolean done, String parent)
	{
		GoalsPanel.Row r = new GoalsPanel.Row(id, category, id, "", "", progress, done, 0, null, null, -1, null);
		r.parent = parent;
		r.serverDone = done;
		return r;
	}

	@Test
	public void partsFoldUnderTheirMainGoal()
	{
		GoalsPanel.Row base = row("base", "Skills", 0.6, false, "");
		base.partsDone = 20;
		base.partsTotal = 23;
		List<GoalsPanel.Row> rows = Arrays.asList(base,
			row("smith", "Skills", 0.5, false, "base"), row("fish", "Skills", 0.9, false, "base"),
			row("ds1", "Quests", 0, false, ""));
		GoalLayout.Layout l = GoalLayout.of(rows);
		assertEquals(1, l.sections.get("Skills").size());
		GoalLayout.Group g = l.sections.get("Skills").get(0);
		assertEquals("base", g.main.id);
		assertEquals(Arrays.asList("fish", "smith"), Arrays.asList(g.parts.get(0).id, g.parts.get(1).id));
		assertEquals(1, l.sections.get("Quests").size());
		assertTrue(l.completed.isEmpty());
	}

	@Test
	public void mainGoalWithoutItsOwnProgressCountsItsParts()
	{
		GoalsPanel.Row cape = row("cape", "Items", -1, false, "");
		cape.partsDone = 8;
		cape.partsTotal = 10;
		GoalsPanel.Row q1 = row("q1", "Quests", 0, false, "cape");
		GoalsPanel.Row q2 = row("q2", "Quests", 1, true, "cape");
		q2.serverDone = false; // done in game since the web app last sent the list
		GoalLayout.Group g = GoalLayout.of(Arrays.asList(cape, q1, q2)).sections.get("Items").get(0);
		assertEquals(9, g.partsDone());
		assertEquals(10, g.partsTotal());
		assertEquals(0.9, g.progress(), 0.0001);
	}

	@Test
	public void finishedGoalsGoToCompletedAndOrphanPartsStandAlone()
	{
		List<GoalsPanel.Row> rows = Arrays.asList(row("done", "Quests", 1, true, ""), row("orphan", "Skills", 0.2, false, "gone"));
		GoalLayout.Layout l = GoalLayout.of(rows);
		assertEquals("done", l.completed.get(0).main.id);
		assertEquals("orphan", l.sections.get("Skills").get(0).main.id);
		assertEquals(1, l.sections.size());
	}

	@Test
	public void extrasShowUnderTheirGoalButAreNotParts()
	{
		GoalsPanel.Row slayer = row("s95", "Skills", 0.4, false, "");
		GoalsPanel.Row task = row(GoalTracker.SLAYER_TASK, "Skills", 0.5, false, "s95");
		task.extra = true;
		GoalsPanel.Row lost = row("x", "Skills", 0.5, false, "missing");
		lost.extra = true;
		GoalLayout.Layout l = GoalLayout.of(Arrays.asList(slayer, task, lost));
		GoalLayout.Group g = l.sections.get("Skills").get(0);
		assertEquals(1, l.sections.get("Skills").size());
		assertEquals(GoalTracker.SLAYER_TASK, g.extras.get(0).id);
		assertTrue(g.parts.isEmpty());
		assertEquals(0, g.partsTotal());
	}

	@Test
	public void overviewKeepsOnlyFavourites()
	{
		GoalsPanel.Row base = row("base", "Skills", 0.6, false, "");
		List<GoalsPanel.Row> rows = Arrays.asList(base, row("smith", "Skills", 0.5, false, "base"),
			row("fish", "Skills", 0.9, false, "base"), row("att", "Skills", 0.3, false, ""), row("hp", "Skills", 0.1, false, ""),
			row("done1", "Quests", 1, true, ""), row("done2", "Quests", 1, true, ""));
		GoalLayout.Layout l = GoalLayout.of(rows);
		Map<String, Set<String>> favs = new HashMap<>();
		favs.put("Skills", new HashSet<>(Arrays.asList("fish", "hp")));
		favs.put("Quests", new HashSet<>(Collections.singletonList("done2")));

		// A favourite part comes on its own; a group whose main goal isn't a favourite doesn't.
		GoalLayout.Picks skills = GoalLayout.favourites(l.sections.get("Skills"), favs);
		assertTrue(skills.groups.stream().anyMatch(g -> "hp".equals(g.main.id)));
		assertEquals(1, skills.groups.size());
		assertEquals(Collections.singletonList("fish"), Arrays.asList(skills.rows.get(0).id));
		assertEquals(2, skills.size());

		// A favourite main goal brings its group (and its parts with it, not again on their own).
		favs.put("Skills", new HashSet<>(Arrays.asList("base", "fish")));
		GoalLayout.Picks whole = GoalLayout.favourites(l.sections.get("Skills"), favs);
		assertEquals("base", whole.groups.get(0).main.id);
		assertTrue(whole.rows.isEmpty());

		// Completed: only the favourited ones.
		GoalLayout.Picks done = GoalLayout.favourites(l.completed, favs);
		assertEquals(1, done.groups.size());
		assertEquals("done2", done.groups.get(0).main.id);

		assertTrue(GoalLayout.favourites(l.completed, Collections.emptyMap()).isEmpty());
	}

	@Test
	public void overviewOrderDefaultsAndKeepsSavedOrder()
	{
		assertEquals(GoalLayout.OVERVIEW_ORDER, GoalLayout.overviewOrder(""));
		assertEquals("Farming", GoalLayout.overviewOrder("").get(0));
		// Saved names first, unknown and repeated ones dropped, the rest after in the default order.
		List<String> o = GoalLayout.overviewOrder("Skills, Nonsense,Quests,Skills");
		assertEquals(Arrays.asList("Skills", "Quests", "Farming", "Daily tasks"), o.subList(0, 4));
		assertEquals(GoalLayout.OVERVIEW_ORDER.size(), o.size());
	}

	@Test
	public void movingASectionLandsWhereItIsDropped()
	{
		List<String> o = Arrays.asList("A", "B", "C", "D");
		assertEquals(Arrays.asList("B", "C", "A", "D"), GoalLayout.move(o, "A", "C"));
		assertEquals(Arrays.asList("A", "D", "B", "C"), GoalLayout.move(o, "D", "B"));
		assertEquals(o, GoalLayout.move(o, "B", "B"));
		assertEquals(o, GoalLayout.move(o, "B", "Z"));
	}
}
