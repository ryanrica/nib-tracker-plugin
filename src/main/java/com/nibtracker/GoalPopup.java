package com.nibtracker;

import java.util.ArrayDeque;
import java.util.Deque;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WidgetNode;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.client.callback.ClientThread;

/**
 * The game's own notification pop-up (the one used for new collection log slots), shown for completed goals. One at a
 * time; the rest wait their turn. Client thread only.
 */
@Singleton
class GoalPopup
{
	/* The game's notification_display_init script (title, text, colour), as the collection log pop-up runs it. */
	private static final int NOTIFICATION_DISPLAY_INIT = 3343;
	private static final int MAX_WAITING = 10;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	private final Deque<String[]> waiting = new ArrayDeque<>();
	private boolean showing;

	void show(String title, String text)
	{
		if (waiting.size() < MAX_WAITING)
		{
			waiting.addLast(new String[]{title, text});
		}
		next();
	}

	void reset()
	{
		waiting.clear();
	}

	private void next()
	{
		if (showing || waiting.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		int parent = parentComponent();
		if (parent == -1)
		{
			waiting.clear();
			return;
		}
		String[] p = waiting.pollFirst();
		showing = true;
		WidgetNode node = client.openInterface(parent, InterfaceID.NOTIFICATION_DISPLAY, WidgetModalMode.MODAL_CLICKTHROUGH);
		client.runScript(NOTIFICATION_DISPLAY_INIT, p[0], p[1], -1);
		// The game hides the pop-up when it's finished with it; close the interface then and show the next one.
		clientThread.invokeLater(() -> {
			Widget w = client.getWidget(InterfaceID.NotificationDisplay.CONTAINER);
			if (w != null && w.getWidth() > 0 && client.getGameState() == GameState.LOGGED_IN)
			{
				return false;
			}
			client.closeInterface(node, true);
			showing = false;
			next();
			return true;
		});
	}

	/** Where the pop-up goes in the current window mode (fixed, resizable, or resizable with the modern layout). */
	private int parentComponent()
	{
		switch (client.getTopLevelInterfaceId())
		{
			case InterfaceID.TOPLEVEL:
				return InterfaceID.Toplevel.NOTIFICATIONS;
			case InterfaceID.TOPLEVEL_OSRS_STRETCH:
				return InterfaceID.ToplevelOsrsStretch.NOTIFICATIONS;
			case InterfaceID.TOPLEVEL_PRE_EOC:
				return InterfaceID.ToplevelPreEoc.NOTIFICATIONS;
			default:
				return -1;
		}
	}
}
