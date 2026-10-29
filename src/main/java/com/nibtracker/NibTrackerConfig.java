package com.nibtracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(NibTrackerConfig.GROUP)
public interface NibTrackerConfig extends Config
{
	String GROUP = "nibtracker";
	String WARNING = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers";

	@ConfigItem(
		keyName = "syncEnabled",
		name = "Sync to Nib Tracker",
		description = "Send your own loot, kill counts, collection log slots and levels to your Nib Tracker account",
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
		description = "Make one in Nib Tracker: Account > RuneLite plugin > Create plugin key. It starts with btk_",
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

	@ConfigItem(keyName = "sendBank", name = "Bank and gear", description = "Bank (with its tabs), seed vault, potion storage, worn equipment, inventory and other storage of yours Nib Tracker asks for, when they change", section = sources, position = 19)
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

	@ConfigSection(
		name = "Goals",
		description = "Your Nib Tracker goals, in the Goals side panel",
		position = 30
	)
	String goals = "goals";

	@ConfigItem(keyName = "goalPopups", name = "Completion pop-up", description = "Show the game's pop-up (like a new collection log slot) when one of your goals completes", section = goals, position = 31)
	default boolean goalPopups()
	{
		return true;
	}

	@ConfigItem(keyName = "goalChat", name = "Completion chat message", description = "Say in chat when one of your goals completes, with the points it earns", section = goals, position = 32)
	default boolean goalChat()
	{
		return true;
	}

	enum OverlaySize
	{
		SMALL(20), MEDIUM(26), LARGE(34);

		final int px;

		OverlaySize(int px)
		{
			this.px = px;
		}
	}

	enum OverlayStyle
	{
		/** A card: each entry with its icon, name, value, a progress bar and a line of detail (the next teleport, the task). */
		DETAILED,
		/** Small boxes: icon and value only, details on hover. */
		COMPACT
	}

	@ConfigItem(keyName = "overlayStyle", name = "On-screen style", description = "Detailed shows each goal's name, progress bar and next step; Compact shows just the icon and number (hover for details)", section = goals, position = 33)
	default OverlayStyle overlayStyle()
	{
		return OverlayStyle.DETAILED;
	}

	@ConfigItem(keyName = "overlaySize", name = "On-screen goal size", description = "How big goals you show on the game screen are (right-click a goal in the Goals panel to show it). Hold Alt to move them.", section = goals, position = 34)
	default OverlaySize overlaySize()
	{
		return OverlaySize.SMALL;
	}

	@ConfigItem(keyName = "overlayFavouriteTitles", name = "Favourites titles on screen", description = "Show the title line (\"★ Farming favourites · 6 ready\") above each favourites box you put on the game screen", section = goals, position = 35)
	default boolean overlayFavouriteTitles()
	{
		return true;
	}

	@ConfigItem(keyName = "dailiesPanel", name = "Daily tasks in the panel", description = "List the daily tasks you've switched on in Nib Tracker, and tick them off from the Goals panel", section = goals, position = 36)
	default boolean dailiesPanel()
	{
		return true;
	}

	@ConfigItem(keyName = "farmingPanel", name = "Farming in the panel", description = "List your farm patches and bird houses by region, with time until they're ready (from RuneLite's Time Tracking, through Nib Tracker)", section = goals, position = 37)
	default boolean farmingPanel()
	{
		return true;
	}

	enum FarmFavourites
	{
		RUN_ORDER("In run order"),
		BY_KIND("By kind of patch");

		private final String label;

		FarmFavourites(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	// Unreleased under "farmFavouritesView" (which saved its first default, by kind); a new key so run order is the default.
	@ConfigItem(keyName = "farmFavouritesOrder", name = "Farming favourites", description = "How Overview lists your favourite patches: by kind of patch (herbs, trees...), or in run order, one stop per place with the teleport to get there", section = goals, position = 38)
	default FarmFavourites farmFavouritesView()
	{
		return FarmFavourites.RUN_ORDER;
	}

	@ConfigItem(keyName = "excludedTeleports", name = "Teleports to skip", description = "Teleports the farm run shouldn't use, separated by commas (book of the dead, say, when it's out of charges). Capitals, spacing and apostrophes don't matter. Each stop uses its next best way there instead.", section = goals, position = 39)
	default String excludedTeleports()
	{
		return "";
	}

	/* Farming regions hidden in the panel. Set from the Farming section's right-click menu. */
	String HIDDEN_FARM_REGIONS = "hiddenFarmRegions";

	@ConfigItem(keyName = HIDDEN_FARM_REGIONS, name = "", description = "", hidden = true)
	default String hiddenFarmRegions()
	{
		return "";
	}

	/* The goals shown on the game screen, by id. Set from the Goals panel's right-click menu. */
	String PINNED_GOALS = "pinnedGoals";

	@ConfigItem(keyName = PINNED_GOALS, name = "", description = "", hidden = true)
	default String pinnedGoals()
	{
		return "";
	}

	/* Overview's sections in the player's order (names, comma separated), dragged in the panel. See GoalLayout.overviewOrder. */
	String OVERVIEW_ORDER = "overviewOrder";

	@ConfigItem(keyName = OVERVIEW_ORDER, name = "", description = "", hidden = true)
	default String overviewOrder()
	{
		return "";
	}

	/* Whether Overview's tip on reordering its sections has been hidden (its Hide link). */
	String OVERVIEW_TIP_HIDDEN = "overviewTipHidden";

	@ConfigItem(keyName = OVERVIEW_TIP_HIDDEN, name = "", description = "", hidden = true)
	default boolean overviewTipHidden()
	{
		return false;
	}

	/* Favourites per tab, saved by Favourites.write. */
	String FAVOURITES = "favourites";

	@ConfigItem(keyName = FAVOURITES, name = "", description = "", hidden = true)
	default String favourites()
	{
		return "";
	}

	@ConfigItem(
		keyName = "sendScreenshots",
		name = "Bingo screenshots",
		description = "When you get an item on one of your live bingo drop tiles, capture the game screen (everything visible, including chat) and send it to Nib Tracker as proof for your group",
		warning = WARNING,
		section = sources,
		position = 22
	)
	default boolean sendScreenshots()
	{
		return false;
	}
}
