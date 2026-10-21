# Goals panel: rows, favourites, pinning, farming groups and contracts — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every Goals panel tab use one row style with ★ (favourite) and ▣ (show on screen) on every row; add
Farming groups (Favourites, Special, All trees, Farming contract), a picker that stays open, single-item pins, and
on-screen boxes per tab.

**Architecture:**
- The farm-data fix is in the website: `nib-tracker-web/site/app.js` (`plugExtra`) and its whitelist in
  `supabase/plugin.sql`. The website starts sending each patch's state and growth progress.
- New logic that doesn't touch the game client goes in small classes that can be unit tested: `FarmGroups` and
  `Favourites`.
- `GoalsPanel` gets one row builder for every tab. `GoalTracker` works out what's pinned and favourited.
  `GoalOverlay` draws one box per group.

**Tech Stack:** Java 11, RuneLite client API (Swing `PluginPanel`, `Overlay`, `ConfigManager`), JUnit 4, Gradle.
Website: plain JS (`site/app.js`) and Supabase SQL.

**Spec:** `docs/superpowers/specs/2026-10-21-goals-panel-favourites-design.md`

## Global Constraints

- Everything in `AGENTS.md`:
  - Java 11, no reflection
  - no blocking IO on the client thread
  - overlay `render` does no computing, only drawing
  - use `net.runelite.api.gameval.ItemID` constants
- Never rename the config keys `pinnedGoals` or `hiddenFarmRegions`. Existing values must keep working.
- New config keys are hidden (`hidden = true`) and live in `NibTrackerConfig.GROUP`.
- Special group, exactly: Hespori, Anima, Belladonna, Cactus, Coral, Seaweed, Mushroom, Calquat. As `kind` keys:
  `hespori, anima, belladonna, cactus, seaweed, mushroom, calquat`. The site maps coral to `seaweed`.
- All trees, as `kind` keys: `tree, hardwood, redwood`.
- Exact text:
  - `Ready · then see Jane for a new contract`
  - `No contract · talk to Jane`
  - Contract title: `Contract: <crop> · Farming Guild <patch> patch`
- The contract is read from RuneLite's `timetracking` profile config, key `contract`. Its value is the contract
  crop's **item id** as a string (from RuneLite's `FarmingContractManager`).
- Rows: icon slot `ICON` = 28px, a 3px left strip, padding `6,6,6,8`, a 5px bar, RuneScape small font. Title is
  white, value on the right, second line in `MUTED`.
- `nib-tracker-web` has the owner's **uncommitted changes**, which match the newest site build
  (`dist/bun-tracker-site_31.zip`). Ask the owner before committing anything in that repo. Never stage files the task
  didn't touch.
- Only the owner can test in-game (`AGENTS.md` Testing). No task is "done" until the owner confirms it in-game.

## Review Focus

1. **Old saved pins.** `pinnedGoals` = `dailies,farming,farming:Herbs,<goal ids>` must still draw the same things
   after the update. *Test: Task 4 `oldPinsStillParse`.*
2. **Old website feed.** Farm entries with no `state` or `progress` must behave exactly as today: ready if `ready`,
   growing otherwise. *Test: Task 2 `feedWithoutStateIsGrowingOrReady`.*
3. **Contract changes crop.** A pinned or favourited contract must follow the new crop with no re-pinning. The id is
   fixed. *Test: Task 3 `contractIdIgnoresCrop`.*
4. **Contract patch holds another crop, or is empty after harvesting.** It must show a sensible line and never throw.
   *Test: Task 3 `contractOccupiedAndEmpty`.*
5. **Hidden region holding a favourite or pinned patch.** Hiding a region hides it from All patches only. Favourites
   and pins still show it (my call: hiding is a view filter). *Test: Task 4 `hiddenRegionKeepsFavourites`.*

---

### Task 1: Website feed sends patch state, growth progress and unknown special patches

**Files:**
- Modify: `nib-tracker-web/site/app.js` (`plugExtra`, around line 8110)
- Modify: `nib-tracker-web/supabase/plugin.sql` (farm `jsonb_build_object`, around line 488)

**Interfaces:**
- Produces: each feed farm entry may carry:
  - `state`: one of `empty|growing|ready|diseased|dead|unknown`
  - `progress`: a number from 0 to 1, or absent

  Unknown patches have no `readyAt`. Ordering stays the same, and the cap stays at 150.

