package com.buntracker;

import com.google.gson.JsonObject;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Farming patches and bird houses, as RuneLite's Time Tracking plugin saves them for this character. The entries are
 * sent as saved ("<region>.<varbit>" = "<value>:<unix seconds>", "birdhouse.<n>", "farmTickOffset" ...), so the
 * web app reads them with the parser it already uses for RuneLite profile files.
 */
@Singleton
class FarmingTracker
{
	private static final String GROUP = "timetracking";
	private static final int EVERY_TICKS = 500; // about 5 minutes
	private static final int MAX_ENTRIES = 1000;

	@Inject
	private ConfigManager configManager;

	@Inject
	private BunTrackerConfig config;

	private EventSink sink;
	private JsonObject lastSent;
	private int ticks;

	void start(EventSink sink)
	{
		this.sink = sink;
	}

	void login()
	{
		ticks = EVERY_TICKS - 20; // first read shortly after login
	}

	void reset()
	{
		lastSent = null;
	}

	void tick()
	{
		if (++ticks >= EVERY_TICKS)
		{
			ticks = 0;
			send();
		}
	}

	/** Also called at logout, so timers planted just before logging out are sent. */
	void send()
	{
		if (sink == null || !sink.active() || !sink.allowed("farming", config.sendFarming()))
		{
			return;
		}
		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return;
		}
		List<String> keys = configManager.getRSProfileConfigurationKeys(GROUP, profile, "");
		JsonObject entries = new JsonObject();
		for (String key : keys)
		{
			if (entries.size() >= MAX_ENTRIES)
			{
				break;
			}
			String value = configManager.getRSProfileConfiguration(GROUP, key);
			if (value != null)
			{
				entries.addProperty(key, value);
			}
		}
		if (entries.size() == 0 || entries.equals(lastSent))
		{
			return;
		}
		lastSent = entries;
		JsonObject d = new JsonObject();
		d.add("entries", entries);
		sink.emit("snapshot_timetracking", d);
	}
}
