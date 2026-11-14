package com.nibtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import java.util.List;
import org.junit.Test;

public class ChatParserTest
{
	@Test
	public void serverPatternsReadWhatTheBuiltInOnesDont()
	{
		List<ChatParser.Extra> extras = ChatParser.extras(new Gson().fromJson("["
			+ "{\"type\":\"kc\",\"pattern\":\"^You have helped defeat the (.+) ([0-9,]+) times\\\\.$\",\"groups\":[\"boss\",\"count\"]},"
			+ "{\"type\":\"kc\",\"pattern\":\"^(.+) down$\",\"groups\":[\"boss\"]}," // a kill count needs its count
			+ "{\"type\":\"Bad Type\",\"pattern\":\"^x$\",\"groups\":[]},"
			+ "{\"type\":\"minigame\",\"pattern\":\"^([\",\"groups\":[]}," // doesn't compile
			+ "\"not an object\","
			+ "{\"type\":\"minigame\",\"pattern\":\"^Your reward points are now (.+)\\\\.$\",\"groups\":[\"points\"]}"
			+ "]", JsonArray.class));
		assertEquals(2, extras.size());

		ChatParser.Parsed p = ChatParser.parse("You have helped defeat the Royal Titans 1,204 times.", extras);
		assertEquals("kc", p.type);
		assertEquals("Royal Titans", p.data.get("boss").getAsString());
		assertEquals(1204, p.data.get("count").getAsInt());
		assertEquals("lots", ChatParser.parse("Your reward points are now lots.", extras).data.get("points").getAsString());
		assertEquals("Zulrah", ChatParser.parse("Your Zulrah kill count is: 3.", extras).data.get("boss").getAsString()); // built-in first
		assertNull(ChatParser.parse("Welcome to Old School RuneScape.", extras));
		assertTrue(ChatParser.extras(null).isEmpty()); // nothing from the server: built-in patterns only
	}

	@Test
	public void killCounts()
	{
		ChatParser.Parsed p = ChatParser.parse("Your Zulrah kill count is: 1,512.");
		assertEquals("kc", p.type);
		assertEquals("Zulrah", p.data.get("boss").getAsString());
		assertEquals(1512, p.data.get("count").getAsInt());

		assertEquals("Chambers of Xeric", ChatParser.parse("Your completed Chambers of Xeric count is: 50.").data.get("boss").getAsString());
		assertEquals("Barrows", ChatParser.parse("Your Barrows chest count is: 12.").data.get("boss").getAsString());
		assertEquals("Wintertodt", ChatParser.parse("Your subdued Wintertodt count is: 300.").data.get("boss").getAsString());
	}

	@Test
	public void collectionLogAndPets()
	{
		ChatParser.Parsed p = ChatParser.parse("New item added to your collection log: Dragon pickaxe");
		assertEquals("clog_new", p.type);
		assertEquals("Dragon pickaxe", p.data.get("item").getAsString());

		p = ChatParser.parse("You have a funny feeling like you would have been followed...");
		assertEquals("pet", p.type);
		assertTrue(p.data.get("duplicate").getAsBoolean());
	}

	@Test
	public void tasks()
	{
		ChatParser.Parsed p = ChatParser.parse("Congratulations, you've completed an elite combat task: Perfect Zulrah (5 points).");
		assertEquals("ca_done", p.type);
		assertEquals("elite", p.data.get("tier").getAsString());
		assertEquals("Perfect Zulrah", p.data.get("task").getAsString());

		assertEquals("Dragon Slayer I", ChatParser.parse("Congratulations, you've completed a quest: Dragon Slayer I").data.get("quest").getAsString());
	}

	@Test
	public void cluesAndTimes()
	{
		ChatParser.Parsed p = ChatParser.parse("You have completed 1,204 medium Treasure Trails.");
		assertEquals("clue_done", p.type);
		assertEquals("medium", p.data.get("tier").getAsString());
		assertEquals(1204, p.data.get("count").getAsInt());

		p = ChatParser.parse("Fight duration: 1:23.40. Personal best: 1:10.00");
		assertEquals("boss_time", p.type);
		assertEquals(83.4, p.data.get("seconds").getAsDouble(), 0.001);
		assertEquals(70.0, p.data.get("pbSeconds").getAsDouble(), 0.001);
		assertFalse(p.data.get("newPb").getAsBoolean());

		p = ChatParser.parse("Challenge duration: 25:01 (new personal best)");
		assertEquals("Challenge", p.data.get("label").getAsString());
		assertEquals(1501.0, p.data.get("seconds").getAsDouble(), 0.001);
		assertTrue(p.data.get("newPb").getAsBoolean());
		assertFalse(p.data.has("delve"));

		p = ChatParser.parse("Delve level: 8+ duration: 1:23.40");
		assertEquals("boss_time", p.type);
		assertEquals("8+", p.data.get("delve").getAsString());
		assertEquals(83.4, p.data.get("seconds").getAsDouble(), 0.001);

		assertEquals(3723.0, ChatParser.seconds("1:02:03"), 0.001);
	}

	@Test
	public void leaguesDiariesAndSlayer()
	{
		ChatParser.Parsed p = ChatParser.parse("Congratulations, you've completed a hard task: Kill a Demonic Gorilla.");
		assertEquals("league_task", p.type);
		assertEquals("hard", p.data.get("tier").getAsString());
		assertEquals("Kill a Demonic Gorilla", p.data.get("task").getAsString());

		// A combat task is still a combat task, not a league task.
		assertEquals("ca_done", ChatParser.parse("Congratulations, you've completed a hard combat task: Whack-a-Mole (3 points).").type);

		p = ChatParser.parse("Well done! You have completed an easy task in the Ardougne area. Your Achievement Diary has been updated.");
		assertEquals("diary_task", p.type);
		assertEquals("Ardougne", p.data.get("region").getAsString());

		p = ChatParser.parse("Congratulations! You have completed all of the medium tasks in the Varrock area. Speak to Toby to claim your reward.");
		assertEquals("diary_done", p.type);
		assertEquals("medium", p.data.get("tier").getAsString());

		p = ChatParser.parse("You've completed 123 tasks and received 15 points, giving you a total of 1,234; return to a Slayer master.");
		assertEquals("slayer_task_done", p.type);
		assertEquals(123, p.data.get("streak").getAsInt());
		assertEquals(1234, p.data.get("total").getAsInt());
	}

	@Test
	public void accountIdIsStableAndOneWay()
	{
		String a = NibTrackerPlugin.accountId(123456789L);
		assertEquals(a, NibTrackerPlugin.accountId(123456789L));
		assertEquals(32, a.length());
		assertFalse(a.contains("123456789"));
	}

	@Test
	public void ignoresOtherLines()
	{
		assertNull(ChatParser.parse("Welcome to Old School RuneScape."));
		assertNull(ChatParser.parse(""));
	}
}
