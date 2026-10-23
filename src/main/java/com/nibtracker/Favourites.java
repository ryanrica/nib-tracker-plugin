package com.nibtracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Per-tab favourites and the pin ids. Pure, so it can be tested. */
final class Favourites
{
	/* Ids hold |, : and commas, so tabs and ids are split on control characters that can't appear in one. */
	private static final char TAB_SEP = '\u001E';
	private static final char ID_SEP = '\u001F';
	/* The Farming tab's Special and All trees groups on the game screen. */
	static final String SPECIAL_PIN = "farming:special";
	static final String TREES_PIN = "farming:trees";

	private Favourites()
	{
	}

	static Map<String, Set<String>> parse(String saved)
	{
		Map<String, Set<String>> out = new LinkedHashMap<>();
		if (saved == null || saved.isEmpty())
		{
			return out;
		}
		for (String line : saved.split(String.valueOf(TAB_SEP)))
		{
			int eq = line.indexOf('=');
			if (eq <= 0)
			{
				continue;
			}
			Set<String> ids = new LinkedHashSet<>();
			for (String id : line.substring(eq + 1).split(String.valueOf(ID_SEP)))
			{
				if (!id.isEmpty())
				{
					ids.add(id);
				}
			}
			out.put(line.substring(0, eq), ids);
		}
		return out;
	}

	static String write(Map<String, Set<String>> m)
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Set<String>> e : m.entrySet())
		{
			if (e.getValue().isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(TAB_SEP);
			}
			sb.append(e.getKey()).append('=').append(String.join(String.valueOf(ID_SEP), e.getValue()));
		}
		return sb.toString();
	}

	static void toggle(Map<String, Set<String>> m, String tab, String id)
	{
		Set<String> ids = m.computeIfAbsent(tab, k -> new LinkedHashSet<>());
		if (!ids.remove(id))
		{
			ids.add(id);
		}
	}

	static void setAll(Map<String, Set<String>> m, String tab, Collection<String> ids)
	{
		m.put(tab, new LinkedHashSet<>(ids));
	}

	static String patchPin(String patchId)
	{
		return "farm:patch:" + patchId;
	}

	static String dailyPin(String id)
	{
		return "daily:" + id;
	}

	static String groupPin(String tab)
	{
		return "fav:" + tab;
	}

	static String regionPin(String region)
	{
		return "farm:region:" + region;
	}

	static String kindPin(String key)
	{
		return "farming:kind:" + key;
	}

	/** An old "farming:Herbs" pin, from before kinds had keys. Check SPECIAL_PIN and TREES_PIN first: they match too. */
	static boolean isLegacyKindPin(String pin)
	{
		return pin.startsWith("farming:") && !pin.startsWith("farming:kind:") && !pin.equals(FarmGroups.CONTRACT_ID);
	}

	/** The rows whose id is a favourite, in the rows' order (what Overview keeps of a tab). */
	static <T> List<T> only(List<T> rows, Function<T, String> id, Set<String> favs)
	{
		List<T> out = new ArrayList<>();
		for (T r : rows)
		{
			if (favs.contains(id.apply(r)))
			{
				out.add(r);
			}
		}
		return out;
	}
}
