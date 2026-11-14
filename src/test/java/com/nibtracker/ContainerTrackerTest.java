package com.nibtracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Item;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContainerTrackerTest
{
	private static final int PLACEHOLDER = 9999;

	private static boolean owned(int id, int quantity)
	{
		return ContainerTracker.owned(new Item(id, quantity), i -> i == PLACEHOLDER ? 14401 : -1);
	}

	@Test
	public void realItemsCount()
	{
		assertTrue(owned(4151, 1));
	}

	@Test
	public void placeholdersAndEmptySlotsDont()
	{
		assertFalse(owned(PLACEHOLDER, 0));
		assertFalse(owned(PLACEHOLDER, 1)); // a placeholder id never counts, whatever its quantity
		assertFalse(owned(4151, 0));
		assertFalse(owned(-1, 1));
	}

	/** A tracker whose sends land in {@code sent}, or go nowhere (sync off, no key) while {@code on[0]} is false. */
	private static ContainerTracker tracker(List<String> sent, boolean[] on)
	{
		ContainerTracker t = new ContainerTracker();
		t.start(new EventSink()
		{
			public String emit(String type, JsonObject data)
			{
				if (!on[0])
				{
					return null;
				}
				sent.add(type);
				return "id";
			}

			public boolean allowed(String serverKey, boolean local)
			{
				return true;
			}

			public JsonObject settings()
			{
				return new JsonObject();
			}

			public boolean active()
			{
				return true;
			}
		});
		return t;
	}

	@Test
	public void loggingOutSendsTheWaitingInventoryOnce()
	{
		List<String> sent = new ArrayList<>();
		ContainerTracker t = tracker(sent, new boolean[]{true});
		JsonArray whip = new JsonArray();
		whip.add(4151);
		t.queue(InventoryID.INV, whip);
		t.tick(); // the inventory waits about a minute while playing
		assertTrue(sent.isEmpty());
		t.flush(); // logging out
		assertEquals(1, sent.size());
		assertEquals("snapshot_inventory", sent.get(0));
		t.flush();
		t.queue(InventoryID.INV, whip); // the same items again
		t.flush();
		assertEquals(1, sent.size());
	}

	@Test
	public void gearChangedWhileSyncWasOffGoesOnceItsOn()
	{
		List<String> sent = new ArrayList<>();
		boolean[] on = {false};
		ContainerTracker t = tracker(sent, on);
		JsonArray noAxe = new JsonArray();
		noAxe.add(4151);
		t.queue(InventoryID.WORN, noAxe); // took the axe off before pasting the key
		t.flush();
		assertTrue(sent.isEmpty());
		on[0] = true;
		t.queue(InventoryID.WORN, noAxe); // the same gear again, once sync is on (what resend() queues)
		t.flush();
		assertEquals(1, sent.size());
		assertEquals("snapshot_equipment", sent.get(0));
	}
}
