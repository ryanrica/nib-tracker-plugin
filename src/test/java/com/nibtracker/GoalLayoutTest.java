package com.nibtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.List;
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
}
