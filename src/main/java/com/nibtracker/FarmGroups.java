package com.nibtracker;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

/** The Farming tab's groups (Special, All trees) and the Farming Guild contract. Pure, so it can be tested. */
final class FarmGroups
{
	static final Set<String> SPECIAL = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"seaweed", "mushroom", "calquat", "belladonna", "anima", "hespori", "cactus")));
	static final Set<String> TREES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"tree", "hardwood", "redwood")));
	static final String CONTRACT_ID = "farming:contract";

	private static final String GUILD = "Farming Guild";
	private static final Map<Integer, Contract> CONTRACTS = new HashMap<>();

	static final class Contract
	{
		final String crop;
		final String kind;
		final int itemId;

		Contract(String crop, String kind, int itemId)
		{
			this.crop = crop;
			this.kind = kind;
			this.itemId = itemId;
		}
	}

	static final class ContractView
	{
		String title = "";
		String value = "";
		String sub = "";
		boolean ready;
		boolean attention;
		int itemId;
		double progress = -1;
	}

	static
	{
		add("allotment", "Potatoes", ItemID.POTATO);
		add("allotment", "Onions", ItemID.ONION);
		add("allotment", "Cabbages", ItemID.CABBAGE);
		add("allotment", "Tomatoes", ItemID.TOMATO);
		add("allotment", "Sweetcorn", ItemID.SWEETCORN);
		add("allotment", "Strawberries", ItemID.STRAWBERRY);
		add("allotment", "Watermelons", ItemID.WATERMELON);
		add("allotment", "Snape grass", ItemID.SNAPE_GRASS);

		add("flower", "Marigold", ItemID.MARIGOLD);
		add("flower", "Rosemary", ItemID.ROSEMARY);
		add("flower", "Nasturtium", ItemID.NASTURTIUM);
		add("flower", "Woad", ItemID.WOADLEAF);
		add("flower", "Limpwurt", ItemID.LIMPWURT_ROOT);
		add("flower", "White lily", ItemID.WHITELILLY);

		add("bush", "Redberries", ItemID.REDBERRIES);
		add("bush", "Dwellberries", ItemID.DWELLBERRIES);
		add("bush", "Jangerberries", ItemID.JANGERBERRIES);
		add("bush", "Cadavaberries", ItemID.CADAVABERRIES);
		add("bush", "Whiteberries", ItemID.WHITE_BERRIES);
		add("bush", "Poison ivy", ItemID.POISONIVY_BERRIES);

		add("herb", "Guam", ItemID.GUAM_LEAF);
		add("herb", "Marrentill", ItemID.MARENTILL);
		add("herb", "Tarromin", ItemID.TARROMIN);
		add("herb", "Harralander", ItemID.HARRALANDER);
		add("herb", "Ranarr", ItemID.RANARR_WEED);
		add("herb", "Toadflax", ItemID.TOADFLAX);
		add("herb", "Irit", ItemID.IRIT_LEAF);
		add("herb", "Avantoe", ItemID.AVANTOE);
		add("herb", "Kwuarm", ItemID.KWUARM);
		add("herb", "Snapdragon", ItemID.SNAPDRAGON);
		add("herb", "Cadantine", ItemID.CADANTINE);
		add("herb", "Lantadyme", ItemID.LANTADYME);
		add("herb", "Dwarf weed", ItemID.DWARF_WEED);
		add("herb", "Torstol", ItemID.TORSTOL);

		add("cactus", "Cactus", ItemID.CACTUS_SPINE);
		add("cactus", "Potato cactus", ItemID.CACTUS_POTATO);

		add("tree", "Oak", ItemID.OAK_LOGS);
		add("tree", "Willow", ItemID.WILLOW_LOGS);
		add("tree", "Maple", ItemID.MAPLE_LOGS);
		add("tree", "Yew", ItemID.YEW_LOGS);
		add("tree", "Magic", ItemID.MAGIC_LOGS);

		add("fruit_tree", "Apple", ItemID.COOKING_APPLE);
		add("fruit_tree", "Banana", ItemID.BANANA);
		add("fruit_tree", "Orange", ItemID.ORANGE);
		add("fruit_tree", "Curry", ItemID.CURRY_LEAF);
		add("fruit_tree", "Pineapple", ItemID.PINEAPPLE);
		add("fruit_tree", "Papaya", ItemID.PAPAYA);
		add("fruit_tree", "Palm", ItemID.COCONUT);
		add("fruit_tree", "Dragonfruit", ItemID.DRAGONFRUIT);

		add("celastrus", "Celastrus", ItemID.BATTLESTAFF);
		add("redwood", "Redwood", ItemID.REDWOOD_LOGS);
	}

	private FarmGroups()
	{
	}

	private static void add(String kind, String crop, int itemId)
	{
		CONTRACTS.put(itemId, new Contract(crop, kind, itemId));
	}

	static boolean special(String kind)
	{
		return SPECIAL.contains(kind);
	}

	static boolean tree(String kind)
	{
		return TREES.contains(kind);
	}

	/** The contract for this produce item id, or null when it isn't a contract crop. */
	static Contract contract(int itemId)
	{
		return CONTRACTS.get(itemId);
	}

	private static boolean sameCrop(String patchCrop, String contractCrop)
	{
		return stem(patchCrop).equals(stem(contractCrop));
	}

	private static String stem(String s)
	{
		String t = s == null ? "" : s.trim().toLowerCase();
		if (t.endsWith("ies"))
		{
			return t.substring(0, t.length() - 3) + "y";
		}
		if (t.endsWith("oes"))
		{
			return t.substring(0, t.length() - 2);
		}
		if (t.endsWith("s") && !t.endsWith("ss"))
		{
			return t.substring(0, t.length() - 1);
		}
		return t;
	}

	static ContractView contractView(Contract c, List<Routines.Patch> farm, long now)
	{
		ContractView v = new ContractView();
		if (c == null)
		{
			v.title = "Contract";
			v.value = "No contract · talk to Jane";
			return v;
		}
		v.title = "Contract: " + c.crop + " · Farming Guild " + c.kind.replace('_', ' ') + " patch";
		v.itemId = c.itemId;

		Routines.Patch p = null;
		for (Routines.Patch q : farm)
		{
			if (GUILD.equals(q.region) && c.kind.equals(q.kind))
			{
				p = q;
				break;
			}
		}
		if (p == null || "empty".equals(p.state))
		{
			v.value = "Not planted";
			v.sub = "Harvested? See Jane for a new contract";
			return v;
		}
		if (!sameCrop(p.crop, c.crop))
		{
			v.value = "Other crop planted";
			return v;
		}
		v.progress = p.progress;
		if ("diseased".equals(p.state))
		{
			v.attention = true;
			v.value = "Diseased";
		}
		else if ("dead".equals(p.state))
		{
			v.attention = true;
			v.value = "Dead";
		}
		else if (p.readyAt(now) || "ready".equals(p.state))
		{
			v.ready = true;
			v.value = "Ready · then see Jane for a new contract";
		}
		else if (p.readyAt > now)
		{
			v.value = Routines.left(p.readyAt - now);
		}
		else
		{
			v.value = "Growing";
		}
		return v;
	}
}
