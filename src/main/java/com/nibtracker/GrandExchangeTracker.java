package com.nibtracker;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Grand Exchange offers: one event whenever an offer is placed, fills further, completes or is cancelled, and an EMPTY
 * one when its slot is collected and freed, so the web app stops showing the old offer there.
 * The game resends every offer at login; those are sent once, marked initial, so offers that filled while logged out
 * still arrive (the web app ignores ones it has already seen).
 */
@Singleton
class GrandExchangeTracker
{
	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private NibTrackerConfig config;

	private EventSink sink;
	private final Map<Integer, String> lastBySlot = new HashMap<>();
	private final Map<Integer, JsonObject> beforeLogin = new HashMap<>();

	void start(EventSink sink)
	{
		this.sink = sink;
	}

	/** Called once the character is ready: sends the offers the game replayed during login. */
	void login()
	{
		for (JsonObject d : beforeLogin.values())
		{
			d.addProperty("initial", true);
			send(d);
		}
		beforeLogin.clear();
	}

	void reset()
	{
		lastBySlot.clear();
		beforeLogin.clear();
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e)
	{
		GrandExchangeOffer o = e.getOffer();
		if (o == null)
		{
			return;
		}
		GrandExchangeOfferState state = o.getState();
		// While hopping or logging in the client clears every slot before the real offers arrive; those empties aren't
		// real, so only an empty seen while logged in is sent (as RuneLite's own Grand Exchange plugin does).
		if (state == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String key = state + ":" + o.getItemId() + ":" + o.getQuantitySold() + ":" + o.getPrice() + ":" + o.getTotalQuantity();
		if (key.equals(lastBySlot.put(e.getSlot(), key)))
		{
			return;
		}
		JsonObject d = new JsonObject();
		d.addProperty("slot", e.getSlot());
		d.addProperty("itemId", o.getItemId());
		// An empty slot has no item (id 0), so no name: looking 0 up would name a real item.
		d.addProperty("name", state == GrandExchangeOfferState.EMPTY ? "" : itemManager.getItemComposition(o.getItemId()).getName());
		d.addProperty("state", state.name());
		d.addProperty("total", o.getTotalQuantity());
		d.addProperty("filled", o.getQuantitySold());
		d.addProperty("price", o.getPrice());
		d.addProperty("spent", o.getSpent());
		if (sink == null || !sink.active())
		{
			beforeLogin.put(e.getSlot(), d);
			return;
		}
		send(d);
	}

	private void send(JsonObject d)
	{
		if (sink.allowed("ge", config.sendGe()))
		{
			sink.emit("ge_offer", d);
		}
	}
}