- [ ] **Step 1:** In `plugExtra`, replace `ttRows(a).filter(r => r.ready || r.growing)` with all `ttRows(a)` rows
  except bird houses that are `EMPTY`. Map `r.status` to `state`:

  | `r.status` | `state` |
  |---|---|
  | `HARVESTABLE` | `ready` |
  | `GROWING` | `growing` |
  | `EMPTY` | `empty` |
  | `DISEASED` | `diseased` |
  | `DEAD` | `dead` |
  | anything else | `growing` |

  Set `progress: r.stages > 1 ? r.stage / (r.stages - 1) : undefined`. Rows go growing/ready first, then the
  others, so the 150 cap never drops a growing patch.
- [ ] **Step 2:** Add one row with `state: 'unknown'` for each Special patch location the account has no data for.
  - Patches come from the site's `PATCH_BY_KEY`, keeping those whose `kindOf` is in the special set.
  - Fields: `region`, `name`, `type`, `kind`, `kindLabel`, and `crop: ''`.
- [ ] **Step 3:** In `plugin.sql`, add these two keys to the farm `jsonb_build_object`:
  ```sql
  'state', case when f->>'state' in ('empty','growing','ready','diseased','dead','unknown') then to_jsonb(f->>'state') end,
  'progress', case when jsonb_typeof(f->'progress') = 'number' then to_jsonb(least(greatest((f->>'progress')::numeric, 0), 1)) end,
  ```
- [ ] **Step 4: Verify.**
  - Open the site with an account that has RuneLite data, then run in the console:
    `JSON.stringify(plugExtra(acct(), P()).farm.filter(f => f.state !== 'growing' && f.state !== 'ready').slice(0, 5))`.
    **Expected:** entries with `state` `empty`, `diseased`, `dead` or `unknown`, and no errors.
  - Ask the owner to run the updated `bt_plugin_goals_set` function from `plugin.sql` in the Supabase SQL editor.
- [ ] **Step 5:** Ask the owner whether to commit their own pending `nib-tracker-web` changes first. Then stage only
  `site/app.js` and `supabase/plugin.sql`, with the message
  `Plugin feed: patch state and growth, unknown special patches`.

### Task 2: Plugin reads patch state and progress

**Files:**
- Modify: `src/main/java/com/nibtracker/Routines.java` (`Patch`, `farm`)
- Test: `src/test/java/com/nibtracker/RoutinesTest.java`

**Interfaces:**
- Produces:
  - `Routines.Patch.state` (`String`; `"ready"` or `"growing"` when the feed has none) and `Routines.Patch.progress`
    (`double`; `-1` when unknown)
  - `boolean Patch.needsAttention()`: true for `diseased` or `dead`
  - `String Patch.id()`: returns `region + "|" + name + "|" + kind`

- [ ] **Step 1: Write the failing tests.**
  ```java
  @Test public void feedWithoutStateIsGrowingOrReady() {
      List<Routines.Patch> f = Routines.farm(new JsonParser().parse(
          "[{\"region\":\"Catherby\",\"crop\":\"Ranarr\",\"ready\":true},{\"region\":\"Ardougne\",\"crop\":\"Guam\"}]"));
      assertEquals("ready", f.get(0).state);
      assertEquals("growing", f.get(1).state);
      assertEquals(-1, f.get(1).progress, 0);
  }
  @Test public void readsStateProgressAndId() {
      Routines.Patch p = Routines.farm(new JsonParser().parse(
          "[{\"region\":\"Farming Guild\",\"name\":\"\",\"kind\":\"anima\",\"state\":\"diseased\",\"progress\":0.5}]")).get(0);
      assertEquals("diseased", p.state);
      assertEquals(0.5, p.progress, 0);
      assertTrue(p.needsAttention());
      assertEquals("Farming Guild||anima", p.id());
      assertFalse(p.readyAt(Long.MAX_VALUE)); // no readyAt, not ready
  }
  ```
