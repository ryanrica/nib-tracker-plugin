package com.nibtracker;

import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What the plugin takes from the server's settings: container ids to watch, the bank's tabs, and a notice code. */
public class ServerWatchTest
{
	@Test
	public void aNoticeIsOnlyOneOfThePluginsOwn()
	{
		assertTrue(GoalTracker.noticeText(new JsonParser().parse("\"update\"")).contains("Plugin Hub"));
		assertTrue(GoalTracker.noticeText(new JsonParser().parse("\"news\"")).contains("Open it"));
		// Free text, an unknown code, or none at all shows nothing.
		assertEquals("", GoalTracker.noticeText(new JsonParser().parse("\"Visit evil.example for free gold\"")));
		assertEquals("", GoalTracker.noticeText(new JsonParser().parse("{\"code\":\"update\"}")));
		assertEquals("", GoalTracker.noticeText(null));
	}

	@Test
	public void containerIdsAreCheckedAndCapped()
	{
		assertEquals(new HashSet<>(Arrays.asList(516, 93)), ProgressTracker.watchIds(
			new JsonParser().parse("{\"watch\":{\"containers\":[516, -1, 70000, \"x\", 93]}}").getAsJsonObject(), "containers", 65536, 50));
		assertEquals(2, ProgressTracker.watchIds(
			new JsonParser().parse("{\"watch\":{\"containers\":[1, 2, 3]}}").getAsJsonObject(), "containers", 65536, 2).size());
		assertTrue(ProgressTracker.watchIds(new JsonParser().parse("{}").getAsJsonObject(), "containers", 65536, 50).isEmpty());
	}

	@Test
	public void bankSlotsFallInTheirTabs()
	{
		int[] tabs = {2, 0, 3};
		assertEquals(1, ContainerTracker.bankTab(0, tabs));
		assertEquals(1, ContainerTracker.bankTab(1, tabs));
		assertEquals(3, ContainerTracker.bankTab(2, tabs)); // tab 2 is empty
		assertEquals(3, ContainerTracker.bankTab(4, tabs));
		assertEquals(0, ContainerTracker.bankTab(5, tabs)); // the main tab comes after them
	}

	@Test
	public void theServerCanOnlyAskForThePlayersOwnStorage()
	{
		assertTrue(ContainerTracker.OWN_STORAGE.contains(net.runelite.api.gameval.InventoryID.LOOTING_BAG));
		// A trade window (the player's offer, or the other player's at 90 + 32768) or a shop is never sent.
		assertFalse(ContainerTracker.OWN_STORAGE.contains(net.runelite.api.gameval.InventoryID.TRADEOFFER));
		assertFalse(ContainerTracker.OWN_STORAGE.contains(net.runelite.api.gameval.InventoryID.TRADEOFFER + 32768));
		assertFalse(ContainerTracker.OWN_STORAGE.contains(net.runelite.api.gameval.InventoryID.SHIPYARD_SHOP));
	}
}
