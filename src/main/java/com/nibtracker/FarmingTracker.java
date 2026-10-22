package com.nibtracker;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Farming patches and bird houses, as RuneLite's Time Tracking plugin saves them for this character. The entries are
 * sent as saved ("<region>.<varbit>" = "<value>:<unix seconds>", "birdhouse.<n>", "farmTickOffset" ...), so the
 * web app reads them with the parser it already uses for RuneLite profile files. They're also kept in memory, so the
 * Farming tab can work out the patches here (PatchPredictor).
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
	private NibTrackerConfig config;

	private EventSink sink;
	private JsonObject lastSent;
	private int ticks;
	/* The last read, for this character; replaced whole, so any thread can read it. */
	private volatile Map<String, String> entries = Collections.emptyMap();

	void start(EventSink sink)
	{
		this.sink = sink;
	}

	void login()
	{
		read();
		ticks = EVERY_TICKS - 20; // first send shortly after login
	}

	void reset()
	{
		lastSent = null;
		entries = Collections.emptyMap();
	}

	void tick()
	{
		if (++ticks >= EVERY_TICKS)
		{
			ticks = 0;
			send();
		}
	}

	/** The item id of the Farming Guild contract you hold, from Time Tracking; 0 when none. */
	int contractItemId()
	{
		String value = configManager.getRSProfileConfiguration(GROUP, "contract");
		if (value == null)
		{
			return 0;
		}
		try
		{
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/** Also called at logout, so timers planted just before logging out are sent. */
	void send()
	{
		read();
		if (sink == null || !sink.active() || !sink.allowed("farming", config.sendFarming()))
		{
			return;
		}
		JsonObject sent = new JsonObject();
		for (Map.Entry<String, String> e : entries.entrySet())
		{
			sent.addProperty(e.getKey(), e.getValue());
		}
		if (sent.size() == 0 || sent.equals(lastSent))
		{
			return;
		}
		lastSent = sent;
		JsonObject d = new JsonObject();
		d.add("entries", sent);
		sink.emit("snapshot_timetracking", d);
	}

	/** Time Tracking's saved entries for this character as last read: key without the profile -> value. */
	Map<String, String> entries()
	{
		return entries;
	}

	/** Any thread. Reads Time Tracking's entries again (ConfigManager holds them in memory, no disk read). */
	void read()
	{
		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			entries = Collections.emptyMap();
			return;
		}
		Map<String, String> m = new LinkedHashMap<>();
		for (String key : configManager.getRSProfileConfigurationKeys(GROUP, profile, ""))
		{
			if (m.size() >= MAX_ENTRIES)
			{
				break;
			}
			String value = configManager.getRSProfileConfiguration(GROUP, key);
			if (value != null)
			{
				m.put(key, value);
			}
		}
		entries = Collections.unmodifiableMap(m);
	}
}
