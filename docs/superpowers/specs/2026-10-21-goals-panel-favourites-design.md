# Goals panel: consistent rows, favourites, pinning, farming groups and contracts

Date: 2026-10-21
Status: draft for approval

## Why

The Goals panel's Farming tab feels unrefined next to RuneLite's Time Tracking panel, and it's awkward to use:

- Hiding or showing farm regions goes through a right-click menu that closes after every tick, so changing ten regions
  takes ten right-clicks. There is no select all or clear.
- You can only put whole things on the game screen: all daily tasks, all of farming, or one patch kind. You can't pin
  the one patch or the one daily task you care about.
- There is no way to keep "my usual patches" together, no single view of the special patches, and trees, hardwood
  trees and redwoods are split into separate kinds.
- Farming contracts aren't shown at all.
- Rows look different from tab to tab. The owner wants less visual clutter without losing detail.

This is time-sensitive and comes before the web app work (see Later).

## Goals

1. **One row look across every tab** (Overview, Daily tasks, Farming, Bosses, Skills, Combat achievements, the
   rest). Every row has the same shape, size, fonts and colours:
   - the real icon on the left (item, skill, boss, crop or seed; never a coloured placeholder)
   - the title, with the value (time left, Ready, Done, 3 / 5, a percentage) on the right
   - a thin progress or growth bar
   - a quieter second line for details (for a patch: location and teleport, e.g. "Farming Guild · Skills necklace")
   - the coloured strip on the left edge that goal rows already have (green when done or ready)

   The existing goal row (`GoalsPanel.row`) is the base. Farming and daily rows are brought into that shape, not the
   other way round. Less clutter comes from muted, short second lines and the same spacing everywhere, not from
   hiding information. This matches mockup C, which the owner picked.
2. **★ and ▣ on every row.**
   - ★ adds the row to *that tab's* Favourites. Each tab has its own favourites; there is **no** combined
     cross-tab favourites list.
   - ▣ shows that one row on the game screen. Single patches and single daily tasks can now be pinned.
   - Both are direct clicks on the row. No menu needed. Filled or orange when on, muted when off.
3. **A picker that stays open** for picking many at once. "edit" on a group header opens it: a tick box per row, plus
   **Select all**, **Clear** and **Done**. It stays open until Done. It replaces the per-region right-click tick menu,
   and is also how you build a tab's Favourites in bulk.
4. **Farming tab groups**, as chips above the list, in this order: **★ Favourites**, **Special**, **All trees**,
   **Farming contract**, then the existing kind chips (Herbs, Allotments, Fruit trees, ...).
   - **Special** = exactly Hespori, Anima, Belladonna, Cactus, Coral, Seaweed, Mushroom, Calquat.
   - **All trees** = normal trees, hardwood trees and redwood together.
5. **Farming contract row.** You favourite or pin it once, and it keeps working whatever crop the contract switches
   to. It shows:
   - the contract crop's icon, name and patch, e.g. "Contract: Redwood · Farming Guild tree patch"
   - its state: not planted, growing (time left), ready, or diseased/dead
   - when ready: "Ready · then see Jane for a new contract"
   - with no contract: "No contract · talk to Jane"; contract done but reward not collected: "Collect reward from Jane"

   The row never disappears, so a favourited or pinned contract is always there.
6. **On the game screen**, pinned things use the style of the Favourites mockup: one box per tab you've pinned from,
   with a title line (e.g. "★ Favourites · 2 ready") and then one line per item with its value. A single pinned item
   is a one-line box.

## Non-goals

- A favourites list shared across tabs.
- The web app gear/progression screen. That's a separate project, brainstormed later.
- Changing how the web app works out runs, teleports or due times.

## Anti-goals (failure even if it works)

- Tabs that still look different from each other.
- Any picker or menu that closes after one tick.
- A favourited contract that stops showing when the contract crop changes, or that has to be favourited again.
- More clutter: extra icons or text that make rows taller or noisier than the goal rows are today.
- Breaking what people have already saved (current pins, hidden regions). See Constraints.

## Constraints

- RuneLite plugin hub rules in `AGENTS.md`: Java 11, no reflection, no blocking IO on the client thread, keep overlay
  `render` cheap, use gameval constants.
- **Saved settings must carry over.** `pinnedGoals` (ids like `dailies`, `farming`, `farming:<Kind>`, goal ids) and
  `hiddenFarmRegions` in config group `NibTrackerConfig.GROUP` must keep working, or be migrated. Never rename a key
  without a migration.
- Farm patch data comes from Nib Tracker (`Routines.farm`, built by the site's `plugExtra` in
  `nib-tracker-web/site/app.js`). Each patch carries a stable `kind` key (`herb`, `tree`, `hardwood`, `redwood`,
  `hespori`, `anima`, `belladonna`, `cactus`, `seaweed`, `mushroom`, `calquat`, ...). Note that the site maps coral to
  `seaweed`. Special and All trees should be worked out **in the plugin from `kind`**.
