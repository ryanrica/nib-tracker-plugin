package com.nibtracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Experience;

/**
 * Goals from the server's settings, and their progress worked out from what the plugin can see. Only these built-in goal
 * types are worked out here (skill level, skill XP, base level, kill count, collection log item, quest, item owned); for
 * any other type the server's own progress is shown. The server only ever sends parameters, never logic.
 * Kept free of client calls so it can be unit tested.
 */
final class GoalProgress
{
	private GoalProgress()
	{
	}

	/** One goal as the web app published it. */
	static final class Goal
	{
		final String id;
		final String title;
		final String type;
		final JsonObject params;
		final double target;
		final double current;
		final double progress;
		final boolean done;
		final int points;

		Goal(String id, String title, String type, JsonObject params, double target, double current, double progress, boolean done, int points)
		{
			this.id = id;
			this.title = title;
			this.type = type;
			this.params = params;
			this.target = target;
			this.current = current;
			this.progress = progress;
			this.done = done;
			this.points = points;
		}

		String str(String key)
		{
			JsonElement v = params.get(key);
			return v != null && v.isJsonPrimitive() ? v.getAsString() : "";
		}

		double num(String key, double fallback)
		{
			JsonElement v = params.get(key);
			return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : fallback;
		}
	}

	/** What the plugin has seen this session. Names are lower case; skills by their RuneLite name. */
	static final class Facts
	{
		final Map<String, Long> xp = new HashMap<>();
		final Map<String, Integer> levels = new HashMap<>();
		final Map<String, Integer> kc = new HashMap<>();
		final Set<String> clog = new HashSet<>();
		final Map<String, String> quests = new HashMap<>();
		final Map<String, Long> bank = new HashMap<>();
		final Map<String, Long> carried = new HashMap<>();
		boolean bankSeen;
		Map<String, String> kcAliases = Collections.emptyMap();

		int kcFor(String boss)
		{
			Integer n = kc.get(metric(boss, kcAliases));
			return n == null ? -1 : n;
		}
	}

	/**
	 * A goal's state for display. progress is 0..1, or -1 when there's nothing to measure. detail is the short value
	 * ("73 / 80"), hint what's left ("1,234,567 XP to go"); either may be empty.
	 */
	static final class Result
	{
		final double progress;
		final String detail;
		final String hint;
		final boolean done;

		Result(double progress, String detail, String hint, boolean done)
		{
			this.progress = progress;
			this.detail = detail;
			this.hint = done ? "" : hint;
			this.done = done;
		}
	}

	/** The panel section a goal type goes in, in display order. */
	static final String[] CATEGORIES = {"Skills", "Quests", "Bosses", "Collection log", "Items", "Diaries", "Combat tasks", "Other"};

	static String category(String type)
	{
		switch (type)
		{
			case "skill_level":
			case "skill_xp":
			case "base_level":
				return "Skills";
			case "quest":
				return "Quests";
			case "kc":
				return "Bosses";
			case "clog_item":
				return "Collection log";
			case "item_owned":
				return "Items";
			case "diary_task":
			case "diary_tier":
				return "Diaries";
			case "combat_achievement":
				return "Combat tasks";
			default:
				return "Other";
		}
	}

