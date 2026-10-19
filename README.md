# Bun Tracker

[![Build](https://github.com/OWNER/bun-tracker-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/OWNER/bun-tracker-plugin/actions/workflows/build.yml)

Syncs your own Old School RuneScape progress to your [Bun Tracker](https://buntracker.pages.dev) planner, so goals,
points, slayer, farming, pet luck and bingo tiles update without typing anything in.

## Setup

1. In Bun Tracker, open **Account > RuneLite plugin** and click **Create plugin key**. One key covers all your characters.
2. In RuneLite, open the Bun Tracker plugin settings, paste the key into **Plugin key**, and turn on **Sync to Bun Tracker**.
3. Log in. You'll see "Bun Tracker is syncing <name>." in chat.

League and Deadman profiles are kept apart from your main: the first time you log in to one, Bun Tracker asks which
planner character it should sync into (or makes a new one).

## What is sent

Only your own character's data, and only while **Sync to Bun Tracker** is on. Each group can be turned off under
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
- **Bingo screenshots**: when you get an item that's on one of your groups' live bingo drop tiles, a picture of the game
  is attached to the tile as proof

Every batch also says who you are: your name, which game mode you're on (normal, a league, Deadman), and a one-way id
made from your account hash, so name changes and league profiles are kept apart.

It goes to Bun Tracker's server (Supabase), and screenshots to Bun Tracker's site (buntracker.pages.dev), linked to your
Bun Tracker account by the plugin key. Revoke the key on the Account page at any time.

This plugin submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers.

## Development

Needs Java 11 or newer.

```
./gradlew test    # unit tests (chat parsing)
./gradlew run     # starts RuneLite with the plugin loaded, in developer mode
```

To log in to the development client with a Jagex account, follow
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

The event format, server functions and design rules are in `docs/plugin-spec.md` in the Bun Tracker web app. The rule
that matters most: the plugin only reports facts, and the server decides what they mean, so most new features never
need a plugin update (and a new Plugin Hub review).