- [ ] **Step 2:** Run `./gradlew test --tests com.nibtracker.RoutinesTest`. **Expected:** it fails to compile
  (`state` and `progress` don't exist yet).
- [ ] **Step 3:** Add the fields and methods, and parse `state` and `progress` in `farm()`. An unknown or missing
  `state` falls back to `ready ? "ready" : "growing"`.
- [ ] **Step 4:** Run the same command. **Expected:** PASS.
- [ ] **Step 5:** Commit with the message `Farm feed: patch state and growth progress`.

### Task 3: FarmGroups: Special, All trees, and the farming contract

**Files:**
- Create: `src/main/java/com/nibtracker/FarmGroups.java`
- Test: `src/test/java/com/nibtracker/FarmGroupsTest.java`

**Interfaces:**
- Consumes: `Routines.Patch` (`kind`, `region`, `state`, `crop`, `readyAt`, `readyAt(long)`, `itemId`) from Task 2.
- Produces:
  - `static final Set<String> SPECIAL`, `TREES`
  - `static final String CONTRACT_ID = "farming:contract"`
  - `static boolean special(String kind)`, `static boolean tree(String kind)`
  - `static final class Contract { final String crop; final String kind; final int itemId; }`
  - `static Contract contract(int itemId)`: returns `null` when the id isn't a contract crop
  - `static ContractView contractView(Contract c, List<Routines.Patch> farm, long now)`, where
    `ContractView { String title; String value; String sub; boolean ready; boolean attention; int itemId; double progress; }`

- [ ] **Step 1: Write the failing tests.** They cover these cases:
  - `special("seaweed")` is true; `special("herb")` is false
  - `tree("redwood")` and `tree("hardwood")` are true; `tree("fruit_tree")` is false
  - `contractIdIgnoresCrop`: `CONTRACT_ID` doesn't change between crops (it's a constant)
  - `contract(ItemID.RANARR_WEED).kind` equals `"herb"`, and `contract(ItemID.REDWOOD_LOGS).kind` equals `"redwood"`
    (use the produce item ids RuneLite's `Produce` stores; check them against `Produce.java` and correct the constant
    names if they differ)
  - `contractView(null, ...)` gives value `"No contract · talk to Jane"`
  - Contract Ranarr with a Farming Guild herb patch, crop Ranarr, state `ready` gives `ready == true` and value
    `"Ready · then see Jane for a new contract"`
  - `contractOccupiedAndEmpty`:
    - the guild herb patch growing Guam gives value `"Other crop planted"`
    - no guild herb patch, or state `empty`, gives value `"Not planted"` with sub `"Harvested? See Jane for a new contract"`
  - The same patch `diseased` gives `attention == true` and value `"Diseased"`
  - Growing with `readyAt = now + 90min` gives value `"1h 30m"`
  - The title is `"Contract: Ranarr · Farming Guild herb patch"`
- [ ] **Step 2:** Run `./gradlew test --tests com.nibtracker.FarmGroupsTest`. **Expected:** it fails to compile.
- [ ] **Step 3:** Implement. The contract table is item id → (crop, kind) for these crops:

  | Kind | Crops |
  |---|---|
  | allotment | Potatoes, Onions, Cabbages, Tomatoes, Sweetcorn, Strawberries, Watermelons, Snape grass |
  | flower | Marigold, Rosemary, Nasturtium, Woad, Limpwurt, White lily |
  | bush | Redberries, Dwellberries, Jangerberries, Cadavaberries, Whiteberries, Poison ivy |
  | herb | Guam, Marrentill, Tarromin, Harralander, Ranarr, Toadflax, Irit, Avantoe, Kwuarm, Snapdragon, Cadantine, Lantadyme, Dwarf weed, Torstol |
  | cactus | Cactus, Potato cactus |
  | tree | Oak, Willow, Maple, Yew, Magic |
  | fruit_tree | Apple, Banana, Orange, Curry, Pineapple, Papaya, Palm, Dragonfruit |
  | celastrus | Celastrus |
  | redwood | Redwood |

  Use `ItemID` constants. Don't import RuneLite's `Produce`: it belongs to another plugin and can change without
  warning. The guild patch is the `farm` entry with region `"Farming Guild"` whose `kind` equals the contract's kind.
  The crop matches when `crop` equals the contract crop, ignoring case and a trailing "s".
- [ ] **Step 4:** Run the same command. **Expected:** PASS.
- [ ] **Step 5:** Commit with the message `Farming groups and the farming contract`.

### Task 4: Favourites, pin ids and the contract read