	/** Reads settings.goals. Anything malformed is skipped. */
	static List<Goal> parse(JsonElement goals)
	{
		List<Goal> out = new ArrayList<>();
		if (goals == null || !goals.isJsonArray())
		{
			return out;
		}
		for (JsonElement e : (JsonArray) goals)
		{
			if (!e.isJsonObject())
			{
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			String id = text(o, "id");
			String type = text(o, "type");
			if (id.isEmpty() || type.isEmpty())
			{
				continue;
			}
			JsonElement params = o.get("params");
			out.add(new Goal(id, text(o, "title").isEmpty() ? "Goal" : text(o, "title"), type,
				params != null && params.isJsonObject() ? params.getAsJsonObject() : new JsonObject(),
				number(o, "target", -1), number(o, "current", -1), number(o, "progress", -1),
				o.has("done") && o.get("done").isJsonPrimitive() && o.get("done").getAsBoolean(), (int) number(o, "points", 0)));
			if (out.size() >= 50)
			{
				break;
			}
		}
		return out;
	}

	/** Works out a goal's progress. A goal the server says is done stays done. */
	static Result compute(Goal g, Facts f)
	{
		Result r = local(g, f);
		if (r == null)
		{
			r = fromServer(g);
		}
		return g.done && !r.done ? new Result(1, r.detail, "", true) : r;
	}

	private static Result local(Goal g, Facts f)
	{
		switch (g.type)
		{
			case "skill_level":
			{
				String skill = skillKey(g.str("skill"));
				int target = (int) g.num("level", g.target);
				Long xp = f.xp.get(skill);
				Integer level = f.levels.get(skill);
				if (xp == null || level == null || target < 1)
				{
					return null;
				}
				return new Result(ratio(xp, xpFor(target)), level + " / " + target, toGo(xpFor(target) - xp, " XP to go"), level >= target);
			}
			case "skill_xp":
			{
				Long xp = f.xp.get(skillKey(g.str("skill")));
				double target = g.num("xp", g.target);
				if (xp == null || target <= 0)
				{
					return null;
				}
				return new Result(ratio(xp, target), compact(xp) + " / " + compact(target), toGo(target - xp, " XP to go"), xp >= target);
			}
			case "base_level":
			{
				int target = (int) g.num("level", g.target);
				if (f.levels.isEmpty() || target < 1)
				{
					return null;
				}
				double sum = 0;
				int lowest = Integer.MAX_VALUE;
				int below = 0;
				long xpLeft = 0;
				for (Map.Entry<String, Integer> s : f.levels.entrySet())
				{
					lowest = Math.min(lowest, s.getValue());
					long xp = f.xp.getOrDefault(s.getKey(), 0L);
					sum += ratio(xp, xpFor(target));
					if (s.getValue() < target)
					{
						below++;
						xpLeft += Math.max(0, xpFor(target) - xp);
					}
				}
				String hint = below == 0 ? "" : below + (below == 1 ? " skill" : " skills") + " below " + target + ", " + fmt(xpLeft) + " XP to go";
				return new Result(sum / f.levels.size(), "Lowest " + lowest, hint, lowest >= target);
			}
			case "kc":
			{
				double target = g.num("count", g.target);
				int seen = f.kcFor(g.str("boss"));
				if (target <= 0 || (seen < 0 && g.current < 0))
				{
					return null;
				}
				double count = Math.max(seen, g.current);
				return new Result(ratio(count, target), fmt(count) + " / " + fmt(target), toGo(target - count, " more kills"), count >= target);
			}
			case "clog_item":
			{
				if (!f.clog.contains(g.str("item").toLowerCase(Locale.ROOT)))
				{
					return null;
				}
				return new Result(1, "Obtained", "", true);
			}
			case "quest":
			{
				String state = f.quests.get(g.str("quest").toLowerCase(Locale.ROOT));
				if (state == null)
				{
					return null;
				}
				switch (state)
				{
					case "FINISHED":
						return new Result(1, "Completed", "", true);
					case "IN_PROGRESS":
						return new Result(0.5, "In progress", "Quick guide in Nib Tracker", false);
					default:
						return new Result(0, "Not started", "Quick guide in Nib Tracker", false);
				}
			}
			case "item_owned":
			{
				String item = g.str("item").toLowerCase(Locale.ROOT);
				double target = Math.max(1, g.num("qty", g.target > 0 ? g.target : 1));
				long carried = f.carried.getOrDefault(item, 0L);
				double have = f.bankSeen ? f.bank.getOrDefault(item, 0L) + carried : Math.max(carried, g.current);
				if (!f.bankSeen && carried == 0)
				{
					return null;
				}
				String hint = f.bankSeen ? toGo(target - have, " more needed") : "Open your bank to count it";
				return new Result(ratio(have, target), fmt(have) + " / " + fmt(target), hint, have >= target);
			}
			default:
				return null;
		}
	}

	private static Result fromServer(Goal g)
	{
		boolean counted = g.target > 0 && g.current >= 0;
		String detail = counted ? fmt(g.current) + " / " + fmt(g.target) : g.done ? "Done" : "";
		double p = g.done ? 1 : g.progress >= 0 ? Math.min(1, g.progress) : counted ? ratio(g.current, g.target) : -1;
		String hint;
		switch (g.type)
		{
			case "clog_item":
				hint = "Not in your collection log yet";
				break;
			case "item_owned":
				hint = "Open your bank to count it";
				break;
			default:
				hint = "Updates when Nib Tracker sees it";
		}
		return new Result(p, detail, hint, g.done);
	}

	private static String toGo(double left, String what)
	{
		return left > 0 ? fmt(Math.ceil(left)) + what : "";
	}

	/** 13,034,431 as 13.0M and 250,000 as 250K, to keep the panel's value column narrow. */
	static String compact(double n)
	{
		if (n >= 10_000_000)
		{
			return String.format(Locale.US, "%.1fM", n / 1_000_000);
		}
		if (n >= 100_000)
		{
			return Math.round(n / 1000) + "K";
		}
		return fmt(n);
	}

	/** A boss name as a Wise Old Man metric key, as the server's bt_wom_metric does, with its alias list. */
	static String metric(String name, Map<String, String> aliases)
	{
		String k = name.toLowerCase(Locale.ROOT).replace("'", "").replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		return aliases.getOrDefault(k, k);
	}

	/** Skill names as the web app writes them, as RuneLite's lower-case names. */
	static String skillKey(String skill)
	{
		String s = skill.toLowerCase(Locale.ROOT);
		return "runecrafting".equals(s) ? "runecraft" : s;
	}

	static int xpFor(int level)
	{
		return Experience.getXpForLevel(Math.max(1, Math.min(Experience.MAX_VIRT_LEVEL, level)));
	}

	private static double ratio(double have, double need)
	{
		return need <= 0 ? 1 : Math.max(0, Math.min(1, have / need));
	}

	static String fmt(double n)
	{
		return NumberFormat.getIntegerInstance(Locale.US).format((long) n);
	}

	private static String text(JsonObject o, String key)
	{
		JsonElement v = o.get(key);
		return v != null && v.isJsonPrimitive() ? v.getAsString() : "";
	}

	private static double number(JsonObject o, String key, double fallback)
	{
		JsonElement v = o.get(key);
		return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : fallback;
	}
}
