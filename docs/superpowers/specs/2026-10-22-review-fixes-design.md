# Pre-review fixes: farming, daily tasks and icons

Date: 2026-10-22
Covers: `bun-tracker-plugin` (RuneLite plugin, Nib Tracker) and `nib-tracker-web` (the webapp, `site/app.js`)

## Why

Ryan found these problems testing the plugin in game, before sending it to Plugin Hub review. The plugin should be correct
and tidy when reviewers see it. There's also a house rule: **if something can be automated, there should be no manual
input or entry for it** in the plugin or the webapp.

## Goals

1. **Spirit trees work as teleports, never as farming timers.**
   - Spirit tree patches never appear in farming timers, ready counts, summaries or the on-screen boxes. That's the
     current behaviour and it stays.
   - In the webapp's travel advice (`telesFor` and related code, `CAT_RANK`, the check near `app.js` line 7039), a
     spirit tree teleport is only offered if the destination exists for this character:
     - **Built-in trees** (Tree Gnome Village, Gnome Stronghold, Grand Exchange, Battlefield of Khazard, and any others
       the game provides without planting, such as Prifddinas after Song of the Elves and Poison Waste): offered when
       the quest requirements for that tree are met.
     - **Player-planted trees** (Port Sarim, Brimhaven, Etceteria, Hosidius, Farming Guild): offered only if Time
       Tracking data shows a fully grown, healthy spirit tree in that patch. Use the `SPIRIT_TREE` rows in `TT_IMPL`.
       Today these rows are filtered out by `NO_TRACK` before anything sees them, so read them separately for this
       check.
   - Today every spirit tree is offered once Tree Gnome Village is done. That's the bug being fixed.
2. **One "All trees" list.** In the plugin's Farming tab, "All trees" shows a single flat list. Today
   `GoalsPanel.farmGroup` splits it under Trees, Hardwood and Redwood sub-headings; those go away. Patches stay sorted
   ready-first. Special keeps its per-kind sub-headings.
3. **Show one farming location on screen.** In the plugin's Farming overview, each location (for example "Farming
   Guild") has a right-click item, "Show on game screen", that puts only that location's patches in an on-screen box.
   Clicking it again removes the box ("Remove from game screen"). This sits next to the existing
   "Show farming on game screen", which shows everything. It uses the existing pin and box mechanism in
   `Favourites` and `GoalTracker`: a new pin id format such as `region:<name>`, and one box per pinned location.
4. **Proper icons for daily tasks, everywhere, never a calendar.**
   - Plugin: replace `GoalTracker.calendarIcon()` for daily task rows and on-screen entries with each task's item icon,
     loaded with `itemManager.getImage(ItemID...)`. Delete the calendar drawing code.
   - The icon list in both the plugin and the webapp's `CHORE_ICON`:
     | Task | Icon |
     |---|---|
     | Tears of Guthix | an experience lamp (`Antique lamp`, as the webapp already uses) |
     | Kingdom of Miscellania | `ItemID.POH_TREASURE_TEAK`, the icon RuneLite's Kingdom of Miscellania plugin uses (the webapp uses Coins today, so change it) |
     | Zaff's battlestaves | Battlestaff |
     | Bert's buckets of sand | Bucket of sand |
     | Wizard Cromperty's pure essence | Pure essence |
     | Flax to bow strings | Bow string |
     | Lundail's free runes | a rune (Mind rune, as today) |
     | Rantz's ogre arrows | Ogre arrow |
     | Nightmare Zone herb boxes | Herb box |
     | Thirus's dynamite | Dynamite |
     | Explorer's ring alchs | Explorer's ring |
     | Robin's buckets of slime | Bucket of slime |
   - **Every daily task option in the webapp has a specific icon**, both in the plugin and in the webapp. Check every
     entry in `defaultChores()` and any other task list or picker the webapp offers. Tabs, headers and overview rows for
     daily tasks show an item icon too, never a calendar.
   - Tasks a player names themselves (if the webapp allows them) fall back to a neutral item icon, never a calendar.
     The builder picks which item.
   - The webapp sends each task's icon item id in the `dailies` feed (a new `itemId` field, also added to the
     `bt_plugin_goals_set` sanitiser in `supabase/plugin.sql`), so the two sides can't drift apart. The plugin keeps a
     local fallback table for tasks with no `itemId`.
