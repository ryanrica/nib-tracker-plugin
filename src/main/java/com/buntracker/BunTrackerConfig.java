package com.buntracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(BunTrackerConfig.GROUP)
public interface BunTrackerConfig extends Config
{
	String GROUP = "buntracker";
	String WARNING = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers";

	@ConfigItem(
		keyName = "syncEnabled",
		name = "Sync to Bun Tracker",
		description = "Send your own loot, kill counts, collection log slots and levels to your Bun Tracker account",
		warning = WARNING,
		position = 0
	)
	default boolean syncEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pluginKey",
		name = "Plugin key",
		description = "Make one in Bun Tracker: Account > RuneLite plugin > Create plugin key. It starts with btk_",
		secret = true,
		position = 1
	)
	default String pluginKey()
	{
		return "";
	}

	@ConfigSection(
		name = "What to send",
		description = "Turn off anything you don't want synced",
		position = 10
	)
	String sources = "sources";

	@ConfigItem(keyName = "sendLoot", name = "Loot", description = "Drops from monsters, raids, clues and other loot sources", section = sources, position = 11)
	default boolean sendLoot()
	{
		return true;
	}

	@ConfigItem(keyName = "sendKc", name = "Kill counts", description = "Boss, raid and activity kill counts, clue completions and boss times from chat", section = sources, position = 12)
	default boolean sendKc()
	{
		return true;
	}

	@ConfigItem(keyName = "sendClog", name = "Collection log", description = "New collection log slots and pets", section = sources, position = 13)
	default boolean sendClog()
	{
		return true;
	}

	@ConfigItem(keyName = "sendLevels", name = "Levels and XP", description = "Level ups and your skill totals", section = sources, position = 14)
	default boolean sendLevels()
	{
		return true;
	}

	@ConfigItem(keyName = "sendXp", name = "XP gains", description = "XP gained per skill, sent about once a minute while you train", section = sources, position = 15)
	default boolean sendXp()
	{
		return true;
	}

	@ConfigItem(keyName = "sendTasks", name = "Quests, diaries and tasks", description = "Quests, achievement diaries, combat achievements and league tasks and relics", section = sources, position = 16)
	default boolean sendTasks()
	{
		return true;
	}

	@ConfigItem(keyName = "sendSessions", name = "Play sessions and deaths", description = "When you log in and out, and where you die", section = sources, position = 17)
	default boolean sendSessions()
	{
		return true;
	}

	@ConfigItem(keyName = "sendSlayer", name = "Slayer", description = "Your slayer task, its progress, points and streak", section = sources, position = 18)
	default boolean sendSlayer()
	{
		return true;
	}

	@ConfigItem(keyName = "sendBank", name = "Bank and gear", description = "Bank, seed vault, worn equipment and inventory, when they change", section = sources, position = 19)
	default boolean sendBank()
	{
		return true;
	}

	@ConfigItem(keyName = "sendGe", name = "Grand Exchange", description = "Offers placed, filled and cancelled", section = sources, position = 20)
	default boolean sendGe()
	{
		return true;
	}

	@ConfigItem(keyName = "sendFarming", name = "Farming timers", description = "Patches and bird houses, as the Time Tracking plugin records them", section = sources, position = 21)
	default boolean sendFarming()
	{
		return true;
	}

	@ConfigItem(keyName = "sendScreenshots", name = "Bingo screenshots", description = "Capture the game when you get an item on one of your live bingo drop tiles, and attach it as proof", section = sources, position = 22)
	default boolean sendScreenshots()
	{
		return true;
	}
}