**Files:**
- Create: `src/main/java/com/nibtracker/Favourites.java`
- Modify: `src/main/java/com/nibtracker/NibTrackerConfig.java`. Add:
  `String FAVOURITES = "favourites"; @ConfigItem(keyName = FAVOURITES, name = "", description = "", hidden = true) default String favourites() { return ""; }`
- Modify: `src/main/java/com/nibtracker/FarmingTracker.java`. Add `int contractItemId()`. It reads
  `configManager.getRSProfileConfiguration("timetracking", "contract")` and returns 0 when the value is missing or
  isn't a number.
- Test: `src/test/java/com/nibtracker/FavouritesTest.java`

**Interfaces:**
- Produces:
  - `Favourites.parse(String saved)` returns a `Map<String, Set<String>>` (tab → ids, kept in insertion order)
  - `Favourites.write(Map<String, Set<String>>)` returns a `String`
  - `Favourites.toggle(Map<..>, String tab, String id)`
  - `Favourites.setAll(Map<..>, String tab, Collection<String> ids)`, used by the picker's Done
  - Pin id helpers:
    - `Favourites.patchPin(String patchId)` gives `"farm:patch:" + id`
    - `Favourites.dailyPin(String id)` gives `"daily:" + id`
    - `Favourites.groupPin(String tab)` gives `"fav:" + tab`
    - `Favourites.kindPin(String key)` gives `"farming:kind:" + key`
  - `Favourites.isLegacyKindPin(String pin)`: true for `farming:<Label>` pins that aren't `farming:kind:` or
    `farming:contract`

  Saved format (my call): one line per tab, `tab=id1\u001Fid2`, with `\u001E` between tabs. Ids can contain `|`,
  `,` and `:`, so neither separator can appear inside one.

- [ ] **Step 1: Write the failing tests.**
  - `roundTrips`: `parse(write(m))` equals `m` for ids holding `|`, `:` and `,`
  - `oldPinsStillParse`: `isLegacyKindPin("farming:Herbs")` is true; `isLegacyKindPin("farming:kind:herb")`,
    `isLegacyKindPin("farming:contract")`, `isLegacyKindPin("dailies")` and `isLegacyKindPin("farming")` are all
    false
  - `toggleAddsThenRemoves`
  - `setAllReplacesOnlyThatTab`
  - `hiddenRegionKeepsFavourites`: a pure check in `FarmGroups`, `static boolean shownInAll(Routines.Patch p,
    Set<String> hidden)`, is false when `p.region` is hidden. Favourites lists are never filtered by `hidden`;
    this is asserted in Task 6's code review.
- [ ] **Step 2:** Run `./gradlew test --tests com.nibtracker.FavouritesTest`. **Expected:** it fails to compile.
- [ ] **Step 3:** Implement `Favourites`, the config key, `FarmGroups.shownInAll` and
  `FarmingTracker.contractItemId()`.
- [ ] **Step 4:** Run `./gradlew test`. **Expected:** every test passes.
- [ ] **Step 5:** Commit with the message `Per-tab favourites, pin ids, contract read from Time Tracking`.

### Task 5: One row for every tab, with ★ and ▣

**Files:**
- Modify: `src/main/java/com/nibtracker/GoalsPanel.java`
  - replace `line(...)` and merge `row(...)` into a single `card(Card c, int indent)`
  - add `Card`
  - add Favourites sections
- Modify: `src/main/java/com/nibtracker/GoalTracker.java` (`start`, `extras`, panel callbacks)

**Interfaces:**
- Consumes: `Favourites` (Task 4).
- Produces:
  - `GoalsPanel.Card { String id; String favTab; String pinId; Icon/int itemId/Skill/Row-icon source; String title; String value; Color valueColour; double progress; String sub; String tooltip; Color edge; boolean fav; boolean pinned; JPopupMenu menu; Runnable click; }`
  - Panel callbacks `onFavourite(BiConsumer<String tab, String id>)` and `onPin(Consumer<String>)` (`onPin`
    already exists)
  - `Extras.favourites` (`Map<String, Set<String>>`) and `Extras.pins` (`Set<String>`)

- [ ] **Step 1:** Build `card()` to the sizes in Global Constraints.
  - Left: the icon at 28px, through the existing `icon(Row, JLabel)` / `itemIcon` paths. Never a coloured
    placeholder. With no icon, the slot stays empty, so rows still line up.
  - Middle: the title and value, the bar (only when `progress >= 0`), and the `sub` line.
  - Right: two 14px glyph labels, ★ and ▣. Orange `ColorScheme.BRAND_ORANGE` when on, `UNKNOWN` grey when off.
    Each has its own tooltip: "Add to favourites" / "Remove from favourites" and "Show on game screen" /
    "Remove from game screen". Clicking a glyph consumes the event, so the row's own click (wiki link) doesn't fire.
