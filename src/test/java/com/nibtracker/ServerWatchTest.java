package com.nibtracker;

import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What the plugin takes from the server's settings to watch: chat fragments, container ids, and the bank's tabs. */
public class ServerWatchTest
{
	@Test
	public void chatFragmentsMatchAsPlainTextIgnoringCase()
	{
		assertTrue(ChatParser.watched("You feel a strange Presence nearby.", new JsonParser().parse("[\"strange presence\"]")));
		// Regex characters are plain text: ".*" matches nothing it doesn't literally contain.
		assertFalse(ChatParser.watched("Anything at all", new JsonParser().parse("[\".*\", \"(a+)+$\"]")));
		// Too short, not a string, or no list: nothing matches.
		assertFalse(ChatParser.watched("You feel a strange presence.", new JsonParser().parse("[\"a\", 5, null]")));
		assertFalse(ChatParser.watched("You feel a strange presence.", null));
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
}
