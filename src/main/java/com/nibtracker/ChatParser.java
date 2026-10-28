package com.nibtracker;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns game chat lines (tags already removed) into Nib Tracker events.
 * Returns the event's type and data, or null if the line means nothing to us.
 * Kept free of RuneLite types so it can be unit tested.
 */
final class ChatParser
{
	static final class Parsed
	{
		final String type;
		final JsonObject data;

		Parsed(String type, JsonObject data)
		{
			this.type = type;
			this.data = data;
		}
	}

	private static final Pattern KC = Pattern.compile(
		"^Your (?:completed |subdued )?(.+?) (?:kill |chest |completion |success |harvest )?count is: ?([0-9,]+)\\.?$");
	private static final Pattern CLOG = Pattern.compile("^New item added to your collection log: (.+?)\\.?$");
	private static final Pattern CA = Pattern.compile(
		"^Congratulations, you've completed an? (easy|medium|hard|elite|master|grandmaster) combat task: (.+?)(?: \\(\\d+ points?\\))?\\.?$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern QUEST = Pattern.compile("^Congratulations, you've completed a quest: (.+?)\\.?$");
	private static final Pattern LEAGUE_TASK = Pattern.compile(
		"^Congratulations, you've completed an? (easy|medium|hard|elite|master) task: (.+?)\\.?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern DIARY_TASK = Pattern.compile(
		"^Well done! You have completed an? (easy|medium|hard|elite) task in the (.+?) area\\..*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern DIARY_DONE = Pattern.compile(
		"^Congratulations! You have completed all of the (easy|medium|hard|elite) tasks in the (.+?) area\\..*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern SLAYER_DONE = Pattern.compile(
		"^You've completed ([0-9,]+) tasks?(?: and received ([0-9,]+) points?, giving you a total of ([0-9,]+))?.*$");
	private static final Pattern CLUE = Pattern.compile(
		"^You have completed ([0-9,]+) (beginner|easy|medium|hard|elite|master) Treasure Trails?\\.?$", Pattern.CASE_INSENSITIVE);
	/* "Fight duration: 1:23.40. Personal best: 1:10.00", "Challenge duration: 25:01 (new personal best)" and the like.
	   The kill count line comes just before, so the web app pairs the two by time. */
	private static final String TIME = "([0-9]+(?::[0-9]{2})+(?:\\.[0-9]{1,2})?)";
	private static final Pattern BOSS_TIME = Pattern.compile(
		"^(Fight|Challenge|Corrupted challenge|Total completion|Theatre of Blood completion|Tombs of Amascut completion|Delve level: [0-9+]+ duration|Duration)"
			+ "(?: duration| time)?: " + TIME + "\\.?(?: ?\\((new personal best)\\))?(?:\\.? ?Personal best: " + TIME + ")?.*$",
		Pattern.CASE_INSENSITIVE);

	private ChatParser()
	{
	}

	/**
	 * Whether a game message the patterns here don't know holds one of the server's chat fragments (settings "chat"),
	 * so the server can start reading new messages without a plugin update. Plain text ignoring case, never a pattern,
	 * so nothing from the server can hang the client: at most 100 fragments of 3 to 100 characters.
	 */
	static boolean watched(String msg, JsonElement fragments)
	{
		if (msg == null || fragments == null || !fragments.isJsonArray())
		{
			return false;
		}
		String line = msg.toLowerCase(Locale.ROOT);
		int n = 0;
		for (JsonElement f : fragments.getAsJsonArray())
		{
			if (++n > 100)
			{
				break;
			}
			if (f.isJsonPrimitive() && f.getAsJsonPrimitive().isString())
			{
				String s = f.getAsString();
				if (s.length() >= 3 && s.length() <= 100 && line.contains(s.toLowerCase(Locale.ROOT)))
				{
					return true;
				}
			}
		}
		return false;
	}

	static Parsed parse(String msg)
	{
		if (msg == null || msg.isEmpty())
		{
			return null;
		}

		Matcher m = KC.matcher(msg);
		if (m.matches())
		{
			JsonObject d = new JsonObject();
			d.addProperty("boss", m.group(1));
			d.addProperty("count", Integer.parseInt(m.group(2).replace(",", "")));
			return new Parsed("kc", d);
		}

		m = CLOG.matcher(msg);
		if (m.matches())
		{
			JsonObject d = new JsonObject();
			d.addProperty("item", m.group(1));
			return new Parsed("clog_new", d);
		}

		if (msg.startsWith("You have a funny feeling like you") || msg.startsWith("You feel something weird sneaking into your backpack"))
		{
			JsonObject d = new JsonObject();
			d.addProperty("duplicate", msg.contains("would have been followed"));
			d.addProperty("backpack", msg.contains("backpack"));
			return new Parsed("pet", d);
		}

		m = CA.matcher(msg);
		if (m.matches())
		{
			JsonObject d = new JsonObject();
			d.addProperty("tier", m.group(1).toLowerCase());
			d.addProperty("task", m.group(2));
			return new Parsed("ca_done", d);
		}

		m = QUEST.matcher(msg);
		if (m.matches())
		{
			JsonObject d = new JsonObject();
			d.addProperty("quest", m.group(1));
			return new Parsed("quest_done", d);
		}

		m = LEAGUE_TASK.matcher(msg);
		if (m.matches())
		{
			JsonObject d = new JsonObject();
			d.addProperty("tier", m.group(1).toLowerCase());
			d.addProperty("task", m.group(2));
			return new Parsed("league_task", d);
		}

		m = DIARY_TASK.matcher(msg);
		if (m.matches())
		{
			JsonObject d = new JsonObject();
			d.addProperty("tier", m.group(1).toLowerCase());
			d.addProperty("region", m.group(2));
			return new Parsed("diary_task", d);
		}

		m = DIARY_DONE.matcher(msg);
		if (m.matches())
		{
			JsonObject d = new JsonObject();
			d.addProperty("tier", m.group(1).toLowerCase());
			d.addProperty("region", m.group(2));
			return new Parsed("diary_done", d);
		}

		m = SLAYER_DONE.matcher(msg);
		if (m.matches())
		{
			JsonObject d = new JsonObject();
			d.addProperty("streak", Integer.parseInt(m.group(1).replace(",", "")));
			if (m.group(2) != null)
			{
				d.addProperty("points", Integer.parseInt(m.group(2).replace(",", "")));
				d.addProperty("total", Integer.parseInt(m.group(3).replace(",", "")));
			}
			return new Parsed("slayer_task_done", d);
		}

		m = CLUE.matcher(msg);
		if (m.matches())
		{
			JsonObject d = new JsonObject();
			d.addProperty("tier", m.group(2).toLowerCase());
			d.addProperty("count", Integer.parseInt(m.group(1).replace(",", "")));
			return new Parsed("clue_done", d);
		}

		m = BOSS_TIME.matcher(msg);
		if (m.matches())
		{
			JsonObject d = new JsonObject();
			d.addProperty("label", m.group(1));
			d.addProperty("seconds", seconds(m.group(2)));
			d.addProperty("newPb", m.group(3) != null);
			if (m.group(4) != null)
			{
				d.addProperty("pbSeconds", seconds(m.group(4)));
			}
			return new Parsed("boss_time", d);
		}

		return null;
	}

	/** "1:23.40" or "1:02:03" to seconds. */
	static double seconds(String time)
	{
		double total = 0;
		for (String part : time.split(":"))
		{
			total = total * 60 + Double.parseDouble(part);
		}
		return Math.round(total * 100) / 100.0;
	}
}