- [ ] **Step 2:** Make goal rows (`group`) render through `card()`. Show the existing hint, percentage and "On
  screen" text on the `sub` line. Keep the existing right-click menu.
- [ ] **Step 3:** In each goal category view and in Daily tasks, add a `★ FAVOURITES` section at the top when that
  tab has favourites (`sectionHeader`). Overview shows no favourites section. A ★ clicked in Overview saves under
  the row's home tab (`Row.category`, `"Daily tasks"`, or `"Farming"`).
- [ ] **Step 4:** Make daily rows render through `card()`:
  - the icon is the daily's item, or `calendarIcon()` if it has none
  - the pin id is `Favourites.dailyPin(id)`
  - keep "Mark done" in the right-click menu
- [ ] **Step 5:** In `GoalTracker`, wire the callbacks:
  - `onFavourite` → `Favourites.toggle`, then save to `NibTrackerConfig.FAVOURITES` and set `dirty = true`
  - pins keep going through the existing `togglePin`
- [ ] **Step 6:** Run `./gradlew build`. **Expected:** BUILD SUCCESSFUL, all tests pass. Then run `./gradlew run`.
  Ask the owner to check that Overview, a goal tab and Daily tasks rows are the same height and style, and that
  ★/▣ toggle. Commit with the message `Goals panel: one row style everywhere, with favourite and on-screen buttons`.

### Task 6: Farming tab: groups, rows and the picker that stays open

**Files:**
- Modify: `src/main/java/com/nibtracker/GoalsPanel.java` (`show` Farming branch, `farming`, `runOrder`; remove
  `regionsMenu`)
