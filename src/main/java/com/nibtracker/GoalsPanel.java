package com.nibtracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.http.api.item.ItemPrice;

/**
 * The Goals side panel: the character's goals from Nib Tracker in sections (skills, quests, bosses...), each with an
 * icon, a bar coloured by how far along it is, and what's left. Clicking a goal opens it in the Nib Tracker web app. Swing thread only.
 */
class GoalsPanel extends PluginPanel
{
	/** One goal to show. progress is 0..1, or -1 for a yes/no goal. */
	static final class Row
	{
		final String id;
		final String category;
		final String title;
		final String detail;
		final String hint;
		final double progress;
		final boolean done;
		final int points;
		final Skill skill;
		final String item;
		final int fallbackItem;
		final String link;
		/* The item's id when the web app sent one (works for untradeable items), else 0 and the name is searched. */
		int itemId;
		/* The main goal this is part of (or empty); whether the web app already counts it done; for a main goal, its
		   parts done and in total as the web app counted them. */
		String parent = "";
		boolean serverDone;
		int partsDone;
		int partsTotal;
		/* Shown under its goal but not a part of it (the current Slayer task); and whether it's shown on the game screen. */
		boolean extra;
		boolean pinned;
		/* What the view's sub-filter matches (a combat achievement or diary tier, a quest's state), or empty. */
		String tag = "";
		/* A one-line description (a combat achievement's task) and how to get there, for the game screen's hover text. */
		String info = "";
		String travel = "";

		Row(String id, String category, String title, String detail, String hint, double progress, boolean done, int points,
			Skill skill, String item, int fallbackItem, String link)
		{
			this.id = id;
			this.category = category;
			this.title = title;
			this.detail = detail;
			this.hint = hint;
			this.progress = progress;
			this.done = done;
			this.points = points;
			this.skill = skill;
			this.item = item;
			this.fallbackItem = fallbackItem;
			this.link = link;
		}
	}

	/**
	 * One row as the panel draws it, the same shape on every tab: an icon, the title with its value on the right, an
	 * optional bar (progress >= 0) and a quieter second line, a coloured strip on the left, and ★ / ▣ on the right.
	 */
	static final class Card
	{
		/* Its favourite id and the tab that favourite is saved under (null: no ★), and its game-screen pin (null: no ▣). */
		String id = "";
		String favTab;
		String pinId;
		/* Puts the row's real icon in the 28px slot (a goal's skill or item, a crop, a daily task's item); null leaves it empty. */
		Consumer<JLabel> icon;
		String title = "";
		/* Wrap a long title onto more lines instead of cutting it short (the contract's). */
		boolean wrapTitle;
		Color titleColour = Color.WHITE;
		String value = "";
		Color valueColour = MUTED;
		double progress = -1;
		boolean barDone;
		String sub = "";
		Color subColour = MUTED;
		String tooltip;
		Color edge = UNKNOWN;
		boolean fav;
		boolean pinned;
		JPopupMenu menu;
		Runnable click;
	}

	/* Each section shows this many goals until "Show more" is clicked. */
	private static final int SECTION_LIMIT = 6;
	private static final String COMPLETED = "Completed";
	private static final String DAILIES = "Daily tasks";
	private static final String FARMING = "Farming";
	private static final String OVERVIEW = "Overview";
	private static final String ALL = "All";
	/* The Farming tab's group chips, in order, before its kind chips (Favourites is drawn with a ★ in front). */
	private static final String ALL_PATCHES = "All patches";
	private static final String FAVOURITES = "Favourites";
	private static final String SPECIAL = "Special";
	private static final String ALL_TREES = "All trees";
	private static final String CONTRACT = "Farming contract";
	private static final List<String> FARM_GROUPS = Arrays.asList(ALL_PATCHES, FAVOURITES, SPECIAL, ALL_TREES, CONTRACT);
	/* Sub-filter values in the order they're offered. */
	private static final List<String> TAG_ORDER = Arrays.asList("Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster", "Not started", "In progress");
	private static final BufferedImage LOGO = ImageUtil.loadImageResource(GoalsPanel.class, "goals_icon.png");
	/* The combat achievements book, as Nib Tracker shows it (OSRS Wiki's Combat achievements detail image, made small). */
	static final BufferedImage CA_BOOK = ImageUtil.loadImageResource(GoalsPanel.class, "combat_achievements.png");
	/* How far a main goal's parts are indented under it. */
	private static final int PART_INDENT = 14;
	/* A value longer than this goes on a row's second line (the contract's "Ready · then see Jane..."). */
	private static final int SHORT_VALUE = 12;

	private static final Color RED = new Color(0xC8, 0x4B, 0x3C);
	private static final Color AMBER = new Color(0xE0, 0xA0, 0x30);
	private static final Color GREEN = new Color(0x4C, 0xB8, 0x50);
	private static final Color DONE = new Color(0x3F, 0xC2, 0x5F);
	private static final Color UNKNOWN = new Color(0x5A, 0x5A, 0x5A);
	private static final Color MUTED = new Color(0x9A, 0x9A, 0x9A);
	private static final Color HEADING = new Color(0xD8, 0xB6, 0x6A);
	private static final int ICON = 28;
	/* The text column beside the icon in the side panel, in pixels. */
	private static final int HINT_WIDTH = PluginPanel.PANEL_WIDTH - 92;

	private final ItemManager itemManager;
	private final SkillIconManager skillIcons;
	private final Map<String, Icon> icons = new HashMap<>();
	/* Sections folded away (Completed starts folded), sections showing all their goals, and main goals showing their parts. */
	private final Set<String> collapsed = new HashSet<>(Collections.singleton(COMPLETED));
	private final Set<String> showAll = new HashSet<>();
	private final Set<String> expanded = new HashSet<>();
	/* Called with a goal's id when it's put on or taken off the game screen. */
	private Consumer<String> onPin = id -> { };
	/* Called with a row's home tab and its id when its ★ is clicked. */
	private BiConsumer<String, String> onFavourite = (tab, id) -> { };
	/* Called with a daily task's id when it's ticked off, and with a region when it's shown or hidden. */
	private Consumer<String> onDailyDone = id -> { };
	private Consumer<String> onRegion = region -> { };
	/* Called by the Farming picker's Done: every hidden region at once, or a tab's favourites in order. */
	private Consumer<Set<String>> onRegionsSet = hidden -> { };
	private BiConsumer<String, List<String>> onFavouritesSet = (tab, ids) -> { };
	/* The Farming picker: the chip it's open on (null when shut), what's ticked, and the row last clicked for shift-click.
	   It lives here until Done, so the redraws while it's open (timers, a new feed) don't close it or lose the ticks. */
	private String picking;
	private final Set<String> picked = new LinkedHashSet<>();
	private String pickAnchor;
	/* The view picked in the tabs, each view's sub-filter, and whether the tabs are being rebuilt (so selecting doesn't redraw). */
	private String view = OVERVIEW;
	private final Map<String, String> filter = new HashMap<>();
	private boolean building;
	private final JPanel tabsHolder = new JPanel(new BorderLayout());
	private final JPanel filters = new JPanel(new GridLayout(0, 3, 4, 4));

	private final JLabel heading = new JLabel("Goals");
	private final JLabel summary = new JLabel();
	private final Bar overall = new Bar();
	private final JLabel status = new JLabel();
	private final JPanel list = new JPanel(new DynamicGridLayout(0, 1, 0, 4));

	private String lastTitle;
	private String lastMessage;
	private List<Row> lastRows = new ArrayList<>();
	private Extras lastExtras = new Extras();

	GoalsPanel(ItemManager itemManager, SkillIconManager skillIcons)
	{
		this.itemManager = itemManager;
		this.skillIcons = skillIcons;
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout(0, 10));
		// The scrollbar always shows, so the panel keeps its width when a tab is short enough not to need it (it
		// appearing and going made everything shift sideways when switching between Overview and other tabs).
		JScrollPane scroll = getScrollPane();
		if (scroll != null)
		{
			scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		}

		heading.setFont(FontManager.getRunescapeBoldFont());
		heading.setForeground(Color.WHITE);
		summary.setFont(FontManager.getRunescapeSmallFont());
		summary.setForeground(MUTED);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		overall.setPreferredSize(new Dimension(0, 6));

