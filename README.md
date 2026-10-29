# Nib Tracker

[![Build](https://github.com/ryanrica/nib-tracker-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/ryanrica/nib-tracker-plugin/actions/workflows/build.yml)

Syncs your own Old School RuneScape progress to your [Nib Tracker](https://buntracker.pages.dev) planner, so goals,
points, slayer, farming, pet luck and bingo tiles update without typing anything in.

## Setup

1. In Nib Tracker, open **Account > RuneLite plugin** and click **Create plugin key**. One key covers all your characters.
2. In RuneLite, open the Nib Tracker plugin settings, paste the key into **Plugin key**, and turn on **Sync to Nib Tracker**.
3. Log in. You'll see "Nib Tracker is syncing <name>." in chat.

League and Deadman profiles are kept apart from your main: the first time you log in to one, Nib Tracker asks which
planner character it should sync into (or makes a new one).

## Goals in game

Click the **Nib Tracker goals** button (the Nib Tracker logo) on RuneLite's sidebar to see the goals you set in Nib
Tracker for the character you're logged in on, grouped into skills, quests, bosses, collection log, items, achievement
diaries and combat achievements (click a heading to fold it away). Each goal shows how far along it is, with a gold
bar that turns green when it's done, and what's left ("88,113 XP left", "288 kills left"). Hover over a goal, or reach
it with Tab, to see its ★ and ▣; Enter opens it and Shift+F10 opens its menu.

Goals made of parts are folded into one row: "Base 80 in every skill" shows your overall progress and how many skills
are done, and "Show parts" in its row opens each skill under it (the same for a quest cape and its quests). Long sections show the first
few goals with "Show more", and finished goals move to a Completed section at the bottom.

A Slayer level or XP goal shows your current task under it: the monster, kills left and a bar for how much of the task
is done.

Below your goals, **Daily tasks** lists the daily tasks you've switched on in Nib Tracker (due ones first, then how long
until each resets); right-click one to mark it done. **Farming** lists your patches and bird houses by region, soonest
first, with a timer for each; click a region to open it, and right-click to choose which regions to show. Both can be
folded away, or turned off under **Goals** in the plugin settings. Farm timers come from RuneLite's Time Tracking plugin
through Nib Tracker, so new plantings show up once the Nib Tracker site has caught up.

The icons along the top of the panel switch between the **Overview** (everything) and one kind at a time: skills,
quests and achievement diaries (one tab, each under its own heading), bingo, farming, combat achievements, collection log and daily tasks. Each has its
own filters: combat achievements and diaries by tier, quests by whether you've started them, daily tasks by due or done,
and farming by kind of patch (herbs, trees, fruit trees, Hespori and every other kind you have a timer on), ready ones
first, each saying where it is and the teleport to use. **Run order** lists your favourite patches as one farm run over
every kind at once: a card per place, in the order Nib Tracker's farm run planner works out with your bank, with the
teleport to use there and what's ready (a favourite Farming Guild contract sits at the bottom of the Farming Guild's
card). Under **Goals** in the plugin settings, **Farming favourites** shows Overview's favourite patches in run order
(the default) or by kind, and **Teleports to skip** (Book of the dead, say, when it's out of charges) makes each stop use
its next best teleport instead.

Overview's sections start as Farming, Daily tasks, Combat achievements, Quests, Collection log, Skills, then the rest.
Drag a section's heading onto another to move it there, or right-click a heading for Move up, Move down and Reset order
(a tip at the top of Overview says so until you hide it). Each heading has the same icon as its tab.

The **Bingo** tab shows each live board (3x3 to 5x5) from your Nib Tracker groups as a small grid of its tiles' pictures: done tiles in gold with a tick, the rest dimmed. Hover a tile for what it asks; click to open that board in Nib Tracker. With no live board, it links to Bingo on the site.

To keep an eye on a goal while you play, right-click it in the panel and choose **Show on game screen**. Daily tasks,
farming, or one kind of patch (just herbs, say) can go on screen the same way. The on-screen card shows each with its
icon, name, value ("82/83", "2 ready"), a progress bar and a line on what's next (the XP left, a combat achievement's
task, the herb run's teleports in order); hover for everything. Choose **Compact** under **Goals** in the plugin settings
for just icons and numbers, and turn off **Favourites titles on screen** to drop the title line above favourites boxes. Finished goals, and daily tasks once they're all done, leave the screen by themselves, so you
can leave it on. Hold Alt to move it, and pick the size in the settings too. Right-click again to take something off.

Finished daily tasks get the same pop-up and chat message as goals.

Click a goal to see more:

- **Skill and XP goals** open that skill's setup page in Nib Tracker. Slayer opens the gear plan for your current task's
  monster (or the Slayer page if there's no task), and Farming opens the Farm page.
- **Kill counts, items and collection log slots** open the boss plan for the boss that drops them; an item no boss
  drops opens its OSRS Wiki page.
- **Combat achievements** open the boss plan, or the task's wiki page when it isn't for a boss the planner covers.

Each of those pages in Nib Tracker has a section listing your goals for it, with what to do and how far along you are.
Goals the plugin can't measure itself show a line from Nib Tracker on what they are (a combat achievement's task, say).
- **Quests** open the wiki quick guide, and **achievement diaries** the wiki diary page.

If Nib Tracker is already open in your browser, that tab switches to the page instead of a new one staying open.
Skill, XP, base level, kill count, collection log, quest and item goals update as you play; other goals show the
progress Nib Tracker last worked out.

When a goal completes, the game's pop-up (the one new collection log slots use) says so, and a chat line says how many
points it earns. Turn either off under **Goals** in the plugin settings. New goals show up a few seconds after Nib Tracker
saves them, or within a couple of minutes if you're idle.

The goals come from Nib Tracker as plain data (goal type and parameters). The plugin works out progress with its own
code for the goal types it knows; nothing it receives is run.

## What is sent

Only your own character's data, and only while **Sync to Nib Tracker** is on. Each group can be turned off under
**What to send**:

- **Loot** you receive (source, items, quantities, GE prices)
- **Kill counts**, boss times, clue completions, from game chat
- **Collection log**: new slots and pets from chat, and each collection log page you open
- **Levels and XP**: level ups, skill levels, and XP gained (about once a minute while training)
- **Quests, diaries and tasks**: quest, achievement diary and combat achievement progress, and league tasks, points and relics
- **Play sessions and deaths**: when you log in and out, and the area you die in
- **Slayer**: your task, its progress, points and streak
- **Bank and gear**: bank, seed vault, worn equipment and inventory, when they change
- **Grand Exchange**: offers placed, filled and cancelled
- **Farming timers**: patches and bird houses, as RuneLite's Time Tracking plugin records them
- **Bingo screenshots** (off unless you turn it on): when you get an item that's on one of your groups' live bingo drop
  tiles, a picture of the game screen (everything visible, chat included) is attached to the tile as proof

Every batch also says who you are: your name, which game mode you're on (normal, a league, Deadman), and a one-way id
made from your account hash, so name changes and league profiles are kept apart.

It goes to Nib Tracker's server (Supabase), and screenshots to Nib Tracker's site (buntracker.pages.dev), linked to your
Nib Tracker account by the plugin key. Revoke the key on the Account page at any time.

## What it receives

Plain data only: your goals for the Goals panel, which of the sources above the server wants, and a list of your own
game values (quest, diary and combat achievement progress) to report as they change. The plugin never downloads or runs
code. Clicking a goal opens a page in your web browser (Nib Tracker or the OSRS Wiki); nothing opens on its own.

This plugin submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers.

## Development

Needs Java 11 or newer.

```
./gradlew test    # unit tests (chat parsing, goal progress)
./gradlew run     # starts RuneLite with the plugin loaded, in developer mode
```

To log in to the development client with a Jagex account, follow
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

The event format, server functions and design rules are in `docs/plugin-spec.md` in the Nib Tracker web app. The rule
that matters most: the plugin only reports facts, and the server decides what they mean, so most new features never
need a plugin update (and a new Plugin Hub review).