- Modify: `src/main/java/com/nibtracker/GoalTracker.java` (`extras`: patch views carry `id`, `kind`, `state`,
  `progress`; contract view; `allRegions` shown even when there's no farm data but there is a contract)

**Interfaces:**
- Consumes:
  - `FarmGroups.special/tree/contractView/shownInAll`
  - `Routines.Patch.id()/state/progress`
  - `Favourites.patchPin`, `kindPin`, `groupPin`
- Produces:
  - `Extras.contract` (`FarmGroups.ContractView` or `null`)
  - `PatchView.id`, `kind`, `state`, `progress`
  - The panel callback `onRegionsSet(Set<String> hidden)`, which replaces the per-region toggle for bulk changes
    (`toggleRegion` stays for the single "Hide <region>" menu item)

- [ ] **Step 1:** Make the Farming chips, in this order: `All patches` (default), `★ Favourites`, `Special`,
  `All trees`, `Farming contract`, then the existing kind chips. The tab shows when there's farm data **or**
  `FarmingTracker.contractItemId() > 0` (spec: it works with the website closed).
- [ ] **Step 2:** Patch rows use `card()`:
  - title: `crop` (`"Unknown"` for an unknown patch)
  - value: the status (`Ready`, time left, `Diseased`, `Dead`, `Empty`, `Unknown`)
  - bar: `progress`
  - sub: `region[ · name] · travel`
  - edge: `DONE` when ready, `RED` when diseased or dead, `AMBER` otherwise
  - ★ saves to tab `Farming`, and ▣ uses `patchPin(id)`

  In each view, ready rows sort first.
- [ ] **Step 3:** Fill each view:
  - **All patches:** region headers that fold open, as today, with patch cards inside. Filtered by
    `shownInAll`.
  - **★ Favourites:** a flat list in the order saved. It is **not** filtered by hidden regions. Its header has
    ▣, which pins `groupPin("Farming")`.
  - **Special / All trees:** a flat list, filtered with `FarmGroups.special` / `tree`, and grouped under small
    headers per kind label (as in the owner's screenshot). The header ▣ pins `"farming:special"` /
    `"farming:trees"`.
  - **Farming contract:** one card from `Extras.contract`, with ★ and ▣ using `FarmGroups.CONTRACT_ID`.
  - **Kind chips:** today's numbered run order. Stop headers keep their teleport icon, and the patches under them
    are cards. The header ▣ pins `kindPin(kind)`. Old `farming:<Label>` pins still show the header as pinned.
- [ ] **Step 4:** Build the picker, which opens inline when you click `edit` on the `All patches` or `★ Favourites`
  header.
  - A toolbar with `Select all`, `Clear` and `Done`.
  - Each row becomes a check box plus its title.
  - Shift-click ticks every row from the last one clicked to this one.
  - The picker state lives in the panel until **Done**:
    - on All patches, Done sends `onRegionsSet(hidden regions)`
    - on Favourites, Done saves the ticked ids through `Favourites.setAll("Farming", ids)`. That list offers every
      patch plus the contract.

  Don't add a `JPopupMenu` here. The list must stay open between clicks.
- [ ] **Step 5:** Run `./gradlew build`. **Expected:** it passes. Then run `./gradlew run`. Ask the owner to check:
  - every chip
  - Select all and Clear, with ten regions changed through one open picker
  - shift-click
  - a contract row with the website closed
  - a diseased patch showing red

  Commit with the message `Farming tab: favourites, special, all trees, contract, and a picker that stays open`.

### Task 7: On-screen boxes, single pins and the contract on screen

**Files:**
- Modify: `src/main/java/com/nibtracker/GoalOverlay.java` (`Entry` gains `final String box`; `detailed` and
  `compact` draw one box per distinct `box`, in first-seen order, stacked with a 6px gap)
- Modify: `src/main/java/com/nibtracker/GoalTracker.java` (where `onScreen` entries are built)
- Test: `src/test/java/com/nibtracker/GoalLayoutTest.java` (or a new `OverlayBoxesTest`) for the pure grouping helper

**Interfaces:**
- Consumes: the pin ids from Task 4, the contract view from Task 3, and `Extras.favourites`.
- Produces: `static List<Map.Entry<String, List<Entry>>> GoalOverlay.boxes(List<Entry>)`, which keeps first-seen
  order. This is the grouping `render` uses. It's worked out in `set()`, not each frame.

- [ ] **Step 1: Write the failing test.** `boxes` turns entries with box A, B, A into
  `[A:[e1, e3], B:[e2]]`, and an empty list into an empty list.
- [ ] **Step 2:** Run `./gradlew test --tests com.nibtracker.OverlayBoxesTest`. **Expected:** FAIL.
- [ ] **Step 3:** Implement `boxes` and cache its result in `set()`. In each box, the heading is the box name
  (replacing `"NIB TRACKER"`), and the value line shows the ready or due count when there is one.
- [ ] **Step 4:** In `GoalTracker`, set each entry's box like this:

  | Pin | Box |
  |---|---|
  | goal | `Row.category` |
  | `daily:<id>` (only while due, like the whole-dailies pin) | `"Daily tasks"` |
  | `farm:patch:<id>` | `"Farming"` |
  | `farming:contract` | `"Farming"` |
  | `fav:<tab>` | `"★ " + tab + " favourites"`, one line per favourite |
  | `farming:special`, `farming:trees`, `farming:kind:<k>` | that group's label |
  | old `dailies`, `farming`, `farming:<Label>` | their old titles, unchanged |

  A pinned patch that's ready stays on screen until its `readyAt` or `state` changes. The contract entry uses
  `ContractView` (title, value, `ready`, and the item icon through `ItemManager`, loaded off the render path).
- [ ] **Step 5:** Run `./gradlew test`. **Expected:** PASS. Then run `./gradlew run`. Ask the owner to check:
  - one pinned patch, one pinned daily, the Farming favourites box and the contract box, each in its own box
  - Compact style
  - Alt-drag still moves the overlay

  Commit with the message `On-screen: a box per tab, single patch and daily pins, farming contract`.

### Task 8: Rules check and hand-over

- [ ] **Step 1:** Re-read `AGENTS.md` against the diff (`git diff <base>..HEAD`):
  - no reflection, no new menu entries that act in the game
  - no blocking IO on the client thread
  - new config keys are hidden
  - unused imports and the removed `regionsMenu` are cleaned up
- [ ] **Step 2:** Run `./gradlew build`. **Expected:** BUILD SUCCESSFUL.
- [ ] **Step 3:** Give the owner the full in-game checklist (the checks from Tasks 5–7 together) and wait for their
  confirmation before calling it done.