		JPanel top = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
		top.add(heading);
		top.add(summary);
		top.add(overall);
		top.add(tabsHolder);
		top.add(filters);
		top.add(status);
		add(top, BorderLayout.NORTH);
		add(list, BorderLayout.CENTER);
		show("Goals", "Log in with Sync to Nib Tracker on to see your goals here.", new ArrayList<>(), new Extras());
	}

	void show(String title, String message, List<Row> rows, Extras extras)
	{
		lastTitle = title;
		lastMessage = message;
		lastRows = rows;
		lastExtras = extras;
		heading.setText(title);
		status.setText(message == null ? "" : "<html>" + escape(message) + "</html>");
		status.setVisible(message != null);

		GoalLayout.Layout layout = GoalLayout.of(rows);
		List<GoalLayout.Group> open = new ArrayList<>();
		layout.sections.values().forEach(open::addAll);
		int done = layout.completed.size(), total = open.size() + done;
		double avg = total == 0 ? 0 : (done + open.stream().mapToDouble(g -> Math.max(0, g.progress())).sum()) / total;
		summary.setText(done + " of " + total + " goals done  ·  " + Math.round(avg * 100) + "% overall");
		summary.setVisible(total > 0);
		overall.set(avg, total > 0 && done == total);
		overall.setVisible(total > 0);

		// The views there's something to show in: Overview, each kind of goal, Daily tasks, Farming.
		List<String> views = new ArrayList<>();
		views.add(OVERVIEW);
		for (String c : GoalProgress.CATEGORIES)
		{
			if (layout.sections.containsKey(c) || layout.completed.stream().anyMatch(g -> c.equals(g.main.category)))
			{
				views.add(c);
			}
		}
		if (extras.dailiesOn && !extras.dailies.isEmpty())
		{
			views.add(DAILIES);
		}
		// Farm data or a contract: the contract row works with the website closed.
		if (extras.farmOn && (!extras.allRegions.isEmpty() || extras.contract != null))
		{
			views.add(FARMING);
		}
		if (!views.contains(view))
		{
			view = OVERVIEW;
		}
		tabs(views);

		list.removeAll();
		if (OVERVIEW.equals(view))
		{
			filters.setVisible(false);
			for (Map.Entry<String, List<GoalLayout.Group>> s : layout.sections.entrySet())
			{
				section(s.getKey(), s.getValue());
			}
			dailies(extras, true);
			farmingOverview(extras);
			if (!layout.completed.isEmpty())
			{
				section(COMPLETED, layout.completed);
			}
		}
		else if (DAILIES.equals(view))
		{
			filters(Arrays.asList(ALL, "Due", "Done"));
			dailies(extras, false);
		}
		else if (FARMING.equals(view))
		{
			// The groups first, then one chip per kind of patch you have a timer on (Herbs, Trees, Fruit trees, Hespori...),
			// each with its crop's icon.
			List<String> kinds = new ArrayList<>();
			Map<String, Integer> kindIcons = new HashMap<>();
			for (PatchView p : extras.patches)
			{
				if (!p.kindLabel.isEmpty() && !kinds.contains(p.kindLabel) && !FARM_GROUPS.contains(p.kindLabel))
				{
					kinds.add(p.kindLabel);
				}
				if (p.itemId > 0)
				{
					kindIcons.putIfAbsent(p.kindLabel, p.itemId);
				}
			}
			kinds.sort(String::compareTo);
			List<String> chips = new ArrayList<>(FARM_GROUPS);
			chips.addAll(kinds);
			kindIcons.put(SPECIAL, ItemID.HESPORI_SEED);
			kindIcons.put(ALL_TREES, ItemID.MAGIC_LOGS);
			kindIcons.put(CONTRACT, extras.contract != null && extras.contract.itemId > 0 ? extras.contract.itemId : ItemID.SEED_BOX);
			Map<String, Icon> fixed = new HashMap<>();
			fixed.put(ALL_PATCHES, farmingIcon(14));
			fixed.put(FAVOURITES, new Glyph(true, true));
			filters(chips, kindIcons, fixed, farmDefault(extras));
			farmingView(extras);
		}
		else
		{
			categoryView(view, layout);
		}
		// The header's grid layout keeps room for hidden components, so hidden chips are removed, not just hidden;
		// otherwise leaving Farming left a blank block the size of its chips under the tabs.
		if (!filters.isVisible())
		{
			filters.removeAll();
		}
		// The tabs and filter chips above the list change height too (Farming has rows of chips, Overview none), so the
		// whole panel is laid out and painted again; repainting only the list left old chips drawn over the goals.
		revalidate();
		repaint();
	}

	private void redraw()
	{
		show(lastTitle, lastMessage, lastRows, lastExtras);
	}

	/** The icon tabs along the top, as RuneLite's Time Tracking has: Overview, then each kind of goal there is. */
	private void tabs(List<String> views)
	{
		tabsHolder.removeAll();
		MaterialTabGroup group = new MaterialTabGroup();
		group.setLayout(new GridLayout(0, 6, 4, 4));
		MaterialTab selected = null;
		for (String v : views)
		{
			MaterialTab tab = new MaterialTab("", group, null);
			tabIcon(v, tab);
			tab.setToolTipText(v);
			tab.setPreferredSize(new Dimension(30, 30));
			tab.setOnSelectEvent(() -> {
				if (!building && !v.equals(view))
				{
					view = v;
					picking = null;
					// A new tab starts at its top, not wherever the last one was scrolled to.
					SwingUtilities.invokeLater(() -> {
						redraw();
						JScrollPane scroll = getScrollPane();
						if (scroll != null)
						{
							scroll.getVerticalScrollBar().setValue(0);
						}
					});
				}
				return true;
			});
			group.addTab(tab);
			if (v.equals(view))
			{
				selected = tab;
			}
		}
		building = true;
		if (selected != null)
		{
			group.select(selected);
		}
		building = false;
		tabsHolder.add(group, BorderLayout.CENTER);
		tabsHolder.revalidate();
	}

	private void tabIcon(String v, MaterialTab tab)
	{
		if (OVERVIEW.equals(v))
		{
			tab.setIcon(new ImageIcon(fitTo(LOGO, 22)));
			return;
		}
		if (DAILIES.equals(v) && itemManager != null)
		{
			itemIcon(ItemID.BATTLESTAFF, 24, tab::setIcon);
			return;
		}
		if ("Combat achievements".equals(v))
		{
			tab.setIcon(new ImageIcon(fitTo(CA_BOOK, 24)));
			return;
		}
		Skill skill = FARMING.equals(v) ? Skill.FARMING : "Bosses".equals(v) ? Skill.SLAYER : null;
		if (skill != null)
		{
			tab.setIcon(new ImageIcon(fitTo(skillIcons.getSkillImage(skill, false), 22)));
			return;
		}
		int item = "Skills".equals(v) ? ItemID.SKILLCAPE_MAX : fallbackItem(v);
		if (item > 0 && itemManager != null)
		{
			itemIcon(item, 24, tab::setIcon);
		}
		else
		{
			tab.setIcon(new ImageIcon(fitTo(LOGO, 22)));
		}
	}

	/** The sub-filters for the current view (tiers, quest state, patch type...), as small clickable chips. */
	private void filters(List<String> options)
	{
		filters(options, Collections.emptyMap(), Collections.emptyMap(), options.isEmpty() ? ALL : options.get(0));
	}

	/**
	 * Sub-filter chips in a grid that wraps onto as many rows as it needs, so every option shows (farming has many patch
	 * kinds). Chips can carry an item icon (a seed or crop for each patch kind) or a fixed one (the Farming skill, ★).
	 * The given default is shown until a chip is picked; it isn't saved, so it can follow the data.
	 */
	private void filters(List<String> options, Map<String, Integer> iconItems, Map<String, Icon> fixedIcons, String byDefault)
	{
		filters.removeAll();
		filters.setLayout(new GridLayout(0, iconItems.isEmpty() && fixedIcons.isEmpty() ? 3 : 2, 4, 4));
		String first = options.contains(byDefault) ? byDefault : options.isEmpty() ? ALL : options.get(0);
		String current = filter.getOrDefault(view, first);
		if (!options.contains(current))
		{
			current = first;
			filter.remove(view);
		}
		if (options.size() <= 1)
		{
			filters.setVisible(false);
			return;
		}
		for (String o : options)
		{
			boolean on = o.equals(current);
			JLabel chip = new JLabel(o);
			chip.setFont(FontManager.getRunescapeSmallFont());
			chip.setForeground(on ? Color.WHITE : MUTED);
			chip.setOpaque(true);
			chip.setBackground(on ? ColorScheme.BRAND_ORANGE.darker() : ColorScheme.DARKER_GRAY_COLOR);
			chip.setBorder(new EmptyBorder(3, 5, 3, 5));
			Integer item = iconItems.get(o);
			Icon fixed = fixedIcons.get(o);
			if (fixed != null)
			{
				chip.setIcon(fixed);
				chip.setIconTextGap(4);
			}
			else if (item != null && item > 0 && itemManager != null)
			{
				itemIcon(item, 14, chip::setIcon);
				chip.setIconTextGap(4);
			}
			chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			onClick(chip, () -> {
				picking = null;
				filter.put(view, o);
				redraw();
			});
			filters.add(chip);
		}
		filters.setVisible(true);
		filters.revalidate();
	}

	private String currentFilter()
	{
		return filter.getOrDefault(view, ALL);
	}

	/** One kind of goal on its own: all of them (no "Show more"), filtered by tier or state where that applies. */
	private void categoryView(String category, GoalLayout.Layout layout)
	{
		List<GoalLayout.Group> open = layout.sections.getOrDefault(category, Collections.emptyList());
		List<GoalLayout.Group> finished = new ArrayList<>();
		for (GoalLayout.Group g : layout.completed)
		{
			if (category.equals(g.main.category))
			{
				finished.add(g);
			}
		}
		List<String> tags = new ArrayList<>();
		tags.add(ALL);
		for (String t : TAG_ORDER)
		{
			if (open.stream().anyMatch(g -> t.equals(g.main.tag)) || finished.stream().anyMatch(g -> t.equals(g.main.tag)))
			{
				tags.add(t);
			}
		}
		filters(tags);
		String f = currentFilter();
		// This tab's favourites first, whatever the sub-filter: each favourited goal, main goal or part, on its own.
		Set<String> favs = favourites(category);
		if (!favs.isEmpty())
		{
			List<Card> cards = new ArrayList<>();
			List<GoalLayout.Group> all = new ArrayList<>(open);
			all.addAll(finished);
			for (GoalLayout.Group g : all)
			{
				if (favs.contains(g.main.id))
				{
					cards.add(mainCard(g));
				}
				for (Row x : g.extras)
				{
					if (favs.contains(x.id))
					{
						cards.add(goalCard(x, x.progress, x.detail));
					}
				}
				for (Row x : g.parts)
				{
					if (favs.contains(x.id))
					{
						cards.add(goalCard(x, x.progress, x.detail));
					}
				}
			}
			favouritesSection(category, cards);
		}
		for (GoalLayout.Group g : open)
		{
			if (ALL.equals(f) || f.equals(g.main.tag))
			{
				group(g);
			}
		}
		List<GoalLayout.Group> shownDone = new ArrayList<>();
		for (GoalLayout.Group g : finished)
		{
			if (ALL.equals(f) || f.equals(g.main.tag))
			{
				shownDone.add(g);
			}
		}
		if (!shownDone.isEmpty())
		{
			list.add(sectionHeader(COMPLETED, shownDone.size()));
			if (!collapsed.contains(COMPLETED))
			{
				shownDone.forEach(this::group);
			}
		}
		if (open.isEmpty() && shownDone.isEmpty())
		{
			list.add(note("Nothing here yet."));
		}
	}

	/** Daily tasks and farm timers for the panel, worked out by GoalTracker. */
	static final class Extras
	{
		boolean dailiesOn;
		boolean farmOn;
		boolean dailiesPinned;
		boolean farmPinned;
		final List<DailyView> dailies = new ArrayList<>();
		/* Shown regions in order (soonest first) with their patches, and every patch on its own, hidden regions' too
		   (hiding is for All patches only), ready first. */
		final List<Region> regions = new ArrayList<>();
		final List<PatchView> patches = new ArrayList<>();
		/* Every region with a timer, shown or not, for the All patches picker. */
		final List<String> allRegions = new ArrayList<>();
		final Set<String> hiddenRegions = new HashSet<>();
		/* The Farming Guild contract row, or null when there's neither farm data nor a contract. */
		FarmGroups.ContractView contract;
		/* Each tab's favourite row ids, and everything on the game screen (goal ids, "daily:<id>", group pins). */
		final Map<String, Set<String>> favourites = new HashMap<>();
		final Set<String> pins = new HashSet<>();
	}

	static final class DailyView
	{
		final String id;
		final String label;
		final String status;
		final String how;
		final boolean due;
		/* Its icon item (see DailySignals.icon). */
		final int itemId;

		DailyView(String id, String label, String status, String how, boolean due, int itemId)
		{
			this.id = id;
			this.label = label;
			this.status = status;
			this.how = how;
			this.due = due;
			this.itemId = itemId;
		}
	}

	static final class PatchView
	{
		final String region;
		final String crop;
		final String type;
		final String status;
		final String travel;
		final String travelHow;
		final boolean ready;
		final int itemId;
		/* Its kind's label (Herbs), its stop in the run order (0 = not planned), and the teleport's kind and item. */
		String kindLabel = "";
		int order;
		String travelCat = "";
		String travelItem = "";
		/* Which patch (region|name|kind, its ★ and ▣ id), its name in the region, its kind key (herb, hardwood...), its
		   state (empty, growing, ready, diseased, dead, unknown), growth 0..1 (-1 unknown), and whether it's diseased or dead. */
		String id = "";
		String name = "";
		String kind = "";
		String state = "";
		double progress = -1;
		boolean attention;

		PatchView(String region, String crop, String type, String status, String travel, String travelHow, boolean ready, int itemId)
		{
			this.region = region;
			this.crop = crop;
			this.type = type;
			this.status = status;
			this.travel = travel;
			this.travelHow = travelHow;
			this.ready = ready;
			this.itemId = itemId;
		}
	}

	static final class Region
	{
		final String name;
		final String summary;
		final boolean anyReady;
		final List<PatchView> patches = new ArrayList<>();

		Region(String name, String summary, boolean anyReady)
		{
			this.name = name;
			this.summary = summary;
			this.anyReady = anyReady;
		}
	}

	/** Daily tasks: due ones first, each with its status; right-click to tick one off or put them on screen. */
	private void dailies(Extras x, boolean overview)
	{
		if (!x.dailiesOn || x.dailies.isEmpty())
		{
			return;
		}
		String f = overview ? ALL : currentFilter();
		if (overview)
		{
			JComponent header = sectionHeader(DAILIES, DAILIES, (int) x.dailies.stream().filter(d -> d.due).count(), headingIcon(ItemID.BATTLESTAFF));
			header.setComponentPopupMenu(dailiesMenu(x, null));
			inheritMenu(header);
			list.add(header);
			if (collapsed.contains(DAILIES))
			{
				return;
			}
		}
		else
		{
			Set<String> favs = favourites(DAILIES);
			List<Card> cards = new ArrayList<>();
			for (DailyView d : x.dailies)
			{
				if (favs.contains(d.id))
				{
					cards.add(dailyCard(x, d, false));
				}
			}
			favouritesSection(DAILIES, cards);
		}
		for (DailyView d : x.dailies)
		{
			if ("Due".equals(f) && !d.due || "Done".equals(f) && d.due)
			{
				continue;
			}
			list.add(card(dailyCard(x, d, overview), 0));
		}
	}

	/** A daily task as a row: its item's icon, its status, how to do it (on its own tab), ★ and ▣ for just this task. */
	private Card dailyCard(Extras x, DailyView d, boolean overview)
	{
		Card c = new Card();
		c.id = d.id;
		c.favTab = DAILIES;
		c.pinId = Favourites.dailyPin(d.id);
		c.fav = favourites(DAILIES).contains(d.id);
		c.pinned = x.pins.contains(c.pinId);
		c.icon = itemManager != null ? l -> itemIcon(d.itemId, ICON, l::setIcon) : null;
		c.title = d.label;
		c.value = d.status;
		c.edge = d.due ? AMBER : DONE;
		c.valueColour = d.due ? MUTED : DONE;
		c.sub = overview ? "" : d.how;
		c.tooltip = d.how.isEmpty() ? null : "<html>" + escape(d.label) + "<br>" + escape(d.how) + "</html>";
		c.menu = dailiesMenu(x, d);
		return c;
	}

	/** This tab's favourites, as a ★ FAVOURITES section at the top of the tab (nothing when it has none). */
	private void favouritesSection(String tab, List<Card> cards)
	{
		if (cards.isEmpty())
		{
			return;
		}
		String key = "fav:" + tab;
		// ▣ on the header puts the whole list on the game screen.
		String pin = Favourites.groupPin(tab);
		list.add(sectionHeader(key, FAVOURITES, cards.size(), new Glyph(true, true), null, pin, lastExtras.pins.contains(pin)));
		if (!collapsed.contains(key))
		{
			cards.forEach(c -> list.add(card(c, 0)));
		}
	}

	private Set<String> favourites(String tab)
	{
		return lastExtras.favourites.getOrDefault(tab, Collections.emptySet());
	}

	private JPopupMenu dailiesMenu(Extras x, DailyView d)
	{
		JPopupMenu m = new JPopupMenu();
		if (d != null && d.due)
		{
			m.add(item("Mark done", () -> onDailyDone.accept(d.id)));
		}
		m.add(item(x.dailiesPinned ? "Remove daily tasks from game screen" : "Show daily tasks on game screen", () -> onPin.accept(GoalTracker.DAILIES)));
		return m;
	}

	/** The Farming chip shown until one is picked: the contract when there's no farm data but a contract, else All patches. */
	private static String farmDefault(Extras x)
	{
		return x.allRegions.isEmpty() && x.contract != null ? CONTRACT : ALL_PATCHES;
	}

	/** Overview's Farming section: the shown regions, soonest first, each folding open to its patches. */
	private void farmingOverview(Extras x)
	{
		if (!x.farmOn || x.allRegions.isEmpty())
		{
			return;
		}
		JComponent header = sectionHeader(FARMING, x.regions.size());
		header.setComponentPopupMenu(farmMenu(x));
		inheritMenu(header);
		list.add(header);
		if (!collapsed.contains(FARMING))
		{
			regionRows(x);
		}
	}

	/** The Farming tab: the view its chip picks. Ready rows come first in each. */
	private void farmingView(Extras x)
	{
		String f = filter.getOrDefault(FARMING, farmDefault(x));
		switch (f)
		{
			case ALL_PATCHES:
				allPatches(x);
				break;
			case FAVOURITES:
				farmFavourites(x);
				break;
			case SPECIAL:
				farmGroup(x, SPECIAL, Favourites.SPECIAL_PIN, FarmGroups::special, "No special patches with timers yet.", true);
				break;
			case ALL_TREES:
				farmGroup(x, ALL_TREES, Favourites.TREES_PIN, FarmGroups::tree, "No tree patches with timers yet.", false);
				break;
			case CONTRACT:
				if (x.contract != null)
				{
					list.add(card(contractCard(x), 0));
				}
				break;
			default:
				runOrder(x, f);
		}
	}

	/** All patches: the shown regions folding open to their patches; "edit" opens the picker of which regions show. */
	private void allPatches(Extras x)
	{
		String key = "farm:" + ALL_PATCHES;
		boolean picker = ALL_PATCHES.equals(picking);
		Runnable edit = picker || x.allRegions.isEmpty() ? null : () -> {
			List<String> shown = new ArrayList<>(x.allRegions);
			shown.removeAll(x.hiddenRegions);
			openPicker(key, ALL_PATCHES, shown);
		};
		list.add(sectionHeader(key, ALL_PATCHES, x.regions.size(), null, edit, GoalTracker.FARMING, x.farmPinned));
		if (collapsed.contains(key))
		{
			return;
		}
		if (picker)
		{
			picker(x.allRegions, x.allRegions, () -> onRegionsSet.accept(FarmRows.hiddenAfter(x.allRegions, x.hiddenRegions, picked)));
			return;
		}
		if (x.allRegions.isEmpty())
		{
			list.add(note("No farm timers from Nib Tracker yet."));
		}
		else if (x.regions.isEmpty())
		{
			list.add(note("Every region is hidden. Click edit to show some."));
		}
		regionRows(x);
	}

	/** Each shown region as a row that folds open to its patches, ready first. Right-click hides one region. */
	private void regionRows(Extras x)
	{
		for (Region r : x.regions)
		{
			String key = "farm:" + r.name;
			boolean open = expanded.contains(key);
			boolean attention = r.patches.stream().anyMatch(p -> p.attention);
			// The region's icon is its soonest patch's crop.
			Card row = simpleCard(r.patches.isEmpty() ? 0 : r.patches.get(0).itemId, (open ? "– " : "+ ") + r.name, r.summary,
				r.anyReady ? DONE : attention ? RED : AMBER);
			row.click = () -> {
				if (!expanded.remove(key))
				{
					expanded.add(key);
				}
				redraw();
			};
			JPopupMenu m = farmMenu(x);
			m.insert(item("Hide " + r.name, () -> onRegion.accept(r.name)), 0);
			String regionPin = Favourites.regionPin(r.name);
			m.insert(item(x.pins.contains(regionPin) ? "Remove from game screen" : "Show on game screen", () -> onPin.accept(regionPin)), 0);
			row.menu = m;
			list.add(card(row, 0));
			if (open)
			{
				for (PatchView p : FarmRows.readyFirst(r.patches, v -> v.ready))
				{
					list.add(card(patchCard(x, p), PART_INDENT));
				}
			}
		}
	}

	/**
	 * ★ Favourites: the tab's favourite patches and the contract in the order saved (ready first), hidden regions
	 * included. "edit" opens the picker of every patch plus the contract; ▣ on the header puts the list on screen.
	 */
	private void farmFavourites(Extras x)
	{
		String key = "fav:" + FARMING;
		Set<String> favs = favourites(FARMING);
		Map<String, PatchView> byId = new HashMap<>();
		for (PatchView p : x.patches)
		{
			byId.putIfAbsent(p.id, p);
		}
		List<String> shown = new ArrayList<>();
		for (String id : favs)
		{
			if (FarmGroups.CONTRACT_ID.equals(id) ? x.contract != null : byId.containsKey(id))
			{
				shown.add(id);
			}
		}
		boolean picker = FAVOURITES.equals(picking);
		String pin = Favourites.groupPin(FARMING);
		list.add(sectionHeader(key, FAVOURITES, shown.size(), new Glyph(true, true),
			picker ? null : () -> openPicker(key, FAVOURITES, favs), pin, x.pins.contains(pin)));
		if (collapsed.contains(key))
		{
			return;
		}
		if (picker)
		{
			List<String> ids = new ArrayList<>();
			List<String> labels = new ArrayList<>();
			if (x.contract != null)
			{
				ids.add(FarmGroups.CONTRACT_ID);
				labels.add(x.contract.title);
			}
			List<PatchView> all = new ArrayList<>(x.patches);
			all.sort(Comparator.comparing((PatchView p) -> p.region).thenComparing(p -> p.crop));
			Set<String> seen = new HashSet<>(ids);
			for (PatchView p : all)
			{
				if (seen.add(p.id))
				{
					ids.add(p.id);
					labels.add(p.crop + " · " + FarmRows.sub(p.region, p.name, ""));
				}
			}
			picker(ids, labels, () -> onFavouritesSet.accept(FARMING, FarmRows.favouritesAfter(favs, ids, picked)));
			return;
		}
		if (shown.isEmpty())
		{
			list.add(note("No favourites yet. Use the star, or edit."));
			return;
		}
		for (String id : FarmRows.readyFirst(shown, id -> FarmGroups.CONTRACT_ID.equals(id) ? x.contract.ready : byId.get(id).ready))
		{
			list.add(card(FarmGroups.CONTRACT_ID.equals(id) ? contractCard(x) : patchCard(x, byId.get(id)), 0));
		}
	}

	/** Special (under a heading per kind) or All trees (one flat list, ready first): hidden regions too (hiding is for All patches only). */
	private void farmGroup(Extras x, String name, String pin, Predicate<String> kinds, String none, boolean byKind)
	{
		List<PatchView> ps = new ArrayList<>();
		for (PatchView p : x.patches)
		{
			if (kinds.test(p.kind))
			{
				ps.add(p);
			}
		}
		String key = "farm:" + name;
		list.add(sectionHeader(key, name, ps.size(), null, null, pin, x.pins.contains(pin)));
		if (collapsed.contains(key))
		{
			return;
		}
		if (ps.isEmpty())
		{
			list.add(note(none));
			return;
		}
		if (!byKind)
		{
			for (PatchView p : FarmRows.readyFirst(ps, v -> v.ready))
			{
				list.add(card(patchCard(x, p), 0));
			}
			return;
		}
		Map<String, List<PatchView>> groups = new TreeMap<>();
		for (PatchView p : ps)
		{
			groups.computeIfAbsent(p.kindLabel.isEmpty() ? p.kind : p.kindLabel, k -> new ArrayList<>()).add(p);
		}
		for (Map.Entry<String, List<PatchView>> e : groups.entrySet())
		{
			list.add(subHeader(e.getKey(), e.getValue().size()));
			for (PatchView p : FarmRows.readyFirst(e.getValue(), v -> v.ready))
			{
				list.add(card(patchCard(x, p), 0));
			}
		}
	}

	/** A small heading inside a group (one kind of patch), with its count; it doesn't fold. */
	private static JComponent subHeader(String name, int count)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.setBorder(new EmptyBorder(4, 4, 0, 2));
		JLabel l = new JLabel(name);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(MUTED);
		JLabel n = new JLabel(String.valueOf(count));
		n.setFont(FontManager.getRunescapeSmallFont());
		n.setForeground(MUTED);
		p.add(l, BorderLayout.WEST);
		p.add(n, BorderLayout.EAST);
		return p;
	}

	/** A patch as a row: crop, status, growth bar, "region · name · travel"; green when ready, red when diseased or dead. */
	private Card patchCard(Extras x, PatchView p)
	{
		Card c = new Card();
		c.id = p.id;
		c.favTab = FARMING;
		c.pinId = Favourites.patchPin(p.id);
		c.fav = favourites(FARMING).contains(p.id);
		c.pinned = x.pins.contains(c.pinId);
		c.icon = cropIcon(p.itemId);
		c.title = p.crop;
		c.value = p.status;
		c.edge = p.ready ? DONE : p.attention ? RED : AMBER;
		c.valueColour = p.ready ? DONE : p.attention ? RED : MUTED;
		c.progress = p.progress;
		c.barDone = p.ready;
		c.sub = FarmRows.sub(p.region, p.name, p.travel);
		c.tooltip = p.travelHow.isEmpty() ? null : "<html>" + escape(p.crop) + "<br><div style='width:180px'>" + escape(p.travelHow) + "</div></html>";
		return c;
	}

	/**
	 * The Farming Guild contract as a row, whatever crop it is now (its id never changes). A long value ("Ready · then see
	 * Jane for a new contract") goes on the second line, where it has room, and the title wraps rather than being cut.
	 */
	private Card contractCard(Extras x)
	{
		FarmGroups.ContractView v = x.contract;
		Card c = new Card();
		c.id = FarmGroups.CONTRACT_ID;
		c.favTab = FARMING;
		c.pinId = FarmGroups.CONTRACT_ID;
		c.fav = favourites(FARMING).contains(c.id);
		c.pinned = x.pins.contains(c.pinId);
		c.icon = cropIcon(v.itemId);
		c.title = v.title;
		c.wrapTitle = true;
		Color state = v.ready ? DONE : v.attention ? RED : MUTED;
		c.edge = v.ready ? DONE : v.attention ? RED : AMBER;
		if (v.value.length() <= SHORT_VALUE)
		{
			c.value = v.value;
			c.valueColour = state;
			c.sub = v.sub;
		}
		else
		{
			c.sub = v.sub.isEmpty() ? v.value : v.value + "  ·  " + v.sub;
			c.subColour = state;
		}
		c.progress = v.progress;
		c.barDone = v.ready;
		c.tooltip = "<html>" + escape(v.title) + "<br>" + escape(v.value) + (v.sub.isEmpty() ? "" : "<br>" + escape(v.sub)) + "</html>";
		return c;
	}

	/** A crop's icon in the row's slot, or the Farming skill's when the patch has no crop item (empty, unknown). */
	private Consumer<JLabel> cropIcon(int itemId)
	{
		if (itemId > 0 && itemManager != null)
		{
			return l -> itemIcon(itemId, ICON, l::setIcon);
		}
		return l -> l.setIcon(icons.computeIfAbsent("skill:FARMING", k -> new ImageIcon(fit(skillIcons.getSkillImage(Skill.FARMING, false)))));
	}

	private Icon farmingIcon(int size)
	{
		return icons.computeIfAbsent("skill:FARMING:" + size, k -> new ImageIcon(fitTo(skillIcons.getSkillImage(Skill.FARMING, false), size)));
	}

	private JPopupMenu farmMenu(Extras x)
	{
		JPopupMenu m = new JPopupMenu();
		m.add(item(x.farmPinned ? "Remove farming from game screen" : "Show farming on game screen", () -> onPin.accept(GoalTracker.FARMING)));
		return m;
	}

	/** Opens the picker on this chip's list with these ticked, unfolding its section. */
	private void openPicker(String key, String chip, Collection<String> ticked)
	{
		picking = chip;
		picked.clear();
		picked.addAll(ticked);
		pickAnchor = null;
		collapsed.remove(key);
		redraw();
	}

	/**
	 * The picker, inline under its header: Select all, Clear and Done, then a tick box per row. It stays open between
	 * clicks (no popup menu to close) and keeps its ticks in the panel until Done, which hands them on and shuts it.
	 * Shift-click ticks or unticks every row from the last one clicked.
	 */
	private void picker(List<String> ids, List<String> labels, Runnable done)
	{
		List<JCheckBox> boxes = new ArrayList<>();
		Runnable sync = () -> {
			for (int i = 0; i < boxes.size(); i++)
			{
				boxes.get(i).setSelected(picked.contains(ids.get(i)));
			}
		};
		JPanel bar = new JPanel(new GridLayout(1, 3, 4, 0));
		bar.setOpaque(false);
		bar.add(pickerButton("Select all", false, () -> {
			picked.addAll(ids);
			sync.run();
		}));
		bar.add(pickerButton("Clear", false, () -> {
			picked.removeAll(ids);
			sync.run();
		}));
		bar.add(pickerButton("Done", true, () -> {
			done.run();
			picking = null;
			picked.clear();
			pickAnchor = null;
			redraw();
		}));
		list.add(bar);
		list.add(note("Shift-click ticks a range."));
		for (int i = 0; i < ids.size(); i++)
		{
			int index = i;
			JCheckBox box = new JCheckBox(labels.get(i), picked.contains(ids.get(i)));
			box.setFont(FontManager.getRunescapeSmallFont());
			box.setForeground(Color.WHITE);
			box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			box.setFocusPainted(false);
			box.setBorder(new EmptyBorder(3, 6, 3, 6));
			box.setToolTipText(labels.get(i));
			box.addActionListener(e -> {
				boolean shift = (e.getModifiers() & ActionEvent.SHIFT_MASK) != 0;
				// The anchor is held by id: a feed refresh can redraw the list in another order mid-pick (gone: -1, one row).
				FarmRows.tickRange(ids, shift && pickAnchor != null ? ids.indexOf(pickAnchor) : -1, index, box.isSelected(), picked);
				pickAnchor = ids.get(index);
				sync.run();
			});
			boxes.add(box);
			list.add(box);
		}
	}

	private static JLabel pickerButton(String text, boolean main, Runnable action)
	{
		JLabel b = new JLabel(text, SwingConstants.CENTER);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setForeground(Color.WHITE);
		b.setOpaque(true);
		b.setBackground(main ? ColorScheme.BRAND_ORANGE.darker() : ColorScheme.DARKER_GRAY_COLOR);
		b.setBorder(new EmptyBorder(4, 5, 4, 5));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		onClick(b, action);
		return b;
	}

	/**
	 * One kind of patch (Herbs, say) as a run: stops in the order Nib Tracker's farm run planner works out with your bank,
	 * each numbered with the teleport's icon, and its patches under it as rows. Stops the planner didn't place go last.
	 * ▣ on the header puts the kind on the game screen.
	 */
	private void runOrder(Extras x, String label)
	{
		List<PatchView> ps = new ArrayList<>();
		for (PatchView p : x.patches)
		{
			if (label.equals(p.kindLabel))
			{
				ps.add(p);
			}
		}
		ps.sort(Comparator.comparingInt((PatchView p) -> p.order > 0 ? p.order : Integer.MAX_VALUE).thenComparing(p -> p.region));
		Map<String, List<PatchView>> stops = new LinkedHashMap<>();
		for (PatchView p : ps)
		{
			stops.computeIfAbsent(p.order + "|" + p.region, k -> new ArrayList<>()).add(p);
		}
		// The pin is the kind's key; an old "farming:Herbs" pin still shows as pinned, and is what a click takes off.
		String legacy = GoalTracker.FARMING + ":" + label;
		String kind = ps.isEmpty() ? "" : ps.get(0).kind;
		boolean hasLegacy = x.pins.contains(legacy);
		String pin = hasLegacy || kind.isEmpty() ? legacy : Favourites.kindPin(kind);
		boolean pinned = hasLegacy || !kind.isEmpty() && x.pins.contains(Favourites.kindPin(kind));
		String key = "farm:kind:" + label;
		list.add(sectionHeader(key, "Run order  ·  " + stops.size() + (stops.size() == 1 ? " stop" : " stops"), ps.size(),
			farmingIcon(14), null, pin, pinned));
		if (collapsed.contains(key))
		{
			return;
		}
		int n = 0;
		for (List<PatchView> stop : stops.values())
		{
			n++;
			PatchView first = stop.get(0);
			long r = stop.stream().filter(p -> p.ready).count();
			JPanel head = new JPanel(new BorderLayout(6, 0));
			head.setOpaque(false);
			head.setBorder(new EmptyBorder(4, 4, 0, 2));
			JLabel t = new JLabel(n + "   " + first.region);
			t.setFont(FontManager.getRunescapeSmallFont());
			t.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			t.setIconTextGap(5);
			teleIcon(first, t);
			JLabel s = new JLabel(r > 0 ? r + " ready" : "");
			s.setFont(FontManager.getRunescapeSmallFont());
			s.setForeground(DONE);
			head.add(t, BorderLayout.CENTER);
			head.add(s, BorderLayout.EAST);
			head.setToolTipText(first.travel.isEmpty() ? null : "<html>" + escape(first.travel)
				+ (first.travelHow.isEmpty() ? "" : "<br><div style='width:180px'>" + escape(first.travelHow) + "</div>") + "</html>");
			list.add(head);
			for (PatchView p : FarmRows.readyFirst(stop, v -> v.ready))
			{
				list.add(card(patchCard(x, p), PART_INDENT));
			}
		}
		if (ps.isEmpty())
		{
			list.add(note("No patches of this kind."));
		}
	}

	/** The icon for a teleport: its item (a cape, a tablet...), or one for its kind (fairy ring, spell, spirit tree, house). */
	private void teleIcon(PatchView p, JLabel label)
	{
		BufferedImage fixed = null;
		int item = -1;
		switch (p.travelCat)
		{
			case "FAIRY_RING":
				item = ItemID.DRAMEN_STAFF;
				break;
			case "SPIRIT_TREE":
				item = ItemID.SPIRIT_TREE_SEED;
				break;
			case "SPELLBOOK":
				fixed = skillIcons.getSkillImage(Skill.MAGIC, true);
				break;
			case "PORTAL_NEXUS":
			case "HOUSE":
				fixed = skillIcons.getSkillImage(Skill.CONSTRUCTION, true);
				break;
			default:
				if (!p.travelItem.isEmpty() && itemManager != null)
				{
					for (ItemPrice ip : itemManager.search(p.travelItem))
					{
						if (ip.getName().equalsIgnoreCase(p.travelItem))
						{
							item = ip.getId();
							break;
						}
					}
				}
				if (item <= 0)
				{
					fixed = skillIcons.getSkillImage(Skill.MAGIC, true);
				}
		}
		if (fixed != null)
		{
			label.setIcon(new ImageIcon(fitTo(fixed, 14)));
		}
		else if (item > 0 && itemManager != null)
		{
			itemIcon(item, 16, label::setIcon);
		}
	}

	private static JMenuItem item(String text, Runnable run)
	{
		JMenuItem i = new JMenuItem(text);
		i.addActionListener(e -> run.run());
		return i;
	}

	private static JLabel note(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(MUTED);
		l.setBorder(new EmptyBorder(4, 2, 4, 2));
		return l;
	}

	/** A plain row (a farm region): its crop's icon, a title, a status on the right, a coloured edge. */
	private Card simpleCard(int itemId, String title, String value, Color edge)
	{
		Card c = new Card();
		c.icon = cropIcon(itemId);
		c.title = title;
		c.value = value;
		c.edge = edge;
		c.valueColour = edge == DONE ? DONE : MUTED;
		return c;
	}

	/** A section: its heading, then its goals (the first few until "Show more"). */
	private void section(String name, List<GoalLayout.Group> groups)
	{
		list.add(sectionHeader(name, groups.size()));
		if (collapsed.contains(name))
		{
			return;
		}
		int shown = showAll.contains(name) ? groups.size() : Math.min(groups.size(), SECTION_LIMIT);
		for (int i = 0; i < shown; i++)
		{
			group(groups.get(i));
		}
		if (groups.size() > SECTION_LIMIT)
		{
			boolean all = showAll.contains(name);
			list.add(toggle(all ? "– Show fewer" : "+ Show " + (groups.size() - SECTION_LIMIT) + " more", 0, () -> {
				if (!showAll.remove(name))
				{
					showAll.add(name);
				}
			}));
		}
	}

	/** A main goal, and its parts folded under it (Base 80 and its skills). */
	private void group(GoalLayout.Group g)
	{
		int partsDone = g.partsDone(), partsTotal = g.partsTotal();
		list.add(card(mainCard(g), 0));
		for (Row x : g.extras)
		{
			list.add(card(goalCard(x, x.progress, x.detail), PART_INDENT));
		}
		if (g.parts.isEmpty())
		{
			return;
		}
		boolean open = expanded.contains(g.main.id);
		list.add(toggle(open ? "– Hide parts" : "+ Show parts  ·  " + partsDone + " of " + partsTotal + " done", PART_INDENT, () -> {
			if (!expanded.remove(g.main.id))
			{
				expanded.add(g.main.id);
			}
		}));
		if (open)
		{
			for (Row p : g.parts)
			{
				list.add(card(goalCard(p, p.progress, p.detail), PART_INDENT));
			}
		}
	}

	/** A main goal's row, measured by its parts when it has them ("3 / 5" for a yes/no goal with parts). */
	private Card mainCard(GoalLayout.Group g)
	{
		int partsDone = g.partsDone(), partsTotal = g.partsTotal();
		String detail = g.main.progress < 0 && partsTotal > 0 && !g.main.done ? partsDone + " / " + partsTotal : g.main.detail;
		return goalCard(g.main, g.progress(), detail);
	}

	/** A small clickable line ("+ 23 parts", "Show 4 more") that changes what's shown. */
	private JComponent toggle(String text, int indent, Runnable flip)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(HEADING);
		l.setBorder(new EmptyBorder(0, indent + 4, 2, 0));
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		onClick(l, () -> {
			flip.run();
			redraw();
		});
		return l;
	}

	private JComponent sectionHeader(String name, int count)
	{
		return sectionHeader(name, name, count, null);
	}

	private JComponent sectionHeader(String key, String name, int count, Icon icon)
	{
		return sectionHeader(key, name, count, icon, null, null, false);
	}

	/**
	 * A section's heading, folded by key; an icon (the ★ for Favourites) goes between the fold sign and the name. On the
	 * right, before the count, an "edit" link when there's a picker; after it, ▣ when the whole section can go on screen.
	 */
	private JComponent sectionHeader(String key, String name, int count, Icon icon, Runnable edit, String pinId, boolean pinned)
	{
		boolean shut = collapsed.contains(key);
		JPanel p = new JPanel(new BorderLayout());
		p.setBorder(new EmptyBorder(6, 2, 2, 2));
		JLabel l = new JLabel((shut ? "+ " : "– ") + name.toUpperCase());
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(COMPLETED.equals(name) ? DONE : HEADING);
		JComponent title = l;
		if (icon != null)
		{
			// The icon is drawn, not a font glyph: the RuneScape fonts have no ★.
			l.setText(shut ? "+ " : "– ");
			JLabel named = new JLabel(name.toUpperCase(), icon, SwingConstants.LEADING);
			named.setFont(l.getFont());
			named.setForeground(l.getForeground());
			named.setIconTextGap(3);
			JPanel both = new JPanel(new BorderLayout());
			both.setOpaque(false);
			both.add(l, BorderLayout.WEST);
			both.add(named, BorderLayout.CENTER);
			title = both;
		}
		JLabel n = new JLabel(String.valueOf(count));
		n.setFont(FontManager.getRunescapeSmallFont());
		n.setForeground(MUTED);
		p.add(title, BorderLayout.WEST);
		if (edit == null && pinId == null)
		{
			p.add(n, BorderLayout.EAST);
		}
		else
		{
			// "edit" and ▣ take their own clicks, so they don't fold the section.
			JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
			east.setOpaque(false);
			if (edit != null)
			{
				JLabel e = new JLabel("edit");
				e.setFont(FontManager.getRunescapeSmallFont());
				e.setForeground(HEADING);
				e.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				e.setToolTipText("Tick several at once");
				onClick(e, edit);
				east.add(e);
			}
			east.add(n);
			if (pinId != null)
			{
				east.add(glyph(false, pinned, "Show on game screen", "Remove from game screen", () -> onPin.accept(pinId)));
			}
			p.add(east, BorderLayout.EAST);
		}
		p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		p.setToolTipText(shut ? "Show " + name.toLowerCase() : "Hide " + name.toLowerCase());
		onClick(p, () -> {
			if (!collapsed.remove(key))
			{
				collapsed.add(key);
			}
			redraw();
		});
		return p;
	}

	/** One goal as a row. progress and detail can differ from the row's own (a main goal measured by its parts). */
	private Card goalCard(Row r, double progress, String detail)
	{
		Card c = new Card();
		c.id = r.id;
		// Its ★ saves under the goal's own tab, also when clicked in Overview.
		c.favTab = r.category;
		c.pinId = r.id;
		c.fav = favourites(r.category).contains(r.id);
		c.pinned = r.pinned;
		c.icon = l -> icon(r, l);
		c.title = r.title;
		c.titleColour = r.done ? MUTED : Color.WHITE;
		c.value = r.done ? "Done" : detail;
		c.valueColour = r.done ? DONE : progress < 0 ? MUTED : colour(progress).brighter();
		c.edge = r.done ? DONE : colour(progress);
		// Quests are done or not: their state is the value, with no bar or percentage.
		boolean measured = progress >= 0 && !("Quests".equals(r.category) && r.partsTotal == 0);
		c.progress = r.done ? 1 : measured ? progress : -1;
		c.barDone = r.done;
		String hint = r.done ? (r.points > 0 ? "+" + r.points + " points" : "") : r.hint;
		String text = r.done || !measured ? hint : hint.isEmpty() ? Math.round(progress * 100) + "%"
			: hint + "  ·  " + Math.round(progress * 100) + "%";
		if (r.pinned)
		{
			text = text.isEmpty() ? "On screen" : text + "  ·  On screen";
		}
		c.sub = text;
		c.subColour = r.done ? DONE.darker() : MUTED;
		c.tooltip = "<html>" + escape(r.title) + (r.link == null ? "" : r.link.startsWith(GoalTracker.WIKI) ? "<br>Click for the OSRS Wiki" : "<br>Click to open it in Nib Tracker")
			+ "<br>Right-click to " + (r.pinned ? "remove it from" : "show it on") + " the game screen</html>";
		// Right-click: show this goal on the game screen, or take it off.
		JPopupMenu menu = new JPopupMenu();
		menu.add(item(r.pinned ? "Remove from game screen" : "Show on game screen", () -> onPin.accept(r.id)));
		c.menu = menu;
		if (r.link != null)
		{
			c.click = () -> LinkBrowser.browse(r.link);
		}
		return c;
	}

	/**
	 * One row, drawn the same on every tab: the icon in a 28px slot (left empty when there's none, so rows line up), the
	 * title with its value and then ★ and ▣ on the right, the bar when there's progress, and the muted second line.
	 */
	private JComponent card(Card c, int indent)
	{
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, c.edge),
			new EmptyBorder(6, 6, 6, 8)));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(ICON, ICON));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		if (c.icon != null)
		{
			c.icon.accept(icon);
		}
		p.add(icon, BorderLayout.WEST);

		JPanel body = new JPanel(new DynamicGridLayout(0, 1, 0, 3));
		body.setOpaque(false);

		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setOpaque(false);
		JLabel title = new JLabel(c.title);
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setForeground(c.titleColour);
		JLabel value = new JLabel(c.value);
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(c.valueColour);
		if (c.wrapTitle)
		{
			// What's left beside the value and ★ ▣ (34px).
			int width = HINT_WIDTH - indent - 34 - (c.value.isEmpty() ? 0 : value.getPreferredSize().width + 6);
			title.setText("<html><div style='width:" + Math.max(60, width) + "px'>" + escape(c.title) + "</div></html>");
		}
		JLabel star = glyph(true, c.fav, "Add to favourites", "Remove from favourites",
			c.favTab == null ? null : () -> onFavourite.accept(c.favTab, c.id));
		JLabel pin = glyph(false, c.pinned, "Show on game screen", "Remove from game screen",
			c.pinId == null ? null : () -> onPin.accept(c.pinId));
		JPanel glyphs = new JPanel(new GridLayout(1, 2, 2, 0));
		glyphs.setOpaque(false);
		glyphs.add(star);
		glyphs.add(pin);
		JPanel right = new JPanel(new BorderLayout(6, 0));
		right.setOpaque(false);
		right.add(value, BorderLayout.CENTER);
		right.add(glyphs, BorderLayout.EAST);
		line.add(title, BorderLayout.CENTER);
		line.add(right, BorderLayout.EAST);
		body.add(line);

		if (c.progress >= 0)
		{
			Bar bar = new Bar();
			bar.setPreferredSize(new Dimension(0, 5));
			bar.set(c.progress, c.barDone);
			body.add(bar);
		}

		if (c.sub != null && !c.sub.isEmpty())
		{
			// Wraps to the row's width, so a task description is shown in full.
			JLabel h = new JLabel("<html><div style='width:" + (HINT_WIDTH - indent) + "px'>" + escape(c.sub) + "</div></html>");
			h.setFont(FontManager.getRunescapeSmallFont());
			h.setForeground(c.subColour);
			body.add(h);
		}
		p.add(body, BorderLayout.CENTER);

		p.setToolTipText(c.tooltip);
		if (c.menu != null)
		{
			p.setComponentPopupMenu(c.menu);
			inheritMenu(p);
		}
		if (c.click != null)
		{
			p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			onClick(p, c.click);
			// Moving onto ★ or ▣ (they take their own clicks) keeps the row lit; it goes out only on leaving the row.
			MouseAdapter hover = new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					p.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					Point at = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), p);
					if (!p.contains(at))
					{
						p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					}
				}
			};
			p.addMouseListener(hover);
			star.addMouseListener(hover);
			pin.addMouseListener(hover);
		}
		if (indent == 0)
		{
			return p;
		}
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBorder(new EmptyBorder(0, indent, 0, 0));
		wrap.add(p);
		return wrap;
	}

	/**
	 * ★ or ▣ at 14px, with its own tooltip. A click on it is its own (the row's click, its wiki link, doesn't fire) and
	 * flips it at once; the redraw after the setting is saved agrees. With nothing to toggle, an empty space of the same
	 * size, so values still line up.
	 */
	private static JLabel glyph(boolean star, boolean on, String addTip, String removeTip, Runnable toggle)
	{
		JLabel g = new JLabel();
		g.setPreferredSize(new Dimension(14, 14));
		g.setHorizontalAlignment(SwingConstants.CENTER);
		if (toggle == null)
		{
			return g;
		}
		Glyph icon = new Glyph(star, on);
		g.setIcon(icon);
		g.setToolTipText(on ? removeTip : addTip);
		g.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		g.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				e.consume();
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				e.consume();
				if (SwingUtilities.isLeftMouseButton(e) && g.contains(e.getPoint()))
				{
					icon.on = !icon.on;
					g.setToolTipText(icon.on ? removeTip : addTip);
					g.repaint();
					SwingUtilities.invokeLater(toggle);
				}
			}
		});
		return g;
	}

	/** Skill icon, the item's own picture if it's a tradeable item, or a picture for the kind of goal. */
	private void icon(Row r, JLabel label)
	{
		if ("Combat achievements".equals(r.category))
		{
			label.setIcon(icons.computeIfAbsent("ca", k -> new ImageIcon(fitTo(CA_BOOK, 24))));
			return;
		}
		String key = r.skill != null ? "skill:" + r.skill.name() : r.item != null ? "item:" + r.item.toLowerCase() : "id:" + r.fallbackItem;
		Icon cached = icons.get(key);
		if (cached != null)
		{
			label.setIcon(cached);
			return;
		}
		if (r.skill != null)
		{
			BufferedImage img = skillIcons.getSkillImage(r.skill, false);
			if (img != null)
			{
				Icon i = new ImageIcon(fit(img));
				icons.put(key, i);
				label.setIcon(i);
			}
			return;
		}
		int id = r.fallbackItem;
		if (r.itemId > 0)
		{
			id = r.itemId;
		}
		else if (r.item != null)
		{
			for (ItemPrice p : itemManager.search(r.item))
			{
				if (p.getName().equalsIgnoreCase(r.item))
				{
					id = p.getId();
					break;
				}
			}
		}
		if (id <= 0)
		{
			return;
		}
		AsyncBufferedImage img = itemManager.getImage(id);
		onLoaded(img, () -> {
			Icon i = new ImageIcon(fit(img));
			icons.put(key, i);
			label.setIcon(i);
		});
	}

	/** Sets what happens when a goal is put on or taken off the game screen. */
	void onPin(Consumer<String> onPin)
	{
		this.onPin = onPin;
	}

	/** Sets what happens when a row's ★ is clicked: called with the row's home tab and its id. */
	void onFavourite(BiConsumer<String, String> onFavourite)
	{
		this.onFavourite = onFavourite;
	}

	void onDailyDone(Consumer<String> onDailyDone)
	{
		this.onDailyDone = onDailyDone;
	}

	void onRegion(Consumer<String> onRegion)
	{
		this.onRegion = onRegion;
	}

	/** Sets what the All patches picker's Done does: called with every hidden region. */
	void onRegionsSet(Consumer<Set<String>> onRegionsSet)
	{
		this.onRegionsSet = onRegionsSet;
	}

	/** Sets what the Favourites picker's Done does: called with the tab and its favourite ids in order. */
	void onFavouritesSet(BiConsumer<String, List<String>> onFavouritesSet)
	{
		this.onFavouritesSet = onFavouritesSet;
	}

	/* The right-click menu works anywhere on the row, labels included. */
	private static void inheritMenu(Container c)
	{
		for (Component k : c.getComponents())
		{
			if (k instanceof JComponent)
			{
				((JComponent) k).setInheritsPopupMenu(true);
			}
			if (k instanceof Container)
			{
				inheritMenu((Container) k);
			}
		}
	}

	/**
	 * Runs when the left button is released anywhere over the component. Swing's own "clicked" is dropped if the mouse
	 * moves even a pixel between press and release, which made the filter chips feel like they needed pinpoint clicks.
	 * The action runs after the event is done, as it usually rebuilds the panel (and this component with it).
	 */
	private static void onClick(JComponent c, Runnable action)
	{
		c.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && c.contains(e.getPoint()))
				{
					SwingUtilities.invokeLater(action);
				}
			}
		});
	}

	/**
	 * Runs once an item image has loaded, on the Swing thread. An image already loaded runs it straight away, so a redraw
	 * shows its icons at once; one still loading finishes on the client thread, and is handed to the Swing thread from
	 * there (setting an icon from the client thread can leave half-drawn rows behind).
	 */
	private static void onLoaded(AsyncBufferedImage img, Runnable r)
	{
		img.onLoaded(() -> {
			if (SwingUtilities.isEventDispatchThread())
			{
				r.run();
			}
			else
			{
				SwingUtilities.invokeLater(r);
			}
		});
	}

	/**
	 * An item's icon at this size, from the cache when it's been drawn before. Until it loads, a blank icon of the same
	 * size holds its place, so text beside it doesn't jump when it arrives.
	 */
	private void itemIcon(int item, int size, Consumer<Icon> set)
	{
		String key = "item:" + item + ":" + size;
		Icon cached = icons.get(key);
		if (cached != null)
		{
			set.accept(cached);
			return;
		}
		set.accept(new ImageIcon(new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)));
		AsyncBufferedImage img = itemManager.getImage(item);
		onLoaded(img, () -> {
			Icon i = new ImageIcon(fitTo(img, size));
			icons.put(key, i);
			set.accept(i);
		});
	}

	/**
	 * An item's icon for a section heading (14px, like the ★). It draws whatever the cache holds when painted, so the
	 * heading shows it as soon as it loads; null without an item manager.
	 */
	private Icon headingIcon(int item)
	{
		if (itemManager == null)
		{
			return null;
		}
		int size = 14;
		String key = "item:" + item + ":" + size;
		itemIcon(item, size, i -> list.repaint());
		return new Icon()
		{
			@Override
			public void paintIcon(Component c, Graphics g, int x, int y)
			{
				Icon i = icons.get(key);
				if (i != null)
				{
					i.paintIcon(c, g, x, y);
				}
			}

			@Override
			public int getIconWidth()
			{
				return size;
			}

			@Override
			public int getIconHeight()
			{
				return size;
			}
		};
	}

	/** An image scaled to fit a square of this size, keeping its shape. */
	private static BufferedImage fitTo(BufferedImage img, int size)
	{
		if (img == null)
		{
			return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		}
		double s = Math.min((double) size / img.getWidth(), (double) size / img.getHeight());
		return ImageUtil.resizeImage(img, Math.max(1, (int) Math.round(img.getWidth() * s)), Math.max(1, (int) Math.round(img.getHeight() * s)));
	}

	private static BufferedImage fit(BufferedImage img)
	{
		double s = Math.min(1.0, Math.min((double) ICON / img.getWidth(), (double) ICON / img.getHeight()));
		return s >= 1.0 ? img : ImageUtil.resizeImage(img, (int) Math.round(img.getWidth() * s), (int) Math.round(img.getHeight() * s));
	}

	/** Red at the start, amber halfway, green when done. Grey when there's nothing to measure. */
	static Color colour(double p)
	{
		if (p < 0)
		{
			return UNKNOWN;
		}
		return p < 0.5 ? mix(RED, AMBER, p / 0.5) : mix(AMBER, GREEN, (p - 0.5) / 0.5);
	}

	private static Color mix(Color a, Color b, double t)
	{
		t = Math.max(0, Math.min(1, t));
		return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
			(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			(int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** A slim rounded progress bar. */
	private static final class Bar extends JComponent
	{
		private double value;
		private boolean done;

		void set(double value, boolean done)
		{
			this.value = Math.max(0, Math.min(1, value));
			this.done = done;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(ColorScheme.DARK_GRAY_COLOR.darker());
			g2.fillRoundRect(0, 0, w, h, h, h);
			int fill = (int) Math.round(w * value);
			if (fill > 0)
			{
				g2.setColor(done ? DONE : colour(value));
				g2.fillRoundRect(0, 0, Math.max(fill, h), h, h, h);
			}
			g2.dispose();
		}
	}

	/** ★ or ▣ at 14px: orange when on, grey when off. Drawn rather than typed, as the RuneScape fonts have neither. */
	private static final class Glyph implements Icon
	{
		private final boolean star;
		boolean on;

		Glyph(boolean star, boolean on)
		{
			this.star = star;
			this.on = on;
		}

		@Override
		public int getIconWidth()
		{
			return 14;
		}

		@Override
		public int getIconHeight()
		{
			return 14;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setColor(on ? ColorScheme.BRAND_ORANGE : UNKNOWN);
			if (star)
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				Path2D.Double s = new Path2D.Double();
				for (int i = 0; i < 10; i++)
				{
					double r = i % 2 == 0 ? 6.5 : 2.7;
					double a = -Math.PI / 2 + i * Math.PI / 5;
					double px = x + 7 + r * Math.cos(a), py = y + 7.5 + r * Math.sin(a);
					if (i == 0)
					{
						s.moveTo(px, py);
					}
					else
					{
						s.lineTo(px, py);
					}
				}
				s.closePath();
				g2.fill(s);
			}
			else
			{
				// A square in a square, kept crisp (no smoothing) at this size.
				g2.drawRect(x + 1, y + 1, 11, 11);
				g2.fillRect(x + 4, y + 4, 6, 6);
			}
			g2.dispose();
		}
	}

	static int fallbackItem(String category)
	{
		switch (category)
		{
			case "Quests":
				return ItemID.SKILLCAPE_QP;
			case "Collection log":
				return ItemID.COLLECTION_LOG;
			case "Items":
				return ItemID.COINS;
			case "Achievement diaries":
				return ItemID.SKILLCAPE_AD_TRIMMED;
			case "Skills":
				return ItemID.SKILLCAPE_MAX;
			default:
				return -1;
		}
	}
}
