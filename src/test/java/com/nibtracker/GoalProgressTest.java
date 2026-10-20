package com.nibtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class GoalProgressTest
{
	private static GoalProgress.Goal goal(String json)
	{
		List<GoalProgress.Goal> l = GoalProgress.parse(new JsonParser().parse("[" + json + "]"));
		assertEquals(1, l.size());
		return l.get(0);
	}

	@Test
	public void parseSkipsMalformedAndCaps()
	{
		StringBuilder sb = new StringBuilder("[1, {\"title\":\"no id\",\"type\":\"kc\"}, {\"id\":\"x\"}");
		for (int i = 0; i < 160; i++)
		{
			sb.append(",{\"id\":\"g").append(i).append("\",\"type\":\"custom\"}");
		}
		List<GoalProgress.Goal> l = GoalProgress.parse(new JsonParser().parse(sb.append("]").toString()));
		assertEquals(GoalProgress.MAX_GOALS, l.size());
		assertEquals("Goal", l.get(0).title);
		assertTrue(GoalProgress.parse(null).isEmpty());
		assertTrue(GoalProgress.parse(new JsonParser().parse("{}")).isEmpty());
	}

	@Test
	public void skillLevelMeasuresFromZeroXp()
	{
		GoalProgress.Goal g = goal("{\"id\":\"a\",\"title\":\"99 Woodcutting\",\"type\":\"skill_level\",\"params\":{\"skill\":\"Woodcutting\",\"level\":99}}");
		GoalProgress.Facts f = new GoalProgress.Facts();
		f.xp.put("woodcutting", 6_517_253L); // level 92, half of 99
		f.levels.put("woodcutting", 92);
		GoalProgress.Result r = GoalProgress.compute(g, f);
		assertEquals(0.5, r.progress, 0.001);
		assertEquals("92 / 99", r.detail);
		assertEquals("6,517,178 XP to go", r.hint);
		assertFalse(r.done);

		f.xp.put("woodcutting", 13_034_431L);
		f.levels.put("woodcutting", 99);
		r = GoalProgress.compute(g, f);
		assertEquals(1, r.progress, 0);
		assertTrue(r.done);
	}

	@Test
	public void skillXpAndRunecraftName()
	{
		GoalProgress.Goal g = goal("{\"id\":\"a\",\"type\":\"skill_xp\",\"params\":{\"skill\":\"Runecrafting\",\"xp\":200000}}");
		GoalProgress.Facts f = new GoalProgress.Facts();
		f.xp.put("runecraft", 50_000L);
		f.levels.put("runecraft", 40);
		GoalProgress.Result r = GoalProgress.compute(g, f);
		assertEquals(0.25, r.progress, 0.0001);
		assertEquals("50,000 / 200K", r.detail);
		assertEquals("150,000 XP to go", r.hint);
	}

	@Test
	public void baseLevelAveragesSkills()
	{
		GoalProgress.Goal g = goal("{\"id\":\"a\",\"type\":\"base_level\",\"params\":{\"level\":70}}");
		GoalProgress.Facts f = new GoalProgress.Facts();
		f.levels.put("attack", 70);
		f.xp.put("attack", (long) GoalProgress.xpFor(70));
		f.levels.put("magic", 1);
		f.xp.put("magic", 0L);
		GoalProgress.Result r = GoalProgress.compute(g, f);
		assertEquals(0.5, r.progress, 0.0001);
		assertEquals("Lowest 1", r.detail);
		assertEquals("1 skill below 70, 737,627 XP to go", r.hint);
		assertFalse(r.done);
	}

	@Test
	public void killCountUsesHigherOfChatAndServerAndAliases()
	{
		GoalProgress.Goal g = goal("{\"id\":\"a\",\"type\":\"kc\",\"params\":{\"boss\":\"barrows_chests\",\"count\":100},\"current\":40}");
		GoalProgress.Facts f = new GoalProgress.Facts();
		GoalProgress.Result r = GoalProgress.compute(g, f);
		assertEquals(0.4, r.progress, 0.0001);
		assertEquals("40 / 100", r.detail);
		assertEquals("60 more kills", r.hint);

		f.kcAliases = Collections.singletonMap("barrows", "barrows_chests");
		f.kc.put(GoalProgress.metric("Barrows", f.kcAliases), 100);
		r = GoalProgress.compute(g, f);
		assertTrue(r.done);
		assertEquals("100 / 100", r.detail);
		assertEquals("", r.hint);
	}

	@Test
	public void metricMatchesServer()
	{
		assertEquals("kreearra", GoalProgress.metric("Kree'arra", Collections.emptyMap()));
		assertEquals("chambers_of_xeric", GoalProgress.metric("Chambers of Xeric", Collections.emptyMap()));
		assertEquals("the_gauntlet", GoalProgress.metric("Gauntlet", Collections.singletonMap("gauntlet", "the_gauntlet")));
	}

	@Test
	public void collectionLogAndQuest()
	{
		GoalProgress.Facts f = new GoalProgress.Facts();
		GoalProgress.Goal clog = goal("{\"id\":\"a\",\"type\":\"clog_item\",\"params\":{\"item\":\"Dragon pickaxe\"},\"progress\":0}");
		assertEquals(0, GoalProgress.compute(clog, f).progress, 0);
		assertFalse(GoalProgress.compute(clog, f).done);
		f.clog.add("dragon pickaxe");
		assertTrue(GoalProgress.compute(clog, f).done);

		GoalProgress.Goal quest = goal("{\"id\":\"b\",\"type\":\"quest\",\"params\":{\"quest\":\"Dragon Slayer I\"}}");
		assertEquals(-1, GoalProgress.compute(quest, f).progress, 0);
		f.quests.put("dragon slayer i", "IN_PROGRESS");
		assertEquals("In progress", GoalProgress.compute(quest, f).detail);
		f.quests.put("dragon slayer i", "FINISHED");
		assertTrue(GoalProgress.compute(quest, f).done);
	}

	@Test
	public void itemOwnedCountsBankAndCarried()
	{
		GoalProgress.Goal g = goal("{\"id\":\"a\",\"type\":\"item_owned\",\"params\":{\"item\":\"Shark\",\"qty\":1000},\"target\":1000,\"current\":300}");
		GoalProgress.Facts f = new GoalProgress.Facts();
		// Nothing seen yet: the server's count
		assertEquals(0.3, GoalProgress.compute(g, f).progress, 0.0001);
		// Carried but bank not opened: the higher of the two
		f.carried.put("shark", 500L);
		assertEquals("500 / 1,000", GoalProgress.compute(g, f).detail);
		// Bank seen: bank plus carried
		f.bankSeen = true;
		f.bank.put("shark", 500L);
		assertTrue(GoalProgress.compute(g, f).done);
	}

	@Test
	public void otherTypesShowServerProgressAndServerDoneSticks()
	{
		GoalProgress.Facts f = new GoalProgress.Facts();
		GoalProgress.Goal checklist = goal("{\"id\":\"a\",\"type\":\"checklist\",\"target\":4,\"current\":1,\"progress\":0.25}");
		GoalProgress.Result r = GoalProgress.compute(checklist, f);
		assertEquals(0.25, r.progress, 0);
		assertEquals("1 / 4", r.detail);

		GoalProgress.Goal custom = goal("{\"id\":\"b\",\"type\":\"custom\"}");
		assertEquals(-1, GoalProgress.compute(custom, f).progress, 0);

		GoalProgress.Goal done = goal("{\"id\":\"c\",\"type\":\"skill_level\",\"params\":{\"skill\":\"Attack\",\"level\":99},\"done\":true,\"points\":100}");
		f.xp.put("attack", 0L);
		f.levels.put("attack", 1);
		assertTrue(GoalProgress.compute(done, f).done);
		assertEquals(100, done.points);
	}

	@Test
	public void categoriesAndCompactNumbers()
	{
		assertEquals("Skills", GoalProgress.category("base_level"));
		assertEquals("Quests", GoalProgress.category("quest"));
		assertEquals("Bosses", GoalProgress.category("kc"));
		assertEquals("Achievement diaries", GoalProgress.category("diary_tier"));
		assertEquals("Other", GoalProgress.category("something_new"));
		assertEquals("13.0M", GoalProgress.compact(13_034_431));
		assertEquals("250K", GoalProgress.compact(250_000));
		assertEquals("99,999", GoalProgress.compact(99_999));
	}

	@Test
	public void goalLinks()
	{
		assertEquals("https://buntracker.pages.dev/#goal=gabc123&c=Kimchi%20Bun",
			GoalTracker.link(goal("{\"id\":\"gabc123\",\"type\":\"kc\",\"params\":{\"boss\":\"vorkath\",\"count\":100}}"), "Kimchi Bun"));
		assertEquals("https://buntracker.pages.dev/#goal=a%26b", GoalTracker.link(goal("{\"id\":\"a&b\",\"type\":\"custom\"}"), null));
		assertEquals("https://oldschool.runescape.wiki/w/Dragon_Slayer_I%2FQuick_guide",
			GoalTracker.link(goal("{\"id\":\"q\",\"type\":\"quest\",\"params\":{\"quest\":\"Dragon Slayer I\"}}"), "Kimchi Bun"));
		assertEquals("https://oldschool.runescape.wiki/w/Kourend_%26_Kebos_Diary",
			GoalTracker.link(goal("{\"id\":\"d\",\"type\":\"diary_tier\",\"params\":{\"region\":\"Kourend & Kebos\",\"tier\":\"Hard\"}}"), null));
	}

	@Test
	public void serverInfoIsTheHint()
	{
		GoalProgress.Goal ca = goal("{\"id\":\"a\",\"type\":\"combat_achievement\",\"params\":{\"task\":\"Shayzien Protector\"},\"info\":\"Kill 25 lizardman shamans\"}");
		assertEquals("Kill 25 lizardman shamans", GoalProgress.compute(ca, new GoalProgress.Facts()).hint);
		GoalProgress.Goal bare = goal("{\"id\":\"b\",\"type\":\"combat_achievement\",\"params\":{\"task\":\"Master of Buckets\"}}");
		assertEquals("Updates when Nib Tracker sees it", GoalProgress.compute(bare, new GoalProgress.Facts()).hint);
	}

	@Test
	public void parsesMainGoalsAndParts()
	{
		GoalProgress.Goal main = goal("{\"id\":\"b\",\"type\":\"base_level\",\"params\":{\"level\":80},\"parts\":[3,23]}");
		assertEquals("", main.parent);
		assertEquals(3, main.partsDone);
		assertEquals(23, main.partsTotal);
		GoalProgress.Goal part = goal("{\"id\":\"s\",\"type\":\"skill_level\",\"params\":{\"skill\":\"Smithing\",\"level\":80},\"parent\":\"b\",\"parts\":\"x\"}");
		assertEquals("b", part.parent);
		assertEquals(0, part.partsTotal);
	}

	@Test
	public void shortTextForTheGameScreen()
	{
		assertEquals("73/80", GoalProgress.shortText("73 / 80", 0.5, false));
		assertEquals("34 left", GoalProgress.shortText("34 left", 0.6, false));
		assertEquals("53%", GoalProgress.shortText("1,234,567 / 13.0M", 0.53, false));
		assertEquals("40%", GoalProgress.shortText("", 0.4, false));
		assertEquals("Done", GoalProgress.shortText("73 / 80", 1, true));
	}
}