5. **Daily tasks complete automatically.** Today a task is only marked done when it's ticked off by hand. The plugin
   should detect it in game and send the existing `chore_done` event. The webapp already handles that event
   (`applyPluginEvents`, `case 'chore_done'`).
   - Use the same game signals as RuneLite's Daily Task Indicators plugin. Read them on login and whenever the varbit
     changes (`VarbitChanged`), not only once per day. A task counts as done today when its claimed flag is set:
     | Task | Signal (gameval) | Done when |
     |---|---|---|
     | Zaff's battlestaves | `VarbitID.ZAFF_LAST_CLAIMED` | != 0 |
     | Wizard Cromperty's pure essence | `VarbitID.ARDOUGNE_FREE_ESSENCE` | != 0 |
     | Lundail's free runes | `VarbitID.LUNDAIL_LAST_CLAIMED` | != 0 |
     | Bert's buckets of sand | `VarbitID.YANILLE_SAND_CLAIMED` | != 0 |
     | Flax to bow strings | `VarbitID.SEERS_FREE_FLAX` | != 0 |
     | Rantz's ogre arrows | `VarbitID.WESTERN_RANTZ_ARROWS` | != 0 |
     | Thirus's dynamite | `VarbitID.KOUREND_FREE_DYNAMITE` | != 0 |
     | Robin's buckets of slime | `VarbitID.MORYTANIA_SLIME_CLAIMED` | >= the cap (13 per Morytania diary tier: medium, hard, elite) |
     | Nightmare Zone herb boxes | `VarbitID.NZONE_HERBBOXES_PURCHASED` | >= 15 |
   - Tears of Guthix, Kingdom of Miscellania and Explorer's ring alchs: find a reliable game signal (a varbit/varp in
     gameval, or a fixed game chat message) and use it.
   - **Miscellania (Ryan's decision): no daily reset.** It's due only while approval is below a threshold, and done
     once it's topped back up. Read approval from the same varbits RuneLite's Kingdom plugin uses; the builder picks
     the threshold. This needs a new cadence kind in the webapp (for example `'condition'`), handled by `choreDueAt` /
     `isChoreDue` and the plugin's due logic. The plugin reports the approval state rather than a `chore_done`
     timestamp, and the existing saved Miscellania task migrates to the new cadence.
     **My call:** Explorer's ring is done when all of today's free alchs are used. The cap depends on the
     Lumbridge & Draynor diary tier, read the same way the slime cap is. If a task truly
     has no signal, leave the manual tick for that task only and **list it for Ryan** at hand-over. Don't drop it
     silently.
   - Mapping game signals to tasks: the plugin matches the webapp's task by label or a stable key. **Builder's call:**
     the webapp adds a stable `key` per built-in task (for example `zaff_staves`) to `defaultChores()` and the feed,
     with a migration that fills in keys on existing saved tasks by matching their labels. The plugin matches on that
     key.
   - Send `chore_done` once for each task, each time it resets. Check the task's current due state and the existing
     `dailyWasDue` tracking so logging in with a flag already set doesn't repeat the pop-up and chat message. The first
     time a done task is seen after login, mark it done without a pop-up; the pop-up is for completions seen happening
     live.
   - Manual ticking in the panel can stay as a fallback, but no task that can be detected should need it.
6. **Charged-item icons in the plugin.** `itemManager.search` only knows tradeable items, so untradeable items such as
   Pendant of Ates (inert) get no icon (`GoalTracker.overlayIcon`, `GoalsPanel` ~1703).
   - The webapp sends an `itemId` with `clog_item` and `item_owned` goals in the goal feed (`goalFeedList` and
     `goalFeedParams`), taken from the item data it already has. Add `itemId` to the sanitiser in `plugin.sql`. The
     plugin uses that id first, then name search, then the fallback.
   - If the item's own picture is missing or blank, use the charged version's id (for example inert → charged).
     **Builder's call:** this lives on the webapp side as a small table of uncharged-to-charged ids for the known
     inert/uncharged items, so the plugin just receives a working id.
   - The webapp's own pictures don't change. Ryan confirmed they're fine.
7. **Farming stays current without the website open.**
   - Today the farm list the plugin shows is built in the browser (`plugExtra`) and only refreshed when the webapp is
     open, so the plugin can show old patch states.
   - **Builder's call (approved by Ryan):** the plugin works out patch state, growth and ready time itself from Time
     Tracking's saved data (the `timetracking` RS-profile config it already reads in `FarmingTracker`). This means
     porting `predictPatch` (as the webapp's `TT.predictAll` already does) and the region/patch/produce tables to Java.
     Generate the Java tables from the same RuneLite source the webapp's `tools/build_timetracking.py` uses (credit
     BSD-2 like the webapp does). Don't call into RuneLite's Time Tracking classes; they aren't public API.
   - The webapp's feed still supplies what only it can work out: travel advice, run order and stop number. The plugin
     joins those onto its own rows by patch id (`region|name|kind`). Where the feed is missing or older, rows show
     without travel tips.
   - **My call:** the Farming tab keeps its current gating (the account is linked and "Farming timers" is on). Local
     working-out makes the data fresh; it doesn't make farming a standalone feature, so the panel text and settings
     stay as they are.
   - Daily task due times are already worked out locally from `dueAt` plus local ticks. With automatic detection
     (goal 5) they no longer depend on the website being open.
8. **Rules compliance.** All changes follow `AGENTS.md` (gameval constants, no reflection, OkHttp enqueue only, the
   Plugin Hub restrictions) and the OSRS Wiki's terms. The plugin loads no wiki content at runtime. Item icons come from
   `ItemManager`. The webapp's `/img/w/` function already downloads each wiki picture once and serves its stored copy.
   Any new picture the webapp needs (for example the Miscellania icon) goes through that route or is mirrored into
   `site/img/w` at build time, never hot-linked from the wiki.

## Non-goals

- Changing how the webapp shows its own item pictures (they're fine).
- Treating spirit trees as crops in any way.
- New daily tasks or new farming features beyond the above.

## Anti-goals (a failure even if it technically works)

- A calendar icon anywhere for daily tasks, in the plugin or the webapp.
- Any manual step left where the game provides a signal.
- Anything a Plugin Hub reviewer would reject: magic numbers where gameval exists, reflection, runtime wiki fetching
  from the plugin, network calls on the client thread.
- Duplicate pop-ups or chat lines for the same daily task.

## Constraints

- Java 11. Use RuneLite's injected services only (`ItemManager`, `ConfigManager`, OkHttp, Gson).
- Never rename existing config keys or pin ids without a migration. Old pins keep working.
- Pure logic goes in testable classes (`FarmRows`, `FarmGroups`, `Routines` style), with unit tests in `src/test`.
- Webapp: plain JS in `site/app.js`. Server changes are SQL in `supabase/plugin.sql`.

## Delivery and testing

- Ryan tests in game with a test build in `dist/nib-tracker-plugin-test` (the jar plus `Start Nib Tracker test.bat`).
  Rebuild it and bump the plugin version. Also produce the next numbered webapp zip in `dist` (`bun-tracker-site_33.zip`
  and `bun-tracker_33.zip`, following the existing pattern) and any SQL to run.
- Per `AGENTS.md`, don't declare it done until Ryan confirms in game. Hand over a short list of what to test: collect
  battlestaves and see the task tick itself, spirit tree teleports offered only where valid, a single location on
  screen, the All trees list, icons on every daily task, the Pendant of Ates (inert) icon, and farming timers correct
  after playing with the site closed.
- Commit in each repo separately, with clear messages and no reformatting mixed in.

## Left to the builder

- Exact signals for Tears of Guthix, Miscellania and Explorer's ring (see goal 5). Report any task that stays manual.
- The fallback icon item for player-named tasks.
- The list of built-in spirit trees and their quest requirements, checked against the wiki's Spirit tree page.
- How the Java Time Tracking tables are generated and kept in step with RuneLite.
