package com.nibtracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Bank (with each item's tab), seed vault, potion storage, worn equipment, inventory and any container the server
 * watches, as snapshots. Each is sent a little after it stops changing, and only
 * when it differs from the last one sent.
 */
@Singleton
class ContainerTracker
{
	private static final class Watch
	{
		final String type;
		final String source;
		final int waitTicks;

		Watch(String type, String source, int waitTicks)
		{
			this.type = type;
			this.source = source;
			this.waitTicks = waitTicks;
		}
	}

	private static final Map<Integer, Watch> WATCH = new HashMap<>();
	// Potion storage is not an item container: it is read from the client when due, as RuneLite's bank tags do.
	private static final int POTION_STORAGE = -1;

	static
	{
		WATCH.put(InventoryID.BANK, new Watch("snapshot_bank", "bank", 10));
		WATCH.put(InventoryID.SEED_VAULT, new Watch("snapshot_seed_vault", "bank", 10));
		WATCH.put(POTION_STORAGE, new Watch("snapshot_potion_storage", "bank", 10));
		WATCH.put(InventoryID.WORN, new Watch("snapshot_equipment", "gear", 5));
		WATCH.put(InventoryID.INV, new Watch("snapshot_inventory", "gear", 100));
	}

	/* Another container the server lists under watch.containers, with its id: only these, the player's own storage. A
	   trade partner's offer, a shop or anyone else's items can never be asked for. */
	private static final Watch SERVER_WATCH = new Watch("snapshot_container", "bank", 10);
	static final Set<Integer> OWN_STORAGE = new HashSet<>(Arrays.asList(InventoryID.LOOTING_BAG, InventoryID.SEED_BOX,
		InventoryID.TACKLE_BOX, InventoryID.HUNTSMANS_KIT, InventoryID.INV_GROUP_TEMP, InventoryID.SAILING_BOAT_1_CARGOHOLD,
		InventoryID.SAILING_BOAT_2_CARGOHOLD, InventoryID.SAILING_BOAT_3_CARGOHOLD, InventoryID.SAILING_BOAT_4_CARGOHOLD,
		InventoryID.SAILING_BOAT_5_CARGOHOLD));
	private static final int[] BANK_TABS = {VarbitID.BANK_TAB_1, VarbitID.BANK_TAB_2, VarbitID.BANK_TAB_3, VarbitID.BANK_TAB_4,
		VarbitID.BANK_TAB_5, VarbitID.BANK_TAB_6, VarbitID.BANK_TAB_7, VarbitID.BANK_TAB_8, VarbitID.BANK_TAB_9};

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private NibTrackerConfig config;

	private EventSink sink;
	private final Map<Integer, JsonArray> pending = new HashMap<>();
	private final Map<Integer, Integer> dueAt = new HashMap<>();
	private final Map<Integer, JsonArray> lastSent = new HashMap<>();
	private int ticks;

	void start(EventSink sink)
	{
		this.sink = sink;
	}

	void reset()
	{
		pending.clear();
		dueAt.clear();
		lastSent.clear();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		Watch w = watch(e.getContainerId());
		if (w == null || sink == null || !sink.active() || !sink.allowed(w.source, config.sendBank()))
		{
			return;
		}
		pending.put(e.getContainerId(), items(e.getContainerId(), e.getItemContainer()));
		schedule(e.getContainerId());
		if (e.getContainerId() == InventoryID.BANK)
		{
			schedule(POTION_STORAGE);
		}
	}

	/** Storing or taking out potions changes varps, not the bank. */
	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (sink != null && sink.active() && sink.allowed("bank", config.sendBank())
			&& client.getWidget(InterfaceID.Bankmain.UNIVERSE) != null)
		{
			schedule(POTION_STORAGE);
		}
	}

	private void schedule(int id)
	{
		dueAt.putIfAbsent(id, ticks + watch(id).waitTicks);
	}

	/** How a container is sent: one of the fixed ones, one the server asks for, or null. */
	private Watch watch(int id)
	{
		Watch w = WATCH.get(id);
		if (w != null || sink == null)
		{
			return w;
		}
		return OWN_STORAGE.contains(id) && ProgressTracker.watchIds(sink.settings(), "containers", 65536, 50).contains(id) ? SERVER_WATCH : null;
	}

	void tick()
	{
		ticks++;
		if (dueAt.isEmpty())
		{
			return;
		}
		dueAt.entrySet().removeIf(due -> {
			if (ticks < due.getValue())
			{
				return false;
			}
			int id = due.getKey();
			JsonArray items = id == POTION_STORAGE ? potions() : pending.remove(id);
			Watch w = watch(id);
			if (w != null && items != null && !items.equals(lastSent.get(id)) && sink.active())
			{
				lastSent.put(id, items);
				JsonObject d = new JsonObject();
				if (w == SERVER_WATCH)
				{
					d.addProperty("container", id);
				}
				d.add("items", items);
				sink.emit(w.type, d);
			}
			return true;
		});
	}

	private JsonArray items(int containerId, ItemContainer c)
	{
		JsonArray out = new JsonArray();
		if (c == null)
		{
			return out;
		}
		// The bank's tabs are runs of slots, tab 1 first; the slots after them are the main tab (0).
		int[] tabSizes = new int[containerId == InventoryID.BANK ? BANK_TABS.length : 0];
		for (int i = 0; i < tabSizes.length; i++)
		{
			tabSizes[i] = client.getVarbitValue(BANK_TABS[i]);
		}
		Item[] all = c.getItems();
		for (int slot = 0; slot < all.length; slot++)
		{
			Item it = all[slot];
			if (!owned(it, i -> itemManager.getItemComposition(i).getPlaceholderTemplateId()))
			{
				continue;
			}
			int id = itemManager.canonicalize(it.getId());
			JsonObject o = new JsonObject();
			o.addProperty("slot", slot);
			o.addProperty("id", id);
			o.addProperty("name", itemManager.getItemComposition(id).getName());
			o.addProperty("quantity", it.getQuantity());
			if (tabSizes.length > 0)
			{
				o.addProperty("tab", bankTab(slot, tabSizes));
			}
			out.add(o);
		}
		return out;
	}

	/** Potion storage as {id, name, quantity}: the full-dose potion, and how many of them its doses make, rounded up. */
	private JsonArray potions()
	{
		JsonArray out = new JsonArray();
		for (int list : new int[]{EnumID.POTIONSTORE_POTIONS, EnumID.POTIONSTORE_UNFINISHED_POTIONS})
		{
			for (int potion : client.getEnum(list).getIntVals())
			{
				client.runScript(ScriptID.POTIONSTORE_DOSES, potion);
				int doses = client.getIntStack()[0];
				if (doses <= 0)
				{
					continue;
				}
				// The potion's enum maps dose -> item; the highest dose it has is the full potion.
				EnumComposition byDose = client.getEnum(potion);
				int max = 4;
				while (max > 1 && byDose.getIntValue(max) <= 0)
				{
					max--;
				}
				int id = byDose.getIntValue(max);
				JsonObject o = new JsonObject();
				o.addProperty("id", id);
				o.addProperty("name", itemManager.getItemComposition(id).getName());
				o.addProperty("quantity", (doses + max - 1) / max);
				out.add(o);
			}
		}
		return out;
	}

	/** The bank tab a slot is in, from each tab's size (tabs 1 to 9 in order): 0 for the main tab after them. */
	static int bankTab(int slot, int[] tabSizes)
	{
		int end = 0;
		for (int i = 0; i < tabSizes.length; i++)
		{
			end += Math.max(0, tabSizes[i]);
			if (slot < end)
			{
				return i + 1;
			}
		}
		return 0;
	}

	/** A real item: not an empty slot, and not a bank placeholder (shown as 0), so canonicalize can't make it owned. */
	static boolean owned(Item it, IntUnaryOperator placeholderTemplate)
	{
		return it.getId() > 0 && it.getQuantity() > 0 && placeholderTemplate.applyAsInt(it.getId()) == -1;
	}
}
