package com.nibtracker;

import net.runelite.api.Item;
import org.junit.Test;
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
}
