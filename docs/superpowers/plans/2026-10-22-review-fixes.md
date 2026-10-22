# Pre-review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix farming (spirit trees, tree grouping, per-location pins, fresh timers), daily tasks (real icons, automatic
completion) and goal item icons in the Nib Tracker plugin and webapp, ready for Plugin Hub review.

**Architecture:** Two repos. Webapp (`nib-tracker-web`, plain JS in `site/app.js`, SQL in `supabase/plugin.sql`)
gives each daily task a stable `key`, an `itemId` and a new `condition` cadence for Miscellania; checks spirit tree
teleports against built-in trees and grown planted ones; sends item ids with goals; and exports its Time Tracking
tables as JSON for the plugin. The plugin (`bun-tracker-plugin`, Java 11) works out patch states from Time Tracking
itself, joins the server feed's travel tips onto them, watches the game's daily-claim varbits, and draws item icons
everywhere a calendar was.

**Tech Stack:** Java 11, RuneLite client API (gameval `VarbitID`/`VarPlayerID`/`ItemID`), Gson, JUnit 4; vanilla JS;
Postgres (Supabase).

**Spec:** `docs/superpowers/specs/2026-10-22-review-fixes-design.md` (in `bun-tracker-plugin`). Read it before any task.

Paths below are relative to `C:\Users\Ryan\Downloads\bun-tracker-raid-luck\`. Plugin tests: run
`./gradlew test` from `bun-tracker-plugin`. The webapp has no test runner: check with `node --check site/app.js`, plus
the throwaway node snippets given in each task (keep them in the scratchpad, don't commit them).

## Global Constraints

- Plugin: Java 11; gameval constants, never magic numbers where gameval has them; no reflection; OkHttp `enqueue`
  only; no network or disk IO on the client thread; follow `bun-tracker-plugin/AGENTS.md` in full.
- The plugin loads nothing from the OSRS Wiki at runtime. Webapp pictures go through `/img/w/` or are mirrored into
  `site/img/w`. Never hot-link the wiki.
- Never rename existing config keys or pin ids without a migration. Old pins (`farm:patch:`, `daily:`, `fav:`,
  `farming:kind:`, `Favourites.SPECIAL_PIN`, `Favourites.TREES_PIN`, legacy `farming:<Label>`) keep working.
- No calendar icon anywhere for daily tasks, in the plugin or the webapp. Delete `GoalTracker.calendarIcon()`.
- No manual step where the game gives a signal. Manual tick stays only as a fallback.
- Spirit trees never appear in farming timers, ready counts, summaries or on-screen boxes.
- Commits per task, in the repo the task changes, no reformatting mixed in. Commit messages end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- A player who logs in with battlestaves already claimed today: the task is marked done once, with no pop-up and no
  chat line, and doesn't repeat on the next login or tick. → `DailySignalsTest.loginWithClaimedFlagIsQuietAndOnce` (Task 7).
- A daily task in the feed with no `key` (an old save, or a task the player named): it keeps the manual tick, gets the
  fallback item icon, and is never auto-ticked. → `DailySignalsTest.taskWithoutKeyIsNeverAutoDone` (Task 7).
- Time Tracking value outside every range in the table, or a region/varbit not in the table (game update): that patch
  is skipped, nothing throws. → `PatchPredictorTest.unknownPatchOrValueIsSkipped` (Task 5).
- The server feed is missing or older than Time Tracking: local rows still show with correct state and timer and no
  travel tip. A feed row whose patch has no local row is still shown, as today. → `FarmMergeTest.feedMissingOrStale` (Task 5).
- A planted spirit tree that's diseased or dead, or still growing: not offered as a teleport. → node check in Task 2.

---

### Task 1: Webapp daily tasks: keys, icons, condition cadence

**Files:**
- Modify: `nib-tracker-web/site/app.js`: `defaultChores()` (~580), `choreDueAt`/`isChoreDue` (~593-609), `M` export (~648), `upgradeAll()` (~3326), `CHORE_ICON`/`choreIcon` (~7424), `W_ICON.chores` (~5362), `plugExtra` dailies (~8105), `applyPluginEvents` (~8203)
- Modify: `nib-tracker-web/supabase/plugin.sql`: `bt_plugin_goals_set` dailies sanitiser (~476-485), and any event-type allow-list for `plugin_events`

**Interfaces:**
- Produces (feed `dailies[]` item): `{ id, key?, label, cadence: 'daily'|'rolling_days'|'interval_min'|'condition', every, dueAt?, due?, how?, itemId? }`. `due` is a boolean, sent only for `condition`.
- Produces (plugin → webapp events): `chore_done { id, key?, label }` (existing, `key` added) and new `chore_state { key, due: boolean }`.
- Produces: `CHORE_KEYS`: label → key for the 12 built-ins:
  `tears_of_guthix, miscellania, zaff_staves, bert_sand, cromperty_essence, seers_flax, lundail_runes, rantz_arrows, nmz_herb_boxes, thirus_dynamite, explorers_ring, robin_slime`.
- Produces: `choreItemId(chore) -> number`, and `choreIcon` keeps its signature but takes the chore's item id first.

- [ ] **Step 1:** Add `key` to `defaultChores()` entries from `CHORE_KEYS`. Give Miscellania `cadence: 'condition'`, `every: 1`, `due: false`. Keep labels unchanged.
- [ ] **Step 2:** In `upgradeAll()`, once per plan (flag `pl.choreKeys1`): fill `key` on saved chores by exact label match with `CHORE_KEYS`, and switch the saved Miscellania chore to `cadence: 'condition'`, `due: false`.
- [ ] **Step 3:** `choreDueAt`: return `null` for `condition`. `isChoreDue`: for `condition`, `chore.enabled && chore.due === true`. Check every other caller of `choreDueAt` (the "soon" list ~5183, `plugExtra`) treats `condition` sensibly: never "Resets in", shown as due or "Approval topped up".
- [ ] **Step 4:** Icons. Turn `CHORE_ICON` into a key → item id table (ids from RuneLite's `ItemID`, using the item names already listed). Miscellania uses the id of `ItemID.POH_TREASURE_TEAK`: look up the number in the RuneLite gameval source. If `itemIcon('#'+id)` has no sprite for it, mirror the wiki picture of that item into `site/img/w` using `tools/fetch_images.js` and point the icon at it. Label regexes stay as a fallback for chores without a key. Chores matching nothing use Coins (`995`). Replace the `img('watch')` fallback and `W_ICON.chores: 'watch'` with an item icon (Battlestaff `1391` for the widget header). Search `app.js` for any other calendar/watch icon on task views and replace those too.
- [ ] **Step 5:** `plugExtra` dailies: add `key`, `itemId: choreItemId(c)`, and `due` for `condition` chores.
- [ ] **Step 6:** `applyPluginEvents`: `chore_done` finds the chore by `id`, else by `key`. New `case 'chore_state'`: find by `key`; set `c.due = !!d.due`; when it goes from due to not due, call `M.completeChore` (one log line).
- [ ] **Step 7:** `plugin.sql`: dailies sanitiser accepts `cadence` `'condition'`, and passes `key` (`^[a-z_]{1,40}$`), `itemId` (number) and `due` (boolean). If events are type-checked anywhere, allow `chore_state`.
- [ ] **Step 8:** Verify: `node --check site/app.js` passes. In a scratch node script, copy `defaultChores`, `choreDueAt`, `isChoreDue` and assert: Miscellania with `due:false` → not due; `due:true` → due; Zaff with `lastDone` today → not due. Serve `site/` locally (`npx serve site` or `python -m http.server` in `site`), open the Tasks view in the browser pane, and screenshot: every task shows an item icon, none shows a calendar or watch.
- [ ] **Step 9:** Commit in `nib-tracker-web`: `Daily tasks: stable keys, item icons, Miscellania by approval`.

### Task 2: Webapp spirit tree teleports only where a tree exists

**Files:**
- Modify: `nib-tracker-web/site/app.js`: the teleport check near `if (t.cat === 'SPIRIT_TREE' && !questDone(a, 'Tree Gnome Village'))` (~7039) and its callers (`telesFor`)

**Interfaces:**
- Produces: `spiritTreesAt(a) -> Set<string>`: the names of the spirit tree spots this character can use. Built-in spots whose quest requirements are met, plus planted spots (`Port Sarim`, `Brimhaven`, `Etceteria`, `Hosidius`, `Farming Guild`) where Time Tracking shows a fully grown, healthy tree.
- Produces: `spiritTreeDest(t) -> string|null`: which spot a `SPIRIT_TREE` teleport lands at, from its `key`/`desc` (`Spirit_Tree_Port_Sarim` → `Port Sarim`, `Spirit_Tree_Brimhaven` → `Brimhaven`, "Farming Guild", "Gnome Stronghold" ...).

- [ ] **Step 1:** Write the built-in list from the OSRS Wiki Spirit tree page, with quest requirements (Tree Gnome Village for all of them; Song of the Elves for Prifddinas; any others the wiki lists). Put it in a const with a "checked October 2026" comment, matching the `EXTRA_TELEPORTS` comment style.
- [ ] **Step 2:** Planted spots are grown when `TT.predictAll` gives a `SPIRIT_TREE` row with `status === 'HARVESTABLE'` or a `GROWING` row at its final stage (the `[20,20]` and `[44,44]` ranges in `TT_IMPL.SPIRIT_TREE`). `DISEASED`/`DEAD` and earlier stages don't count. Read these rows from `TT.predictAll(a.timeTracking)` directly, because `ttRows` filters spirit trees out via `NO_TRACK`. Leave `NO_TRACK` alone.
- [ ] **Step 3:** In the teleport check, a `SPIRIT_TREE` teleport whose destination isn't in `spiritTreesAt(a)` is missing a requirement. Its missing text names the spot: `Spirit tree at Port Sarim`. If `spiritTreeDest` can't tell where a teleport lands, fall back to today's Tree Gnome Village rule.
- [ ] **Step 4:** Verify: `node --check site/app.js`. Scratch node snippet with the `SPIRIT_TREE` ranges: value 20 → usable, 44 → usable, 25 (diseased) → not, 10 (growing) → not.
- [ ] **Step 5:** Commit: `Farm runs: spirit tree teleports only where a tree is there`.

### Task 3: Webapp goal item ids for the plugin

**Files:**
- Modify: `nib-tracker-web/site/app.js`: the `itemIcon` module (~3220, export `itemIdByName` beside `itemIcon`), `goalFeedParams` (~8044)
- Modify: `nib-tracker-web/supabase/plugin.sql`: the goals sanitiser, if it filters `params` keys

**Interfaces:**
- Produces: `itemIdByName(name: string) -> number|null`, using the same `K` lookup and suffix-stripping fallbacks as `itemIcon`. The ` (inert)`/`(uncharged)` → base-name fallback comes from the existing `/ \(.*\)$/` strip.
- Produces (feed goal `params`): `itemId: number` on `clog_item` and `item_owned` goals when known.

- [ ] **Step 1:** Add a small `CHARGED_ID` table for names whose own entry in `K` exists but has no sprite in-game (start with `Pendant of ates (inert)` → the charged pendant's id; look up both ids in `K`). `itemIdByName` checks `CHARGED_ID` first.
- [ ] **Step 2:** `goalFeedParams`: for `clog_item`/`item_owned`, return `{ ...g.params, itemId }` when `itemIdByName(first name before '|')` is non-null.
- [ ] **Step 3:** If `bt_plugin_goals_set` whitelists param keys, add `itemId` (number). Otherwise leave it.
- [ ] **Step 4:** Verify: `node --check site/app.js`. In the browser pane, call `itemIdByName('Pendant of ates (inert)')` in the console and check it returns the charged id.
- [ ] **Step 5:** Commit: `Plugin feed: item ids for item goals`.

### Task 4: Time Tracking tables as JSON for the plugin

**Files:**
- Create: `nib-tracker-web/tools/export_timetracking.js`
- Create: `bun-tracker-plugin/src/main/resources/com/nibtracker/timetracking.json`

**Interfaces:**
- Produces `timetracking.json`:
  `{ "source": "<credit line>", "produce": TT_PRODUCE, "impl": TT_IMPL, "regions": TT_REGIONS, "kinds": [[key,label,group]...], "implKind": IMPL_KIND, "splitTree": SPLIT_TREE }`.
  Shapes are exactly as in `app.js`.

- [ ] **Step 1:** Script reads `site/app.js` as text, pulls each `const NAME = <json>;` line for the names above, `JSON.parse`s each (the generated TT_* lines are pure JSON; for `KINDS`/`IMPL_KIND`/`SPLIT_TREE`, evaluate just that literal with `new Function('return ' + literal)`), and writes the file to the path given as its second argument. The credit line is the one on `TT_PRODUCE`'s comment (BSD-2-Clause RuneLite).
- [ ] **Step 2:** Run: `node tools/export_timetracking.js site/app.js ../bun-tracker-plugin/src/main/resources/com/nibtracker/timetracking.json`. Expected: the file exists, and it has a `regions` entry for `"Farming Guild", 4922`.
- [ ] **Step 3:** Add a line about the script to the README's "Wiki data and pictures" section. Commit the script in `nib-tracker-web` and the JSON in `bun-tracker-plugin` (`Farming: Time Tracking tables for working out patches locally`).

### Task 5: Plugin works out patches locally and joins the feed

**Files:**
- Create: `bun-tracker-plugin/src/main/java/com/nibtracker/PatchPredictor.java`
- Modify: `FarmingTracker.java` (expose the saved entries), `GoalTracker.java` (`settingsChanged` ~255, `extras` ~474)
- Test: `src/test/java/com/nibtracker/PatchPredictorTest.java`, `src/test/java/com/nibtracker/FarmMergeTest.java`

**Interfaces:**
- Consumes: `timetracking.json` (Task 4); `Routines.Patch` and `Routines.farm(JsonElement)` (existing).
- Produces: `final class PatchPredictor`, pure, a port of `TT.predictAll` for patches only (bird houses too, as in `predictAll`):
  - `static PatchPredictor load(Gson gson)`: reads the resource once.
  - `List<Routines.Patch> predict(Map<String,String> entries, long nowMs)`: `entries` are Time Tracking's keys without the profile prefix (`"4922.4771" -> "20:1729600000"`, `"birdhouse.1626"`, `farmTickOffset`, `farmTickOffsetPrecision`, `autoweed`). Each patch gets `region`, `name`, `type`, `crop`, `itemId`, `readyAt`, `ready`, `state` (`empty|growing|ready|diseased|dead`), `progress`, `kind` (via `implKind`, `SPLIT_TREE` → its key), `kindLabel` (from `kinds`), `run` (group). Spirit trees are left out. Its `id()` must equal the feed's `region|name|kind`.
- Produces: `static List<Routines.Patch> FarmRows.merge(List<Routines.Patch> local, List<Routines.Patch> feed)`: local rows, each taking `travel, travelHow, travelCat, travelItem, order` from the feed row with the same `id()`; then feed rows with no local match, unchanged.
- Produces: `Map<String,String> FarmingTracker.entries()`: what `send()` already gathers, kept in memory. Called on the client thread.

- [ ] **Step 1: Write the failing tests.** `PatchPredictorTest`:
  - `herbGrowingThenReady`: Catherby herb `"11062.4774"` with a Ranarr growing value and timestamp T. At T, state `growing` and `readyAt` > T. At T + 2h, `ready`. Take the values from `TT_IMPL.HERB` and `TT_PRODUCE.RANARR`.
  - `farmingGuildSpiritTreeIsLeftOut`: `"4922.4771" -> "20:..."` gives no row.
  - `weedsAreEmpty`: value 3 → `empty`.
  - `idMatchesFeed`: `id()` is `"Farming Guild||celastrus"` for `"4922.7910"`.
  - `unknownPatchOrValueIsSkipped`: `"99999.1"` and an out-of-range value → no rows, no exception.

  `FarmMergeTest.feedMissingOrStale`: local Catherby herb plus a feed row for the same id with `travel:"Catherby teleport"` → one row, the local state and that travel. Empty feed → the local rows. A feed-only row is kept.
- [ ] **Step 2:** Run `./gradlew test --tests '*PatchPredictorTest' --tests '*FarmMergeTest'`. Expected: compile failure, because the classes don't exist yet.
- [ ] **Step 3:** Implement `PatchPredictor` (port `stateFor`, `tickTime` and the patch loop of `predictAll` line for line) and `FarmRows.merge`.
- [ ] **Step 4:** Run those tests. Expected: PASS.
- [ ] **Step 5:** Wire it in. `GoalTracker` keeps `feedFarm` from `settingsChanged`. On each minute tick (where `extras` already rebuilds), set `farm = FarmRows.merge(predictor.predict(farmingTracker.entries(), now), feedFarm)`. Only do this when the existing gating allows farming (linked and `sendFarming`/`farmingPanel` as today). `FarmingTracker` refreshes `entries` when it reads config: on login, every `EVERY_TICKS`, and on `ConfigChanged` for group `timetracking`. Register that event the way the plugin registers its other events.
- [ ] **Step 6:** `./gradlew test`. Expected: all pass. Commit: `Farming: work out patches from Time Tracking, feed adds travel`.

### Task 6: Farming tab: one All trees list and per-location pins

**Files:**
- Modify: `GoalsPanel.java`: `farmGroup` (~966), the region rows loop (~874), `farmMenu` (~1091)
- Modify: `Favourites.java` (new pin), `GoalTracker.java` (`pinnedSingles` ~590)
- Test: `FavouritesTest.java`, `OverlayBoxesTest.java`

**Interfaces:**
- Produces: `static String Favourites.regionPin(String region)` → `"farm:region:" + region`.
- Produces: `farmGroup(Extras x, String name, String pin, Predicate<String> kinds, String none, boolean byKind)`. All trees calls it with `byKind=false` (one flat list, ready first), and Special with `true`.

- [ ] **Step 1: Failing tests.** `FavouritesTest.regionPinIsDistinct`: `regionPin("Farming Guild")` is `"farm:region:Farming Guild"` and isn't a legacy kind pin. `OverlayBoxesTest.regionPinShowsOnlyThatRegion`: pins `{regionPin("Farming Guild")}` with patches in the Farming Guild and Catherby → one on-screen entry, holding only Farming Guild patches, titled `Farming Guild`. Follow the existing helpers in `OverlayBoxesTest`.
- [ ] **Step 2:** Run them: FAIL.
- [ ] **Step 3:** Implement. Each region row in the All patches list gets a popup menu with one item: `Show on game screen` / `Remove from game screen`, which calls `onPin.accept(Favourites.regionPin(r.name))`. In `pinnedSingles`, a `farm:region:` pin adds `kindEntry(region, region, patchesInRegion, now)` (the same entry shape the kind pins use). The box shows only while that region has patches. Check `isLegacyKindPin` doesn't match `farm:region:`. It starts with `farm:`, not `farming:`, so it shouldn't.
- [ ] **Step 4:** Run the tests: PASS. Run `./gradlew test`: all pass.
- [ ] **Step 5:** Commit: `Farming tab: one All trees list; show a single location on screen`.

### Task 7: Daily tasks complete themselves, with item icons

**Files:**
- Create: `bun-tracker-plugin/src/main/java/com/nibtracker/DailySignals.java`
- Modify: `Routines.java` (`Daily` gets `key`, `itemId`, `due`), `GoalTracker.java` (`extras` dailies ~428-471, `dailyEntry` ~747, delete `calendarIcon` ~890, `dailyDone` ~838), `GoalsPanel.java` (daily rows, Daily tasks tab icon), `NibTrackerPlugin.java` (subscribe `VarbitChanged`, call on login)
- Test: `src/test/java/com/nibtracker/DailySignalsTest.java`, `RoutinesTest.java`

**Interfaces:**
- Consumes: feed `dailies[]` from Task 1 (`key`, `itemId`, `cadence: 'condition'`, `due`).
- Produces: `final class DailySignals`, pure:
  - `static final Map<String,Integer> ICONS`: key → `ItemID` fallback, for when the feed has no `itemId`. Uses the same items as the spec's table; Miscellania is `ItemID.POH_TREASURE_TEAK`; unknown is `ItemID.COINS`.
  - `static Set<String> claimedToday(IntUnaryOperator varbit, IntUnaryOperator varp)`: keys whose claim flag says done. Use the spec's table exactly: `ZAFF_LAST_CLAIMED`, `ARDOUGNE_FREE_ESSENCE`, `LUNDAIL_LAST_CLAIMED`, `YANILLE_SAND_CLAIMED`, `SEERS_FREE_FLAX`, `WESTERN_RANTZ_ARROWS`, `KOUREND_FREE_DYNAMITE` (each `!= 0`); `MORYTANIA_SLIME_CLAIMED >= 13 × (medium + hard + elite Morytania diary done)`; `NZONE_HERBBOXES_PURCHASED >= 15`. Add Tears of Guthix and Explorer's ring once their signals are found (Step 1).
  - `static Boolean miscellaniaDue(IntUnaryOperator varbit)`: approval below 90% → `true`, else `false`; `null` if not unlocked. Approval percent is `value * 100 / 127`, from the varbit RuneLite's Kingdom plugin reads.
  - `static List<Routines.Daily> newlyDone(Set<String> claimed, Set<String> sent, List<Routines.Daily> dailies)`:
    the dailies that have a non-empty key in `claimed` and aren't in `sent`. It adds their keys to `sent`. The caller
    clears a key from `sent` when its flag goes back to 0 (the daily reset). Whether to show a pop-up is the caller's
    choice: quiet at login, live otherwise.
- Produces: the panel and the screen use `Routines.Daily.itemId` (or `DailySignals.ICONS`) through `itemManager.getImage`.

- [ ] **Step 1: Research the three open signals.** In RuneLite's gameval (`VarbitID`, `VarPlayerID`), find:
  - the Tears of Guthix last-played value. It may be a varp holding the day or the quest points at last play; RuneLite or the wiki's "Tears of Guthix" page say which.
  - the Explorer's ring alchemy casts used today, with its cap per Lumbridge & Draynor diary tier.
  - the Kingdom of Miscellania approval and unlock varbits, from RuneLite's `KingdomPlugin`.

  Write what you found as a comment at the top of `DailySignals`. If any of them has no signal, leave that task manual and note it for the hand-over.
- [ ] **Step 2: Failing tests.** `DailySignalsTest`:
  - `zaffClaimedIsDone`: the varbit for `ZAFF_LAST_CLAIMED` returns 1 → `claimedToday` contains `zaff_staves`.
  - `slimeNeedsTheDiaryCap`: medium and hard done, slime 13 → not done; 26 → done.
  - `herbBoxesAt15`.
  - `miscellaniaBelow90IsDue`: approval 100 (of 127) → due; 120 → not due; not unlocked → null.
  - `loginWithClaimedFlagIsQuietAndOnce`: the first `newlyDone` call returns the Zaff daily. A second call with the same `sent` set returns nothing. After `sent` is cleared (reset), it returns the daily again.
  - `taskWithoutKeyIsNeverAutoDone`: a daily with an empty key is never returned.

  `RoutinesTest.readsKeyItemIdAndCondition`: the feed parses `key`, `itemId`, `cadence:"condition"`, `due:true`.
- [ ] **Step 3:** Run them: FAIL.
- [ ] **Step 4:** Implement `DailySignals` and the `Routines.Daily` fields. A `condition` daily is due when `due == true`, whatever `dueAt` says.
- [ ] **Step 5:** Run them: PASS.
- [ ] **Step 6:** Wire it in. On login (first tick after `LOGGED_IN`, quiet) and on `VarbitChanged` for any watched varbit (live), `GoalTracker` calls `claimedToday` and `newlyDone`. For each key it gets, it runs the existing `dailyDone(id)` path, which emits `chore_done` with `key` added and sets `tickedOff`. A live event gets the existing pop-up and chat line through the `dailyWasDue` check. A quiet one is added to `dailyWasDue` as not due before `extras` runs, so no pop-up. For Miscellania, emit `chore_state {key, due}` when the value changes, and show the task as due or not due from that value straight away. Daily rows and `dailyEntry` use the item icon; the legacy whole-dailies box uses the first due task's icon. Delete `calendarIcon()` and the `calendar` field. The Daily tasks tab/section header uses the Battlestaff icon.
- [ ] **Step 7:** `./gradlew test`: all pass. Commit: `Daily tasks: tick themselves from the game, item icons`.

### Task 8: Plugin uses goal item ids

**Files:**
- Modify: `GoalTracker.java` (`row(...)` ~1000, `overlayIcon` ~951), `GoalsPanel.java` (~1703 item lookup), `GoalProgress.java` if `Goal` needs an `num("itemId")` accessor
- Test: `GoalProgressTest.java`

**Interfaces:**
- Consumes: `params.itemId` from Task 3.
- Produces: `GoalsPanel.Row.itemId` (int, 0 when unknown), used before name search in both icon lookups.

- [ ] **Step 1: Failing test.** `GoalProgressTest.itemGoalCarriesItemId`: parse `[{"id":"g","type":"clog_item","params":{"item":"Pendant of ates (inert)","itemId":12345}}]` → the goal's item id is 12345 (use the accessor `row` will call).
- [ ] **Step 2:** Run it: FAIL.
- [ ] **Step 3:** Implement. `row` sets `itemId`. `overlayIcon` and the panel's lookup use `itemId > 0 ? itemId : search…`.
- [ ] **Step 4:** Run `./gradlew test`: PASS. Commit: `Goals: use the item id from Nib Tracker for icons`.

### Task 9: Build, package and hand over

**Files:**
- Modify: `bun-tracker-plugin/runelite-plugin.properties` (`version=0.4.0`), `dist/nib-tracker-plugin-test/READ ME FIRST.txt` and `Start Nib Tracker test.bat` (jar name)
- Create: `dist/nib-tracker-plugin-test/nib-tracker-plugin-0.4.0.jar`, `dist/nib-tracker-plugin-test.zip`, `dist/bun-tracker-site_33.zip`, `dist/bun-tracker_33.zip`

- [ ] **Step 1:** Bump the version. Run `./gradlew test shadowJar`. Expected: BUILD SUCCESSFUL. Copy `build/libs/*-all.jar` into `dist/nib-tracker-plugin-test/` as `nib-tracker-plugin-0.4.0.jar` and remove the 0.3.0 jar. Update the jar name in the .bat and README. Re-zip the folder.
- [ ] **Step 2:** Zip `nib-tracker-web/site` as `dist/bun-tracker-site_33.zip`, and the whole webapp the way `bun-tracker_32.zip` is laid out (check that zip's listing) as `dist/bun-tracker_33.zip`. Bump the service worker `CACHE` version in `site/sw.js` before zipping, and commit that bump.
- [ ] **Step 3:** Hand over to Ryan: run `supabase/plugin.sql` in the SQL editor, deploy with `.\deploy.ps1`, and start the test client with `Start Nib Tracker test.bat`. What to test in game:
  - collect battlestaves: the task ticks itself with a pop-up
  - log out and back in: no repeat
  - Miscellania shows due only when approval is low
  - icons on every daily task, plugin and site
  - spirit tree teleports offered only where valid
  - All trees is one list
  - right-click Farming Guild → only it on screen
  - Pendant of ates (inert) goal shows its icon
  - play with the site closed: farming timers still correct

  List any daily task that stayed manual (Task 7, Step 1). Don't call it done until Ryan confirms in game.