- **A small website change is part of this release (owner approved).** `plugExtra` currently sends only growing or
  ready patches (`ttRows(a).filter(r => r.ready || r.growing)`). It should also send empty, diseased, dead and
  unknown patches, each with a `state` field (`empty`, `growing`, `ready`, `diseased`, `dead`, `unknown`). The
  plugin then shows "not planted", "diseased/dead" and the "Unknown" special patches. The plugin must still work
  with an older feed that has no `state` field, treating it as today's growing/ready. Check the 150-patch cap in
  `plugExtra` and `Routines.farm` still fits.
- **RuneLite rules (owner's condition).** Nothing here adds anything the hub forbids. The feed covers only the
  player's own account, through the existing opt-in sync. No menu entries send actions to the server, no input is
  injected, and there's no boss or PvP information. The builder re-checks `AGENTS.md` before submitting.
- The farming contract should also be read **in the plugin**, not from the website. RuneLite's Time Tracking saves
  the current contract in the `timetracking` group of the character's profile; `FarmingTracker` already reads that
  group. The contract's state comes from the matching Farming Guild patch in the farm data. The builder must
  confirm the exact key and value format against RuneLite's `FarmingContractManager`.
- The owner tests in-game. Claude can't verify plugin behaviour itself (see `AGENTS.md` Testing).

## Decided

- Layout: mockup C (two-line rows, ★ and ▣ on every row), applied to every tab.
- Real icons in the left slot everywhere.
- Favourites are per tab.
- The Special group is exactly the eight listed above.
- Pinned items show on screen in the Favourites-box style.
- A contract crop that's ready also tells you to go back to Jane.

## Left to the builder (Claude's calls, flagged for the owner to check)

- **Where favourites are saved:** a new hidden config key per tab (e.g. `favourites.<tab>`) in the plugin's config
  group. Local, so it works with the website closed.
- **Ids:** farm patches are favourited or pinned by `region|name|kind`, daily tasks by their daily id, and the
  contract by a fixed id such as `farming:contract`.
- **Old pins:** existing `dailies`, `farming` and `farming:<Kind>` pins keep working as group pins.
- **Ready state:** ready rows sort to the top of a group. A pinned patch that's ready stays on screen until its timer
  changes (harvested or replanted).
- **Picker:** shift-click selects a range.
- **Hover detail:** tooltips keep the fuller detail (teleport instructions, how-to text) so the second line can stay
  short.
- **Daily tasks:** the Daily tasks tab gets ★ and ▣ too. A pinned single daily task leaves the screen once it's done,
  as the whole-dailies pin does today.
- **Size:** matches the existing overlay size and style settings (`overlaySize`, `overlayStyle`).
- **Bird houses:** they appear only under their own chip and All patches, not in Special or All trees.
- **Default Farming view:** an **All patches** chip comes first and is selected by default. It keeps today's grouping
  by region, with each patch as a C-style row inside. The kind chips keep the numbered run order, with each stop's
  teleport on the second line.
- **What the picker sets:** on the **Favourites** header, the ticks choose that tab's favourites. On the **All
  patches** header, the ticks choose which regions are shown. Hiding stays per region, so `hiddenFarmRegions` keeps
  its meaning. On-screen pins are always set with ▣, never through the picker.
- **On-screen boxes:**
  - Each tab gets one box, titled with the tab's name, holding everything ▣-pinned from that tab.
  - The Favourites group header also has ▣, which pins the whole favourites list as a live box ("★ Favourites ·
    2 ready").
  - Something pinned from Overview goes in its home tab's box.
  - Old group pins (`dailies`, `farming`, `farming:<Kind>`) keep drawing as their own boxes, as they do today.
  - The Compact overlay style shows icon and value per line.
- **Farming tab with no farm data:** the tab shows if there is farm data *or* a contract, so the contract row works
  with the website closed.
- **Group pin ids:** new group pins use the stable `kind` key (e.g. `farming:kind:hardwood`, `farming:special`,
  `farming:trees`). Existing label pins (`farming:Herbs` ...) keep matching on `kindLabel` and aren't merged into All
  trees.

## Later (separate work, not this release)

- The web app gets the Special group and the farming contract row too.
- The web app gear/progression project: the best gear from what you own, for skilling, bossing and skilling bosses,
  and several ways to progress your account.

## Reference

- Mockups: `../.superpowers/brainstorm/1910-1792572641/content/farming-panel.html` (in the parent folder).
- Current code: `GoalsPanel.java` (`row`, `farming`, `runOrder`, `regionsMenu`), `GoalTracker.java` (`extras`,
  `togglePin`, `hiddenRegions`), `GoalOverlay.java`, `Routines.java`, `FarmingTracker.java`.
- Farming contracts: https://oldschool.runescape.wiki/w/Farming_contracts
