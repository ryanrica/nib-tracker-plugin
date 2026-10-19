package com.nibtracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Bank, seed vault, worn equipment and inventory as snapshots. Each is sent a little after it stops changing, and only
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

	static
	{
		WATCH.put(InventoryID.BANK, new Watch("snapshot_bank", "bank", 10));
		WATCH.put(InventoryID.SEED_VAULT, new Watch("snapshot_seed_vault", "bank", 10));
		WATCH.put(InventoryID.WORN, new Watch("snapshot_equipment", "gear", 5));
		WATCH.put(InventoryID.INV, new Watch("snapshot_inventory", "gear", 100));
	}

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
		Watch w = WATCH.get(e.getContainerId());
		if (w == null || sink == null || !sink.active() || !sink.allowed(w.source, config.sendBank()))
		{
			return;
		}
		pending.put(e.getContainerId(), items(e.getItemContainer()));
		dueAt.putIfAbsent(e.getContainerId(), ticks + w.waitTicks);
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
			JsonArray items = pending.remove(id);
			if (items != null && !items.equals(lastSent.get(id)) && sink.active())
			{
				lastSent.put(id, items);
				JsonObject d = new JsonObject();
				d.add("items", items);
				sink.emit(WATCH.get(id).type, d);
			}
			return true;
		});
	}

	private JsonArray items(ItemContainer c)
	{
		JsonArray out = new JsonArray();
		if (c == null)
		{
			return out;
		}
		Item[] all = c.getItems();
		for (int slot = 0; slot < all.length; slot++)
		{
			Item it = all[slot];
			if (it.getId() <= 0 || it.getQuantity() <= 0)
			{
				continue; // empty slots and bank placeholders
			}
			int id = itemManager.canonicalize(it.getId());
			JsonObject o = new JsonObject();
			o.addProperty("slot", slot);
			o.addProperty("id", id);
			o.addProperty("name", itemManager.getItemComposition(id).getName());
			o.addProperty("quantity", it.getQuantity());
			out.add(o);
		}
		return out;
	}
}
