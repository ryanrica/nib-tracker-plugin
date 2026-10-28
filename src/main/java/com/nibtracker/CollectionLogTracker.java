package com.nibtracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Collection log pages, as the player opens them. Each page is sent in Character Export's collection_log shape;
 * the web app merges pages, so the whole log fills in as pages are viewed.
 */
@Singleton
class CollectionLogTracker
{
	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private NibTrackerConfig config;

	private EventSink sink;
	private final Map<String, JsonObject> lastSent = new HashMap<>();

	void start(EventSink sink)
	{
		this.sink = sink;
	}

	void reset()
	{
		lastSent.clear();
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired e)
	{
		if (e.getScriptId() != ScriptID.COLLECTION_DRAW_LIST || sink == null || !sink.active() || !sink.allowed("clog", config.sendClog()))
		{
			return;
		}
		String page = pageTitle();
		Widget list = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (page == null || list == null || list.getDynamicChildren() == null)
		{
			return;
		}
		JsonArray items = new JsonArray();
		for (Widget w : list.getDynamicChildren())
		{
			int id = w.getItemId();
			if (id <= 0)
			{
				continue;
			}
			boolean got = w.getOpacity() == 0; // missing slots are drawn faded
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("name", itemManager.getItemComposition(id).getName());
			o.addProperty("obtained", got);
			o.addProperty("quantity", got ? Math.max(1, w.getItemQuantity()) : 0);
			items.add(o);
		}
		JsonObject entry = new JsonObject();
		entry.add("items", items);
		entry.add("lines", headerLines());
		if (items.size() == 0 || entry.equals(lastSent.get(page)))
		{
			return;
		}
		lastSent.put(page, entry);
		JsonObject pages = new JsonObject();
		pages.add(page, entry);
		JsonObject tabs = new JsonObject();
		tabs.add("Collection log", pages);
		JsonObject d = new JsonObject();
		d.add("tabs", tabs);
		sink.emit("snapshot_collection_log", d);
	}

	/** The page header's lines after its title, as the game shows them ("Obtained: 12/50", "Vardorvis kills: 5"). */
	private JsonArray headerLines()
	{
		JsonArray lines = new JsonArray();
		Widget header = client.getWidget(InterfaceID.Collection.HEADER);
		Widget[] kids = header == null ? null : header.getDynamicChildren();
		for (int i = 1; kids != null && i < kids.length; i++)
		{
			String t = kids[i].getText() == null ? "" : Text.removeTags(kids[i].getText()).trim();
			if (!t.isEmpty())
			{
				lines.add(t);
			}
		}
		return lines;
	}

	private String pageTitle()
	{
		Widget header = client.getWidget(InterfaceID.Collection.HEADER);
		if (header == null)
		{
			return null;
		}
		Widget[] kids = header.getDynamicChildren();
		String t = kids != null && kids.length > 0 ? kids[0].getText() : header.getText();
		t = t == null ? "" : Text.removeTags(t).trim();
		return t.isEmpty() ? null : t;
	}
}
