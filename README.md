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
diaries and combat achievements (click a heading to fold it away). Each goal shows how far along it is, with a bar that
goes from red to green, and what's left ("88,113 XP to go", "288 more kills").

Goals made of parts are folded into one row: "Base 80 in every skill" shows your overall progress and how many skills
are done, and you open it to see each skill (the same for a quest cape and its quests). Long sections show the first
few goals with "Show more", and finished goals move to a Completed section at the bottom.

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
