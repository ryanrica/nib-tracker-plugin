package com.nibtracker;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.AbstractAction;
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
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
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
	 * One row as the panel draws it, the same shape on every tab: an icon, the title (wrapping) with its value on the
	 * right, an optional bar (progress >= 0), a quieter second line, and ★ / ▣ on the right.
	 */
	static final class Card
	{
		/* Its favourite id and the tab that favourite is saved under (null: no ★), and its game-screen pin (null: no ▣). */
		String id = "";
		String favTab;
		String pinId;
		/* Puts the row's real icon in its slot (a goal's skill or item, a crop, a daily task's item); null leaves it empty. */
		Consumer<JLabel> icon;
		String title = "";
		Color titleColour = INK;
		String value = "";
		Color valueColour = INK_2;
		/* A ✓ before the value: done, or ready to act on. */
		boolean check;
		double progress = -1;
		boolean barDone;
		/* The bar's colour, or null for the usual (gold, green when done). */
		Color barColour;
		String sub = "";
		Color subColour = MUTED;
		/* A main goal's "Show skills · 19 of 23 done" line under its second line, and what clicking it does. */
		String parts;
		Runnable partsToggle;
		String tooltip;
		boolean fav;
		boolean pinned;
		JPopupMenu menu;
		Runnable click;
	}

	private static final String COMPLETED = "Completed";
	private static final String DAILIES = "Daily tasks";
	private static final String FARMING = "Farming";
	private static final String BINGO = "Bingo";
	private static final String OVERVIEW = "Overview";
	private static final String ALL = "All";
	/* The Farming tab's group chips, in order, before its kind chips (Favourites is drawn with a ★ in front). */
	private static final String ALL_PATCHES = "All patches";
	private static final String FAVOURITES = "Favourites";
	/* The favourites stop by stop, in the order of one run over every kind of patch. */
	private static final String RUN_ORDER = "Run order";
	private static final String SPECIAL = "Special";
	private static final String ALL_TREES = "All trees";
	private static final String CONTRACT = "Farming contract";
	/* After the kind chips: whether each spirit tree spot has a tree (never a farm timer). */
	private static final String SPIRIT_TREES = "Spirit trees";
	private static final List<String> FARM_GROUPS = Arrays.asList(ALL_PATCHES, FAVOURITES, RUN_ORDER, SPECIAL, ALL_TREES, CONTRACT);
	/* The tabs, four to a row: Overview, Skills, Quests, Achievement diaries; then Farming, Combat achievements,
	   Collection log, Daily tasks; anything else after. */
	private static final List<String> TAB_ORDER = Arrays.asList("Overview", "Skills", "Quests", "Achievement diaries",
		"Farming", "Combat achievements", "Collection log", "Daily tasks", "Bingo", "Bosses", "Items", "Other");
	/* Sub-filter values in the order they're offered. */
	private static final List<String> TAG_ORDER = Arrays.asList("Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster", "Not started", "In progress");
	private static final BufferedImage LOGO = ImageUtil.loadImageResource(GoalsPanel.class, "goals_icon.png");
	/* The combat achievements book, as Nib Tracker shows it (OSRS Wiki's Combat achievements detail image, made small). */
	static final BufferedImage CA_BOOK = ImageUtil.loadImageResource(GoalsPanel.class, "combat_achievements.png");
	/* What hangs off a main goal (its parts, its Slayer task) sits this far in, on a hairline. */
	private static final int CHILD_INDENT = 21;
	private static final int CHILD_PAD = 12;
	/* A value longer than this goes on a row's second line (the contract's "Ready · then see Jane..."). */
	private static final int SHORT_VALUE = 12;

	/* One accent: gold for progress and anything switched on, green only for finished or ready, red only for a patch
	   that needs you. The ink has three steps and is never pure white, so the gold and the icons carry the colour. */
	static final Color GOLD = new Color(214, 181, 110);
	static final Color GOLD_DEEP = new Color(150, 118, 56);
	private static final Color GOLD_WASH = new Color(57, 53, 45);
	private static final Color RED = new Color(0xC8, 0x4B, 0x3C);
	private static final Color AMBER = new Color(0xE0, 0xA0, 0x30);
	private static final Color DONE = new Color(104, 186, 120);
	private static final Color INK = new Color(236, 233, 226);
	private static final Color INK_2 = new Color(170, 168, 162);
	/* 4.9:1 on the panel, 5.8:1 on a row. */
	private static final Color MUTED = new Color(152, 150, 145);
	/* Structure without boxes: a faint white line, brighter under the mouse; a recessed well for bar tracks. */
	private static final Color HAIR = new Color(255, 255, 255, 15);
	private static final Color RING_HOVER = new Color(255, 255, 255, 31);
	private static final Color SURFACE_HOVER = new Color(37, 37, 37);
	private static final Color WELL = new Color(23, 23, 23);

	/* Figtree (Nib Tracker's own face, OFL, bundled) for all the panel's text, and the game-screen overlay's. */
	static final Font FIGTREE = loadFont("Figtree-Regular.ttf");
	static final Font FIGTREE_SEMIBOLD = loadFont("Figtree-SemiBold.ttf");
	private static final Font NAME = FIGTREE_SEMIBOLD.deriveFont(15f);
	private static final Font TITLE = FIGTREE_SEMIBOLD.deriveFont(13f);
	private static final Font SMALL_TITLE = FIGTREE_SEMIBOLD.deriveFont(12f);
	private static final Font VALUE = FIGTREE_SEMIBOLD.deriveFont(12f);
	private static final Font BODY = FIGTREE.deriveFont(12f);
	/* Section headings: small capitals, spaced out a little. */
	private static final Font HEAD = FIGTREE_SEMIBOLD.deriveFont(Collections.singletonMap(TextAttribute.TRACKING, 0.05f)).deriveFont(10f);
	/* Smaller, then untracked, for a long heading in a narrow panel (see Heading). */
	private static final Font HEAD_SMALL = FIGTREE_SEMIBOLD.deriveFont(Collections.singletonMap(TextAttribute.TRACKING, 0.03f)).deriveFont(9.5f);
	private static final Font HEAD_TIGHT = FIGTREE_SEMIBOLD.deriveFont(9f);

	private static final int ICON = 28;
	private static final int CHILD_ICON = 18;
	/* ★ and ▣ side by side. */
	private static final int GLYPHS = 30;
	/* The text column beside a row's icon: the panel less its 10px sides, the row's padding (8 and 7), the icon and the
	   gap after it, with 4px to spare; a child's less its indent and hairline (30), padding (4 and 4) and smaller icon. */
	private static final int TEXT = PluginPanel.PANEL_WIDTH - 20 - 15 - ICON - 9 - 4;
	private static final int CHILD_TEXT = PluginPanel.PANEL_WIDTH - 20 - (CHILD_INDENT + 1 + CHILD_PAD - 4) - 8 - CHILD_ICON - 8 - 4;

	private final ItemManager itemManager;
	private final SkillIconManager skillIcons;
	private final Map<String, Icon> icons = new HashMap<>();
	/* Sections folded away (Completed starts folded), and main goals showing their parts. */
	private final Set<String> collapsed = new HashSet<>(Collections.singleton(COMPLETED));
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
	/* Called with Overview's section order when a heading is dragged or moved. */
	private Consumer<List<String>> onOverviewOrder = order -> { };
	private Runnable onHideTip = () -> { };
	/* Overview's headings by section, in list order, for working out where a dragged one is dropped; the heading marked
	   as the drop target and its own border; and whether a release just ended a drag (so it doesn't fold the section). */
	private final Map<String, JComponent> overviewHeaders = new LinkedHashMap<>();
	private JComponent dropMark;
	private Border dropMarkBorder;
	private boolean justDragged;
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
	/* The header, stacked again on each show from what's visible (see stackHeader), and its first row: the name and %. */
	private final JPanel top = new JPanel(new DynamicGridLayout(0, 1, 0, 6));
	private final JPanel nameRow = new JPanel(new BorderLayout());
	private final JPanel filters = new JPanel(new GridLayout(0, 3, 4, 4));

	private final JLabel heading = new JLabel("Goals");
	private final JLabel percent = new JLabel();
	private final JLabel summary = new JLabel();
	private final JLabel openSite = new JLabel("Open Nib Tracker");
	private final Bar overall = new Bar(3);
	/* The picked view's name under the tabs, as the tabs are icons. */
	private final JLabel viewName = new JLabel();
	private final Wrap status = new Wrap("", BODY, INK_2, PluginPanel.PANEL_WIDTH - 20, 0);
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
		setLayout(new BorderLayout(0, 4));
		// The scrollbar always shows, so the panel keeps its width when a tab is short enough not to need it (it
		// appearing and going made everything shift sideways when switching between Overview and other tabs).
		JScrollPane scroll = getScrollPane();
		if (scroll != null)
		{
			scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		}

		heading.setFont(NAME);
		heading.setForeground(INK);
		percent.setFont(NAME);
		percent.setForeground(GOLD);
		summary.setFont(BODY);
		summary.setForeground(MUTED);
		viewName.setFont(TITLE);
		viewName.setForeground(INK);
		openSite.setFont(BODY);
		openSite.setForeground(GOLD);
		openSite.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		openSite.setToolTipText(NibTrackerApi.SITE);
		onClick(openSite, () -> LinkBrowser.browse(NibTrackerApi.SITE));

		nameRow.setOpaque(false);
		nameRow.add(heading, BorderLayout.CENTER);
		nameRow.add(percent, BorderLayout.EAST);
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
		status.setText(message == null ? "" : message);
		status.setVisible(message != null);

		GoalLayout.Layout layout = GoalLayout.of(rows);
		List<GoalLayout.Group> open = new ArrayList<>();
		layout.sections.values().forEach(open::addAll);
		int done = layout.completed.size(), total = open.size() + done;
		double avg = total == 0 ? 0 : (done + open.stream().mapToDouble(g -> Math.max(0, g.progress())).sum()) / total;
		summary.setText(done + " of " + total + " goals done");
		summary.setVisible(total > 0);
		percent.setText(Math.round(avg * 100) + "%");
		percent.setVisible(total > 0);
		overall.set(avg, total > 0 && done == total);
		overall.setVisible(total > 0);

		// Every tab always shows, so they don't move about as goals come and go; an empty one says so. Bosses, Items and
		// Other are left out for now (their goals still show in Overview when starred).
		List<String> views = new ArrayList<>();
		views.add(OVERVIEW);
		views.addAll(Arrays.asList(GoalProgress.CATEGORIES));
		views.removeAll(Arrays.asList("Bosses", "Items", "Other"));
		views.add(DAILIES);
		views.add(FARMING);
		// Only for a player on a live board: everyone else would have an empty tab on a row of its own.
		if (!extras.boards.isEmpty())
		{
			views.add(BINGO);
		}
		views.sort(Comparator.comparingInt(v -> TAB_ORDER.indexOf(v) < 0 ? TAB_ORDER.size() : TAB_ORDER.indexOf(v)));
		if (!views.contains(view))
		{
			view = OVERVIEW;
		}
		tabs(views);
		viewName.setText(view);

		list.removeAll();
		if (OVERVIEW.equals(view))
		{
			// Only what's been favourited in each tab, under the same headings; a heading with none doesn't show.
			filters.setVisible(false);
			boolean any = false;
			List<String> order = extras.overviewOrder.isEmpty() ? GoalLayout.overviewOrder("") : extras.overviewOrder;
			overviewHeaders.clear();
			for (String s : order)
			{
				int before = list.getComponentCount();
				GoalLayout.Picks picks = GoalLayout.favourites(layout.sections.getOrDefault(s, Collections.emptyList()), extras.favourites);
				Row task = slayerTask();
				if (task != null && s.equals(task.category) && favourites(s).contains(task.id))
				{
					picks.rows.add(0, task);
				}
				boolean shown = DAILIES.equals(s) ? dailies(extras, true) : FARMING.equals(s) ? farmingOverview(extras) : picksSection(s, picks);
				if (shown)
				{
					draggable((JComponent) list.getComponent(before), s, order);
				}
				any |= shown;
			}
			if (any && !extras.overviewTipHidden)
			{
				list.add(reorderTip(), 0);
			}
			any |= picksSection(COMPLETED, GoalLayout.favourites(layout.completed, extras.favourites));
			if (!any)
			{
				list.add(note("Star things in any tab to see them here."));
			}
		}
		else if (BINGO.equals(view))
		{
			filters.setVisible(false);
			bingoView(extras);
		}
		else if (DAILIES.equals(view))
		{
			if (!extras.dailiesOn || extras.dailies.isEmpty())
			{
				filters.setVisible(false);
				list.add(note(extras.dailiesOn ? "No daily tasks yet. Switch some on in Nib Tracker."
					: "Turn on Daily tasks in the panel in this plugin's settings."));
			}
			else
			{
				filters(Arrays.asList(ALL, "Due", "Done"));
				dailies(extras, false);
			}
		}
		else if (FARMING.equals(view) && (!extras.farmOn || extras.allRegions.isEmpty() && extras.contract == null))
		{
			// Farm data or a contract: the contract row works with the website closed.
			filters.setVisible(false);
			list.add(note(extras.farmOn ? "No farm timers yet. They come from RuneLite's Time Tracking plugin."
				: "Turn on Farming in the panel in this plugin's settings."));
		}
		else if (FARMING.equals(view))
		{
			// The groups first, then one chip per kind of patch you have a timer on (Herbs, Fruit trees, Hespori...), each
			// with its crop's icon.
			Map<String, Integer> kindIcons = new HashMap<>();
			for (PatchView p : extras.patches)
			{
				if (p.itemId > 0)
				{
					kindIcons.putIfAbsent(p.kindLabel, p.itemId);
				}
			}
			// Kinds with no crop to show yet (no Time Tracking data) use their own picture.
			for (PatchView p : extras.patches)
			{
				if (FarmGroups.kindIcon(p.kind) > 0)
				{
					kindIcons.putIfAbsent(p.kindLabel, FarmGroups.kindIcon(p.kind));
				}
			}
			List<String> chips = new ArrayList<>(FARM_GROUPS);
			chips.addAll(kindChips(extras.patches));
			if (!extras.allRegions.isEmpty() && !extras.spiritTrees.isEmpty())
			{
				chips.add(SPIRIT_TREES);
			}
			kindIcons.put(SPECIAL, ItemID.HESPORI_SEED);
			kindIcons.put(RUN_ORDER, ItemID.DRAMEN_STAFF);
			kindIcons.put(ALL_TREES, ItemID.MAGIC_LOGS);
			kindIcons.put(CONTRACT, extras.contract != null && extras.contract.itemId > 0 ? extras.contract.itemId : ItemID.SEED_BOX);
			kindIcons.put(SPIRIT_TREES, ItemID.PLANTPOT_SPIRIT_TREE_SAPLING);
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
		stackHeader();
		revalidate();
		repaint();
	}

	/**
	 * The header from what's showing now. The grid leaves a gap for every component in it, shown or not, so hidden ones
	 * (no goals yet, no filters on Overview, no status line) are left out rather than hidden, and Overview's sections
	 * start just under its name.
	 */
	private void stackHeader()
	{
		top.removeAll();
		top.add(nameRow);
		top.add(openSite);
		for (JComponent c : Arrays.asList(summary, overall, tabsHolder, viewName, filters, status))
		{
			if (c.isVisible())
			{
				top.add(c);
			}
		}
	}

	/**
	 * The Farming tab's kind chips, sorted: each patch kind's label once, leaving out tree kinds (All trees has them) and
	 * any label a group chip already uses.
	 */
	static List<String> kindChips(List<PatchView> patches)
	{
		List<String> kinds = new ArrayList<>();
		for (PatchView p : patches)
		{
			if (!p.kindLabel.isEmpty() && !FarmGroups.tree(p.kind) && !kinds.contains(p.kindLabel) && !FARM_GROUPS.contains(p.kindLabel)
				&& !SPIRIT_TREES.equals(p.kindLabel))
			{
				kinds.add(p.kindLabel);
			}
		}
		kinds.sort(String::compareTo);
		return kinds;
	}

	private void redraw()
	{
		show(lastTitle, lastMessage, lastRows, lastExtras);
	}

	/**
	 * The icon tabs along the top, as RuneLite's Time Tracking has: Overview, then each kind of goal there is. Four fit
	 * on a row, each in its own box, big enough to tell apart at a glance (more rows take the rest).
	 */
	private void tabs(List<String> views)
	{
		tabsHolder.removeAll();
		MaterialTabGroup group = new MaterialTabGroup();
		group.setLayout(new GridLayout(0, 4, 4, 4));
		MaterialTab selected = null;
		for (String v : views)
		{
			Tab tab = new Tab(group);
			tabIcon(v, tab);
			tab.setToolTipText(v);
			tab.setPreferredSize(new Dimension(47, 40));
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

	private void tabIcon(String v, Tab tab)
	{
		if (OVERVIEW.equals(v))
		{
			// The house teleport tablet: Overview is home.
			if (itemManager != null)
			{
				itemIcon(ItemID.POH_TABLET_TELEPORTTOHOUSE, 28, tab::icon);
			}
			else
			{
				tab.icon(new ImageIcon(fitTo(LOGO, 26)));
			}
			return;
		}
		if (DAILIES.equals(v) && itemManager != null)
		{
			itemIcon(ItemID.GIANT_STOPWATCH, 28, tab::icon);
			return;
		}
		if (BINGO.equals(v) && itemManager != null)
		{
			itemIcon(ItemID.BINGO_TABARD, 28, tab::icon);
			return;
		}
		if ("Combat achievements".equals(v))
		{
			tab.icon(new ImageIcon(fitTo(CA_BOOK, 28)));
			return;
		}
		Skill skill = FARMING.equals(v) ? Skill.FARMING : "Bosses".equals(v) ? Skill.SLAYER : null;
		if (skill != null)
		{
			tab.icon(new ImageIcon(fitTo(skillIcons.getSkillImage(skill, false), 26)));
			return;
		}
		int item = "Skills".equals(v) ? ItemID.SKILLCAPE_MAX : fallbackItem(v);
		if (item > 0 && itemManager != null)
		{
			itemIcon(item, 28, tab::icon);
		}
		else
		{
			tab.icon(new ImageIcon(fitTo(LOGO, 26)));
		}
	}

	/**
	 * A tab as its own rounded box with its icon centred, in place of MaterialTab's orange line: the picked one filled
	 * with a gold wash and ringed in gold, the others on the darker grey with a faint ring and their icon a little dimmed.
	 */
	private static final class Tab extends MaterialTab
	{
		private static final Border PAD = new EmptyBorder(6, 0, 6, 0);
		private Icon on;
		private Icon off;
		private boolean picked;

		Tab(MaterialTabGroup group)
		{
			super("", group, null);
			setOpaque(false);
			setHorizontalAlignment(SwingConstants.CENTER);
			setVerticalAlignment(SwingConstants.CENTER);
			setBorder(PAD);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(picked ? GOLD_WASH : ColorScheme.DARKER_GRAY_COLOR);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
			g2.setColor(picked ? GOLD : HAIR);
			g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
			g2.dispose();
			super.paintComponent(g);
		}

		/** Its icon, with the dimmed copy made once; it may arrive after the tab is drawn (an item still loading). */
		void icon(Icon icon)
		{
			on = icon;
			off = icon instanceof ImageIcon && icon.getIconWidth() > 1
				? new ImageIcon(ImageUtil.alphaOffset(ImageUtil.bufferedImageFromImage(((ImageIcon) icon).getImage()), 0.8f))
				: icon;
			style(isSelected());
		}

		@Override
		public boolean select()
		{
			boolean picked = super.select();
			style(isSelected());
			return picked;
		}

		@Override
		public void unselect()
		{
			super.unselect();
			style(false);
		}

		private void style(boolean picked)
		{
			this.picked = picked;
			// MaterialTab sets its own border on select; the padding is put back so the box keeps its size.
			setBorder(PAD);
			setIcon(picked ? on : off);
			repaint();
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
			chip.setFont(BODY);
			chip.setForeground(on ? GOLD : INK_2);
			chip.setOpaque(true);
			chip.setBackground(on ? GOLD_WASH : ColorScheme.DARKER_GRAY_COLOR);
			chip.setBorder(new EmptyBorder(4, 6, 4, 6));
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
		// Skills starts with the current Slayer task, a row of its own above the skills.
		Row task = "Skills".equals(category) ? slayerTask() : null;
		if (task != null)
		{
			list.add(card(goalCard(task, task.progress, task.detail)));
		}
		// Favourites stay where they are here (★ on the row); Overview is where they're gathered.
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
		if (open.isEmpty() && shownDone.isEmpty() && task == null)
		{
			list.add(note("Nothing here yet."));
		}
	}

	/** The current Slayer task's row, or null with no task. It isn't a goal, so GoalLayout leaves it out. */
	private Row slayerTask()
	{
		return lastRows.stream().filter(r -> GoalTracker.SLAYER_TASK.equals(r.id)).findFirst().orElse(null);
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
		/* Each spirit tree spot and whether a tree is planted there, worked out here; empty when farming is not. */
		final List<PatchPredictor.SpiritTree> spiritTrees = new ArrayList<>();
		/* The stops of the one run over every kind of patch, by number; and whether Overview lists favourite patches by
		   those stops rather than by kind (the plugin setting). */
		final Map<Integer, StopView> stops = new HashMap<>();
		boolean favouritesByRun;
		/* Overview's sections, top to bottom (see GoalLayout.overviewOrder), and whether its tip on reordering them is hidden. */
		final List<String> overviewOrder = new ArrayList<>();
		boolean overviewTipHidden;
		/* The player's live bingo boards (see Routines.bingo). */
		final List<Routines.Board> boards = new ArrayList<>();
	}

	/**
	 * Bingo: each live board under its name and how many tiles are done, as a grid of its tiles' pictures. A done tile
	 * is filled gold with a ✓; the rest are dimmed. Hover a tile for what it asks; click to open the board in Nib Tracker.
	 */
	private void bingoView(Extras x)
	{
		for (Routines.Board b : x.boards)
		{
			String key = "bingo:" + b.id;
			int done = 0, tiles = 0;
			for (Routines.Tile t : b.tiles)
			{
				tiles += t == null ? 0 : 1;
				done += t != null && t.done ? 1 : 0;
			}
			list.add(sectionHeader(key, b.name, done, null, null, null, false));
			if (collapsed.contains(key))
			{
				continue;
			}
			JPanel grid = new JPanel(new GridLayout(b.size, b.size, 3, 3));
			grid.setOpaque(false);
			for (Routines.Tile t : b.tiles)
			{
				grid.add(new BingoTile(t, b.size));
			}
			list.add(grid);
			list.add(new Wrap(done + " of " + tiles + " tiles done. Click a tile to open the board in Nib Tracker.", BODY, MUTED,
				PluginPanel.PANEL_WIDTH - 20, 0));
		}
	}

	/**
	 * One bingo tile: a rounded square with the tile's picture in the middle (its item, or its skill for an XP tile),
	 * gold-washed and ringed with a ✓ in the corner when done, dimmed when not. Empty slots stay blank.
	 */
	private final class BingoTile extends JComponent
	{
		private final Routines.Tile tile;
		private final int side;
		private Icon picture;

		BingoTile(Routines.Tile tile, int size)
		{
			this.tile = tile;
			// The panel's width shared between the tiles and the 3px gaps, for a square tile at any board size.
			side = (PluginPanel.PANEL_WIDTH - 20 - 3 * (size - 1)) / size;
			setPreferredSize(new Dimension(side, side));
			if (tile == null)
			{
				return;
			}
			int px = Math.max(12, Math.min(32, side - 6));
			if (!tile.skill.isEmpty())
			{
				Skill skill = skill(tile.skill);
				if (skill != null)
				{
					picture = new ImageIcon(fitTo(skillIcons.getSkillImage(skill, false), px));
				}
			}
			else if (tile.itemId > 0 && itemManager != null)
			{
				itemIcon(tile.itemId, px, i -> {
					picture = i;
					repaint();
				});
			}
			setToolTipText("<html>" + escape(tile.label) + (tile.done ? "<br>Done" : "") + "<br>Click to open the board in Nib Tracker</html>");
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			onClick(this, () -> LinkBrowser.browse(NibTrackerApi.SITE + "/#bingo"));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (tile == null)
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), h = getHeight();
			g2.setColor(tile.done ? GOLD_WASH : ColorScheme.DARKER_GRAY_COLOR);
			g2.fillRoundRect(0, 0, w, h, 6, 6);
			g2.setColor(tile.done ? GOLD : HAIR);
			g2.drawRoundRect(0, 0, w - 1, h - 1, 6, 6);
			if (picture != null)
			{
				if (!tile.done)
				{
					g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
				}
				picture.paintIcon(this, g2, (w - picture.getIconWidth()) / 2, (h - picture.getIconHeight()) / 2);
				g2.setComposite(AlphaComposite.SrcOver);
			}
			if (tile.done)
			{
				new Check(DONE).paintIcon(this, g2, w - 11, 2);
			}
			g2.dispose();
		}
	}

	/** A skill by its name in the feed ("Slayer", any case), or null for one this client doesn't know. */
	private static Skill skill(String name)
	{
		for (Skill s : Skill.values())
		{
			if (s.getName().equalsIgnoreCase(name))
			{
				return s;
			}
		}
		return null;
	}

	/** A stop in the one run over every kind of patch: its number, the place, and the teleport to use (null: all excluded). */
	static final class StopView
	{
		final int stop;
		final String place;
		final Routines.Tele tele;

		StopView(int stop, String place, Routines.Tele tele)
		{
			this.stop = stop;
			this.place = place;
			this.tele = tele;
		}
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
		/* Its stop in the one run over every kind of patch (see StopView), 0 when it has none. */
		int stop;
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

	/**
	 * Daily tasks: due ones first, each with its status; right-click to tick one off or put them on screen. In Overview,
	 * only the favourite ones, and nothing when there are none. True when anything was shown.
	 */
	private boolean dailies(Extras x, boolean overview)
	{
		if (!x.dailiesOn || x.dailies.isEmpty())
		{
			return false;
		}
		String f = overview ? ALL : currentFilter();
		List<DailyView> shown = x.dailies;
		if (overview)
		{
			shown = Favourites.only(x.dailies, d -> d.id, favourites(DAILIES));
			if (shown.isEmpty())
			{
				return false;
			}
			String pin = Favourites.groupPin(DAILIES);
			JComponent header = sectionHeader(DAILIES, DAILIES, (int) shown.stream().filter(d -> d.due).count(),
				headingIcon(ItemID.GIANT_STOPWATCH), null, pin, x.pins.contains(pin));
			header.setComponentPopupMenu(dailiesMenu(x, null));
			inheritMenu(header);
			list.add(header);
			if (collapsed.contains(DAILIES))
			{
				return true;
			}
		}
		for (DailyView d : shown)
		{
			if ("Due".equals(f) && !d.due || "Done".equals(f) && d.due)
			{
				continue;
			}
			list.add(card(dailyCard(x, d, overview)));
		}
		return true;
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
		c.valueColour = d.due ? GOLD : DONE;
		c.sub = overview ? "" : d.how;
		c.tooltip = d.how.isEmpty() ? null : "<html>" + escape(d.label) + "<br>" + escape(d.how) + "</html>";
		c.menu = dailiesMenu(x, d);
		return c;
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

	/** Overview's Farming section: the tab's favourite patches and the contract, ready first. False (and nothing) when none. */
	private boolean farmingOverview(Extras x)
	{
		if (!x.farmOn)
		{
			return false;
		}
		Map<String, PatchView> byId = patchesById(x);
		List<String> shown = farmFavouriteIds(x, byId);
		if (shown.isEmpty())
		{
			return false;
		}
		String pin = Favourites.groupPin(FARMING);
		JComponent header = sectionHeader(FARMING, FARMING, shown.size(), sectionIcon(FARMING), null, pin, x.pins.contains(pin));
		header.setComponentPopupMenu(farmMenu(x));
		inheritMenu(header);
		list.add(header);
		if (!collapsed.contains(FARMING))
		{
			farmFavouriteRows(x, byId, shown, x.favouritesByRun);
		}
		return true;
	}

	private static Map<String, PatchView> patchesById(Extras x)
	{
		Map<String, PatchView> byId = new HashMap<>();
		for (PatchView p : x.patches)
		{
			byId.putIfAbsent(p.id, p);
		}
		return byId;
	}

	/** The Farming tab's favourites that are there now (a patch with a timer, or the contract), in the order saved. */
	private List<String> farmFavouriteIds(Extras x, Map<String, PatchView> byId)
	{
		List<String> shown = new ArrayList<>();
		for (String id : favourites(FARMING))
		{
			if (FarmGroups.CONTRACT_ID.equals(id) ? x.contract != null : byId.containsKey(id))
			{
				shown.add(id);
			}
		}
		return shown;
	}

	/**
	 * Those favourites as rows: the contract first, then a sub-heading per kind of patch, ready first in each, or (byRun)
	 * a card per stop in run order.
	 */
	private void farmFavouriteRows(Extras x, Map<String, PatchView> byId, List<String> shown, boolean byRun)
	{
		List<PatchView> ps = new ArrayList<>();
		boolean contract = false;
		for (String id : shown)
		{
			if (!FarmGroups.CONTRACT_ID.equals(id))
			{
				ps.add(byId.get(id));
			}
			else if (byRun)
			{
				contract = true;
			}
			else
			{
				list.add(subHeader(CONTRACT, 1));
				list.add(card(contractCard(x)));
			}
		}
		if (byRun)
		{
			runStops(x, ps, contract);
		}
		else
		{
			kindRows(x, ps);
		}
	}

	/**
	 * Run order: the favourite patches stop by stop, in the order Nib Tracker plans one run over every kind of patch with
	 * your bank, each stop with its teleport (one you haven't excluded in the settings) and what's ready there.
	 */
	private void runOrderView(Extras x)
	{
		String key = "farm:" + RUN_ORDER;
		Map<String, PatchView> byId = patchesById(x);
		List<String> shown = farmFavouriteIds(x, byId);
		String pin = Favourites.groupPin(FARMING);
		list.add(sectionHeader(key, RUN_ORDER, shown.size(), null, null, pin, x.pins.contains(pin)));
		if (collapsed.contains(key))
		{
			return;
		}
		if (shown.isEmpty())
		{
			list.add(note("Star patches in the other Farming views to plan a run with them."));
			return;
		}
		if (x.stops.isEmpty())
		{
			list.add(note("Open Nib Tracker to work out the run; until then each place is a stop."));
		}
		farmFavouriteRows(x, byId, shown, true);
	}

	/**
	 * Patches grouped by their stop, in run order; ones with no stop come last, a card per place, in name order. The
	 * contract, when it's a favourite, goes at the bottom of the Farming Guild's card (a card of its own at the end when
	 * no Farming Guild patch is).
	 */
	private void runStops(Extras x, List<PatchView> ps, boolean contract)
	{
		List<PatchView> sorted = new ArrayList<>(ps);
		sorted.sort(Comparator.comparingInt((PatchView p) -> p.stop > 0 ? p.stop : Integer.MAX_VALUE).thenComparing(p -> p.region));
		Map<String, List<PatchView>> stops = new LinkedHashMap<>();
		for (PatchView p : sorted)
		{
			stops.computeIfAbsent(p.stop > 0 ? "stop:" + p.stop : "place:" + p.region, k -> new ArrayList<>()).add(p);
		}
		boolean contractShown = !contract;
		for (List<PatchView> stop : stops.values())
		{
			boolean guild = !contractShown && FarmGroups.GUILD.equals(stop.get(0).region);
			contractShown |= guild;
			list.add(stopCard(x, x.stops.get(stop.get(0).stop), stop, guild));
		}
		if (!contractShown)
		{
			StopView guild = x.stops.values().stream().filter(v -> FarmGroups.GUILD.equals(v.place)).findFirst().orElse(null);
			list.add(stopCard(x, guild, Collections.emptyList(), true));
		}
	}

	/**
	 * A stop as one card: the teleport's icon, the place and how many are ready there, the teleport to use, then a line
	 * per patch (ready first) with its crop and time. Hover for how to use the teleport; right-click for the farming menu.
	 */
	private JComponent stopCard(Extras x, StopView s, List<PatchView> ps, boolean contract)
	{
		PatchView first = ps.isEmpty() ? null : ps.get(0);
		long ready = ps.stream().filter(p -> p.ready).count() + (contract && x.contract != null && x.contract.ready ? 1 : 0);
		boolean noTele = s != null && s.tele == null;
		String travel = s != null ? (noTele ? "" : s.tele.travel) : first != null ? first.travel : "";
		String how = s != null ? (noTele ? "" : s.tele.how) : first != null ? first.travelHow : "";
		Surface p = new Surface(false);
		p.setLayout(new BorderLayout(9, 0));
		p.setBorder(new EmptyBorder(8, 8, 8, 7));

		JLabel icon = new IconSlot(ICON);
		icon.setPreferredSize(new Dimension(ICON, ICON));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		icon.setVerticalAlignment(SwingConstants.TOP);
		if (s == null && first != null)
		{
			teleIcon(first.travelCat, first.travelItem, icon);
		}
		else if (!noTele)
		{
			teleIcon(s.tele.cat, s.tele.item, icon);
		}
		p.add(icon, BorderLayout.WEST);

		JPanel body = new JPanel(new DynamicGridLayout(0, 1, 0, 5));
		body.setOpaque(false);
		JLabel count = new JLabel(ready > 0 ? ready + " ready" : "");
		count.setFont(VALUE);
		count.setForeground(DONE);
		if (ready > 0)
		{
			count.setIcon(new Check(DONE));
			count.setIconTextGap(3);
		}
		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setOpaque(false);
		line.add(new Wrap(s != null ? s.place : first != null ? first.region : FarmGroups.GUILD, TITLE, ready > 0 ? INK : INK_2, TEXT - count.getPreferredSize().width - 6, 2), BorderLayout.CENTER);
		line.add(count, BorderLayout.EAST);
		body.add(line);
		body.add(new Wrap(noTele ? "Every teleport here is one you skip" : travel.isEmpty() ? "No teleport known yet" : travel,
			BODY, noTele ? AMBER : MUTED, TEXT, 0));
		for (PatchView v : FarmRows.readyFirst(ps, q -> q.ready))
		{
			body.add(stopPatch(v));
		}
		if (contract && x.contract != null)
		{
			body.add(contractLine(x.contract));
		}
		p.add(body, BorderLayout.CENTER);

		p.setToolTipText(how.isEmpty() ? null : "<html>" + escape(travel) + "<br><div style='width:180px'>" + escape(how) + "</div></html>");
		Card menu = new Card();
		menu.menu = farmMenu(x);
		p.setComponentPopupMenu(menu.menu);
		inheritMenu(p);
		p.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				p.hover(true);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				p.hover(false);
			}
		});
		keys(p, menu, () -> { }, () -> { });
		return p;
	}

	/**
	 * The contract's line at the bottom of the Farming Guild's card: its crop's icon and what it asks for, then its state
	 * beside it, or under it when it's long ("Ready · then see Jane for a new contract").
	 */
	private JComponent contractLine(FarmGroups.ContractView v)
	{
		JPanel l = new JPanel(new BorderLayout(6, 0));
		l.setOpaque(false);
		JLabel icon = new IconSlot(16);
		icon.setPreferredSize(new Dimension(16, 16));
		icon.setVerticalAlignment(SwingConstants.TOP);
		cropIcon(v.itemId).accept(icon);
		Color colour = v.ready ? DONE : v.attention ? RED : MUTED;
		int width = TEXT - 16 - 6;
		l.add(icon, BorderLayout.WEST);
		if (v.value.length() <= SHORT_VALUE)
		{
			JLabel status = new JLabel(v.value);
			status.setFont(VALUE);
			status.setForeground(colour);
			status.setVerticalAlignment(SwingConstants.TOP);
			if (v.ready)
			{
				status.setIcon(new Check(DONE));
				status.setIconTextGap(3);
			}
			l.add(new Wrap(v.title, BODY, INK_2, width - status.getPreferredSize().width - 6, 3), BorderLayout.CENTER);
			l.add(status, BorderLayout.EAST);
		}
		else
		{
			JPanel text = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
			text.setOpaque(false);
			text.add(new Wrap(v.title, BODY, INK_2, width, 3));
			text.add(new Wrap(v.value, VALUE, colour, width, 0));
			l.add(text, BorderLayout.CENTER);
		}
		return l;
	}

	/** A patch's line in a stop: its crop's icon and name, then ready (with a ✓) or its time; red when it needs you. */
	private JComponent stopPatch(PatchView v)
	{
		JPanel l = new JPanel(new BorderLayout(6, 0));
		l.setOpaque(false);
		JLabel icon = new IconSlot(16);
		icon.setPreferredSize(new Dimension(16, 16));
		cropIcon(v.itemId).accept(icon);
		JLabel status = new JLabel(v.status);
		status.setFont(VALUE);
		status.setForeground(v.ready ? DONE : v.attention ? RED : MUTED);
		if (v.ready)
		{
			status.setIcon(new Check(DONE));
			status.setIconTextGap(3);
		}
		l.add(icon, BorderLayout.WEST);
		l.add(new Wrap(v.crop, BODY, INK_2, TEXT - 16 - 6 - status.getPreferredSize().width - 6, 1), BorderLayout.CENTER);
		l.add(status, BorderLayout.EAST);
		return l;
	}

	/** Patches under a sub-heading per kind (Herbs, Hardwood...), in name order, ready first in each. */
	private void kindRows(Extras x, List<PatchView> ps)
	{
		for (Map.Entry<String, List<PatchView>> e : byKind(ps).entrySet())
		{
			list.add(subHeader(e.getKey(), e.getValue().size()));
			for (PatchView p : FarmRows.readyFirst(e.getValue(), v -> v.ready))
			{
				list.add(card(patchCard(x, p)));
			}
		}
	}

	/** Patches by their kind's label (its key when it has none), labels in name order, each list in the order given. */
	static Map<String, List<PatchView>> byKind(List<PatchView> ps)
	{
		Map<String, List<PatchView>> groups = new TreeMap<>();
		for (PatchView p : ps)
		{
			groups.computeIfAbsent(p.kindLabel.isEmpty() ? p.kind : p.kindLabel, k -> new ArrayList<>()).add(p);
		}
		return groups;
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
				farmGroup(x, SPECIAL, Favourites.SPECIAL_PIN, FarmGroups::special, "No special patches with timers yet.");
				break;
			case ALL_TREES:
				farmGroup(x, ALL_TREES, Favourites.TREES_PIN, FarmGroups::tree, "No tree patches with timers yet.");
				break;
			case RUN_ORDER:
				runOrderView(x);
				break;
			case CONTRACT:
				if (x.contract != null)
				{
					list.add(card(contractCard(x)));
				}
				break;
			case SPIRIT_TREES:
				spiritTrees(x);
				break;
			default:
				kindView(x, f);
		}
	}

	/** Spirit trees: each spot and whether a tree is planted there; a green bar when grown, amber when diseased or dead, grey while growing. */
	private void spiritTrees(Extras x)
	{
		String key = "farm:" + SPIRIT_TREES;
		list.add(sectionHeader(key, SPIRIT_TREES, x.spiritTrees.size(), null, null, null, false));
		if (collapsed.contains(key))
		{
			return;
		}
		for (PatchPredictor.SpiritTree t : x.spiritTrees)
		{
			boolean grown = "Grown".equals(t.state);
			boolean attention = "Diseased".equals(t.state) || "Dead".equals(t.state);
			Card c = new Card();
			c.icon = cropIcon(ItemID.PLANTPOT_SPIRIT_TREE_SAPLING);
			c.title = t.region;
			c.value = t.state;
			c.check = grown;
			c.valueColour = grown ? DONE : attention ? AMBER : MUTED;
			if (grown || attention)
			{
				c.progress = 1;
				c.barDone = grown;
				c.barColour = attention ? AMBER : null;
			}
			else if ("Growing".equals(t.state) && t.progress >= 0)
			{
				c.progress = t.progress;
				c.barColour = MUTED;
			}
			list.add(card(c));
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
				r.anyReady ? DONE : attention ? RED : INK_2);
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
			list.add(card(row));
			if (open)
			{
				// Patches as cards like every other patch row, so each one reads the same wherever it's shown.
				for (PatchView p : FarmRows.readyFirst(r.patches, v -> v.ready))
				{
					list.add(card(patchCard(x, p)));
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
		Map<String, PatchView> byId = patchesById(x);
		List<String> shown = farmFavouriteIds(x, byId);
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
		farmFavouriteRows(x, byId, shown, false);
	}

	/** Special or All trees, under a heading per kind, ready first: hidden regions too (hiding is for All patches only). */
	private void farmGroup(Extras x, String name, String pin, Predicate<String> kinds, String none)
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
		kindRows(x, ps);
	}

	/** A small heading inside a group (one kind of patch), with its count; it doesn't fold. */
	private static JComponent subHeader(String name, int count)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.setBorder(new EmptyBorder(4, 4, 0, 2));
		JLabel l = new JLabel(name);
		l.setFont(BODY);
		l.setForeground(INK_2);
		JLabel n = new JLabel(String.valueOf(count));
		n.setFont(BODY);
		n.setForeground(MUTED);
		p.setLayout(new BorderLayout(6, 0));
		p.add(l, BorderLayout.WEST);
		p.add(new Rule(), BorderLayout.CENTER);
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
		c.check = p.ready;
		c.valueColour = p.ready ? DONE : p.attention ? RED : INK_2;
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
		Color state = v.ready ? DONE : v.attention ? RED : INK_2;
		if (v.value.length() <= SHORT_VALUE)
		{
			c.value = v.value;
			c.check = v.ready;
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
			box.setFont(BODY);
			box.setForeground(INK);
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
		b.setFont(main ? SMALL_TITLE : BODY);
		b.setForeground(main ? GOLD : INK);
		b.setOpaque(true);
		b.setBackground(main ? GOLD_WASH : ColorScheme.DARKER_GRAY_COLOR);
		b.setBorder(new EmptyBorder(5, 5, 5, 5));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		onClick(b, action);
		return b;
	}

	/**
	 * One kind of patch (Herbs, say): its patches as cards, ready first, then in the order Nib Tracker's farm run planner
	 * visits them (each card says where and the teleport). ▣ on the header puts the kind on the game screen.
	 */
	private void kindView(Extras x, String label)
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
		// The pin is the kind's key; an old "farming:Herbs" pin still shows as pinned, and is what a click takes off.
		String legacy = GoalTracker.FARMING + ":" + label;
		String kind = ps.isEmpty() ? "" : ps.get(0).kind;
		boolean hasLegacy = x.pins.contains(legacy);
		String pin = hasLegacy || kind.isEmpty() ? legacy : Favourites.kindPin(kind);
		boolean pinned = hasLegacy || !kind.isEmpty() && x.pins.contains(Favourites.kindPin(kind));
		String key = "farm:kind:" + label;
		list.add(sectionHeader(key, label, ps.size(), farmingIcon(14), null, pin, pinned));
		if (collapsed.contains(key))
		{
			return;
		}
		for (PatchView p : FarmRows.readyFirst(ps, v -> v.ready))
		{
			list.add(card(patchCard(x, p)));
		}
		if (ps.isEmpty())
		{
			list.add(note("No patches of this kind."));
		}
	}

	/** The icon for a teleport: its item (a cape, a tablet...), or one for its kind (fairy ring, spell, spirit tree, house). */
	private void teleIcon(String cat, String travelItem, JLabel label)
	{
		BufferedImage fixed = null;
		int item = -1;
		switch (cat)
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
				if (!travelItem.isEmpty() && itemManager != null)
				{
					for (ItemPrice ip : itemManager.search(travelItem))
					{
						if (ip.getName().equalsIgnoreCase(travelItem))
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
			label.setIcon(new ImageIcon(fitTo(fixed, 20)));
		}
		else if (item > 0 && itemManager != null)
		{
			itemIcon(item, 24, label::setIcon);
		}
	}

	private static JMenuItem item(String text, Runnable run)
	{
		JMenuItem i = new JMenuItem(text);
		i.setFont(BODY);
		i.addActionListener(e -> run.run());
		return i;
	}

	private static JLabel note(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(BODY);
		l.setForeground(MUTED);
		l.setBorder(new EmptyBorder(4, 2, 4, 2));
		return l;
	}

	/** A plain row (a farm region): its crop's icon, a title, a status on the right in its state's colour. */
	private Card simpleCard(int itemId, String title, String value, Color valueColour)
	{
		Card c = new Card();
		c.icon = cropIcon(itemId);
		c.title = title;
		c.value = value;
		c.valueColour = valueColour;
		return c;
	}

	private boolean picksSection(String name, GoalLayout.Picks picks)
	{
		if (picks.isEmpty())
		{
			return false;
		}
		// ▣ puts that tab's favourites on the game screen (not for Completed).
		String pin = COMPLETED.equals(name) ? null : Favourites.groupPin(name);
		list.add(sectionHeader(name, name, picks.size(), sectionIcon(name), null, pin, pin != null && lastExtras.pins.contains(pin)));
		if (!collapsed.contains(name))
		{
			picks.groups.forEach(this::group);
			picks.rows.forEach(r -> list.add(card(goalCard(r, r.progress, r.detail))));
		}
		return true;
	}

	/**
	 * A main goal, and what hangs off it on a hairline: its Slayer task, and its parts when opened (Base 80 and its
	 * skills) from the "Show parts" line inside its row.
	 */
	private void group(GoalLayout.Group g)
	{
		Card main = mainCard(g);
		boolean open = expanded.contains(g.main.id);
		if (!g.parts.isEmpty())
		{
			main.parts = (open ? "Hide parts" : "Show parts") + "  ·  " + g.partsDone() + " of " + g.partsTotal() + " done";
			main.partsToggle = () -> {
				if (!expanded.remove(g.main.id))
				{
					expanded.add(g.main.id);
				}
				redraw();
			};
		}
		list.add(card(main));
		List<Card> under = new ArrayList<>();
		for (Row x : g.extras)
		{
			under.add(goalCard(x, x.progress, x.detail));
		}
		if (open)
		{
			for (Row p : g.parts)
			{
				under.add(goalCard(p, p.progress, p.detail));
			}
		}
		if (!under.isEmpty())
		{
			list.add(children(under));
		}
	}

	/** Rows that hang off the one above, flat and smaller, on a hairline running down their left. */
	private JComponent children(List<Card> cards)
	{
		JPanel p = new JPanel(new DynamicGridLayout(0, 1, 0, 6));
		p.setOpaque(false);
		p.setBorder(BorderFactory.createCompoundBorder(new EmptyBorder(0, CHILD_INDENT, 2, 0),
			BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, HAIR), new EmptyBorder(2, CHILD_PAD - 4, 2, 0))));
		for (Card c : cards)
		{
			p.add(row(c, true));
		}
		return p;
	}

	/** A main goal's row, measured by its parts when it has them ("3 / 5" for a yes/no goal with parts). */
	private Card mainCard(GoalLayout.Group g)
	{
		int partsDone = g.partsDone(), partsTotal = g.partsTotal();
		String detail = g.main.progress < 0 && partsTotal > 0 && !g.main.done ? partsDone + " / " + partsTotal : g.main.detail;
		return goalCard(g.main, g.progress(), detail);
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
	 * A section's heading, folded by key: a chevron, an icon (the ★ for Favourites) when there is one, the name in small
	 * spaced capitals, then a hairline running to the right-hand side. There, before the count, an "edit" link when
	 * there's a picker; after it, ▣ when the whole section can go on screen.
	 */
	private JComponent sectionHeader(String key, String name, int count, Icon icon, Runnable edit, String pinId, boolean pinned)
	{
		boolean shut = collapsed.contains(key);
		Heading p = new Heading();
		p.setOpaque(false);
		p.setBorder(new EmptyBorder(10, 2, 4, 2));
		JLabel fold = new JLabel(new Chevron(!shut));
		JLabel l = new JLabel(name.toUpperCase(), icon, SwingConstants.LEADING);
		l.setFont(HEAD);
		l.setForeground(COMPLETED.equals(name) ? DONE : INK_2);
		l.setIconTextGap(3);
		p.name = l;
		p.fold = fold;
		JPanel title = new JPanel(new BorderLayout(4, 0));
		title.setOpaque(false);
		title.add(fold, BorderLayout.WEST);
		title.add(l, BorderLayout.CENTER);
		JLabel n = new JLabel(String.valueOf(count));
		n.setFont(BODY);
		n.setForeground(MUTED);
		p.add(title, BorderLayout.WEST);
		p.add(new Rule(), BorderLayout.CENTER);
		if (edit == null && pinId == null)
		{
			p.add(n, BorderLayout.EAST);
		}
		else
		{
			// "edit" and ▣ take their own clicks, so they don't fold the section.
			JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
			east.setOpaque(false);
			if (edit != null)
			{
				JLabel e = new JLabel("edit");
				e.setFont(BODY);
				e.setForeground(GOLD);
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
			if (justDragged)
			{
				justDragged = false;
				return;
			}
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
		c.titleColour = r.done ? INK_2 : INK;
		c.value = r.done ? "Done" : detail;
		c.check = r.done;
		c.valueColour = r.done ? DONE : INK_2;
		// Quests are done or not: their state is the value, with no bar or percentage.
		boolean measured = progress >= 0 && !("Quests".equals(r.category) && r.partsTotal == 0);
		c.progress = r.done ? 1 : measured ? progress : -1;
		c.barDone = r.done;
		// What's left, then how far along; on screen is shown by its ▣, so it isn't said again here.
		String hint = r.done ? (r.points > 0 ? "+" + r.points + " points" : "") : r.hint;
		c.sub = r.done || !measured ? hint : hint.isEmpty() ? Math.round(progress * 100) + "%"
			: hint + "  ·  " + Math.round(progress * 100) + "%";
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
		// The plugin doesn't route you to a task: Nib Tracker's Slayer page does.
		if (GoalTracker.SLAYER_TASK.equals(r.id) && c.click != null)
		{
			c.parts = "How to get there in Nib Tracker";
			c.partsToggle = c.click;
		}
		return c;
	}

	private JComponent card(Card c)
	{
		return row(c, false);
	}

	/**
	 * One row, drawn the same on every tab: the icon (its slot kept when there's none, so rows line up), the title
	 * wrapping beside the value and then ★ and ▣, the bar when there's progress, the muted second line, and a main
	 * goal's "Show parts" line. A top-level row sits on a rounded surface with a faint ring that brightens under the
	 * mouse; a child (a main goal's part or Slayer task) is flat and smaller. ★ and ▣ show when they're on and otherwise only
	 * while the row is under the mouse or focused; in Overview every row is a favourite, so its ★ waits too.
	 * Rows take keyboard focus: Enter opens, Shift+F10 or the menu key opens the right-click menu.
	 */
	private JComponent row(Card c, boolean child)
	{
		Surface p = new Surface(child);
		p.setLayout(new BorderLayout(child ? 8 : 9, 0));
		p.setBorder(child ? new EmptyBorder(3, 4, 3, 4) : new EmptyBorder(8, 8, 8, 7));

		int iconSize = child ? CHILD_ICON : ICON;
		JLabel icon = new IconSlot(iconSize);
		icon.setPreferredSize(new Dimension(iconSize, iconSize));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		icon.setVerticalAlignment(SwingConstants.TOP);
		if (c.icon != null)
		{
			c.icon.accept(icon);
		}
		p.add(icon, BorderLayout.WEST);

		JPanel body = new JPanel(new DynamicGridLayout(0, 1, 0, child ? 4 : 5));
		body.setOpaque(false);

		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setOpaque(false);
		JLabel value = new JLabel(c.value);
		value.setFont(VALUE);
		value.setForeground(c.valueColour);
		value.setVerticalAlignment(SwingConstants.TOP);
		if (c.check && !c.value.isEmpty())
		{
			value.setIcon(new Check(c.valueColour));
			value.setIconTextGap(3);
		}
		boolean impliedFav = c.fav && OVERVIEW.equals(view);
		JLabel star = glyph(true, c.fav, "Add to favourites", "Remove from favourites",
			c.favTab == null ? null : () -> onFavourite.accept(c.favTab, c.id));
		JLabel pin = glyph(false, c.pinned, "Show on game screen", "Remove from game screen",
			c.pinId == null ? null : () -> onPin.accept(c.pinId));
		Runnable hideGlyphs = () -> {
			reveal(star, c.fav && !impliedFav);
			reveal(pin, c.pinned);
		};
		Runnable showGlyphs = () -> {
			reveal(star, true);
			reveal(pin, true);
		};
		hideGlyphs.run();
		JPanel glyphs = new JPanel(new GridLayout(1, 2, 2, 0));
		glyphs.setOpaque(false);
		glyphs.add(star);
		glyphs.add(pin);
		// The title has the whole width beside ★ ▣, so it shows in full (sharing it with "In progress" left a word or two).
		// The value goes on the next line: at the end of the bar ("93 / 99" after the gold), or on its own without one.
		int text = child ? CHILD_TEXT : TEXT;
		Font titleFont = child ? SMALL_TITLE : TITLE;
		boolean onBar = c.progress >= 0 && !c.value.isEmpty();
		line.add(new Wrap(c.title, titleFont, child ? INK_2 : c.titleColour, text - GLYPHS - 6, 3), BorderLayout.CENTER);
		line.add(glyphs, BorderLayout.EAST);
		body.add(line);
		if (!onBar && !c.value.isEmpty())
		{
			body.add(value);
		}

		if (c.progress >= 0)
		{
			Bar bar = new Bar(child ? 2 : 3);
			bar.set(c.progress, c.barDone);
			bar.fixed = c.barColour;
			if (onBar)
			{
				JPanel barLine = new JPanel(new BorderLayout(8, 0));
				barLine.setOpaque(false);
				barLine.add(bar, BorderLayout.CENTER);
				barLine.add(value, BorderLayout.EAST);
				body.add(barLine);
			}
			else
			{
				body.add(bar);
			}
		}

		if (c.sub != null && !c.sub.isEmpty())
		{
			// Wraps to the row's width, so a task description is shown in full.
			body.add(new Wrap(c.sub, BODY, c.subColour, text, 0));
		}

		JLabel parts = null;
		if (c.parts != null)
		{
			parts = new JLabel(c.parts);
			parts.setFont(BODY);
			parts.setForeground(GOLD);
			parts.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			onClick(parts, c.partsToggle);
			body.add(parts);
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
		}
		// Moving onto ★, ▣ or "Show parts" (they take their own clicks) keeps the row lit; it goes out only on leaving the row.
		MouseAdapter hover = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				p.hover(true);
				showGlyphs.run();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				Point at = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), p);
				if (!p.contains(at))
				{
					p.hover(false);
					if (!p.isFocusOwner())
					{
						hideGlyphs.run();
					}
				}
			}
		};
		p.addMouseListener(hover);
		star.addMouseListener(hover);
		pin.addMouseListener(hover);
		if (parts != null)
		{
			parts.addMouseListener(hover);
		}
		keys(p, c, showGlyphs, hideGlyphs);
		return p;
	}

	/** Keyboard for a row: it takes focus (a gold ring shows it), Enter opens it, Shift+F10 or the menu key opens its menu. */
	private static void keys(Surface p, Card c, Runnable showGlyphs, Runnable hideGlyphs)
	{
		p.setFocusable(true);
		p.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				p.focus(true);
				showGlyphs.run();
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				p.focus(false);
				if (!p.isHovered())
				{
					hideGlyphs.run();
				}
			}
		});
		if (c.click != null)
		{
			p.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
			p.getActionMap().put("open", action(c.click));
		}
		if (c.menu != null)
		{
			p.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), "menu");
			p.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "menu");
			p.getActionMap().put("menu", action(() -> c.menu.show(p, 12, p.getHeight())));
		}
	}

	private static AbstractAction action(Runnable run)
	{
		return new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				run.run();
			}
		};
	}

	/** Shows ★ or ▣ (its icon is kept on the label) or leaves its space empty, so values stay lined up either way. */
	private static void reveal(JLabel glyph, boolean show)
	{
		Object icon = glyph.getClientProperty(Glyph.class);
		if (icon instanceof Glyph)
		{
			glyph.setIcon(show ? (Glyph) icon : null);
		}
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
		g.setVerticalAlignment(SwingConstants.TOP);
		if (toggle == null)
		{
			return g;
		}
		Glyph icon = new Glyph(star, on);
		g.setIcon(icon);
		g.putClientProperty(Glyph.class, icon);
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
	/** Sets what hides Overview's reordering tip for good. */
	void onHideTip(Runnable onHideTip)
	{
		this.onHideTip = onHideTip;
	}

	/** Overview's tip on reordering its sections, with Hide to put it away once it's been read. */
	private JComponent reorderTip()
	{
		JLabel hide = new JLabel("Hide");
		hide.setFont(BODY);
		hide.setForeground(GOLD);
		hide.setVerticalAlignment(SwingConstants.TOP);
		hide.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		hide.setToolTipText("Hide this tip");
		onClick(hide, () -> {
			lastExtras.overviewTipHidden = true;
			onHideTip.run();
			redraw();
		});
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setOpaque(false);
		p.setBorder(new EmptyBorder(0, 2, 0, 2));
		p.add(new Wrap("Drag a heading, or right-click it, to change the order of these sections.", BODY, MUTED,
			PluginPanel.PANEL_WIDTH - 20 - 4 - 8 - hide.getPreferredSize().width, 0), BorderLayout.CENTER);
		p.add(hide, BorderLayout.EAST);
		return p;
	}

	/** An Overview heading's 14px icon: the same picture as its tab (the Daily tasks heading has its own). */
	private Icon sectionIcon(String section)
	{
		if ("Combat achievements".equals(section))
		{
			return icons.computeIfAbsent("heading:ca", k -> new ImageIcon(fitTo(CA_BOOK, 14)));
		}
		Skill skill = FARMING.equals(section) ? Skill.FARMING : "Bosses".equals(section) ? Skill.SLAYER : null;
		if (skill != null)
		{
			return icons.computeIfAbsent("heading:" + skill.name(), k -> new ImageIcon(fitTo(skillIcons.getSkillImage(skill, false), 14)));
		}
		int item = "Skills".equals(section) ? ItemID.SKILLCAPE_MAX : fallbackItem(section);
		return item > 0 ? headingIcon(item) : null;
	}

	/** Sets what saves Overview's section order: called with every section, top to bottom. */
	void onOverviewOrder(Consumer<List<String>> onOverviewOrder)
	{
		this.onOverviewOrder = onOverviewOrder;
	}

	/**
	 * Lets an Overview heading be dragged onto another to move its section there (the target shows a gold line where it
	 * will land), and adds Move up, Move down and Reset order to its right-click menu for doing the same without a drag.
	 */
	private void draggable(JComponent header, String section, List<String> order)
	{
		overviewHeaders.put(section, header);
		header.setToolTipText("Click to fold, drag to move");
		MouseAdapter drag = new MouseAdapter()
		{
			private Point start;
			private boolean moving;

			@Override
			public void mousePressed(MouseEvent e)
			{
				start = SwingUtilities.isLeftMouseButton(e) ? e.getPoint() : null;
				moving = false;
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				if (start == null || !moving && start.distance(e.getPoint()) < 5)
				{
					return;
				}
				moving = true;
				header.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
				String onto = dropTarget(header, e);
				markDrop(onto == null || onto.equals(section) ? null : overviewHeaders.get(onto), order.indexOf(onto) > order.indexOf(section));
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (!moving)
				{
					return;
				}
				moving = false;
				// Only a release over the heading would also fold it; one anywhere else has no click to swallow.
				justDragged = header.contains(e.getPoint());
				header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				markDrop(null, false);
				String onto = dropTarget(header, e);
				if (onto != null && !onto.equals(section))
				{
					setOverviewOrder(GoalLayout.move(order, section, onto));
				}
			}
		};
		header.addMouseListener(drag);
		header.addMouseMotionListener(drag);

		JPopupMenu menu = header.getComponentPopupMenu() != null ? header.getComponentPopupMenu() : new JPopupMenu();
		if (menu.getComponentCount() > 0)
		{
			menu.addSeparator();
		}
		int at = order.indexOf(section);
		List<String> shown = new ArrayList<>(overviewHeaders.keySet());
		if (shown.indexOf(section) > 0)
		{
			String above = shown.get(shown.indexOf(section) - 1);
			menu.add(item("Move up", () -> setOverviewOrder(GoalLayout.move(order, section, above))));
		}
		menu.add(item("Move down", () -> {
			// The next shown section below, if any; sections with nothing starred are skipped over.
			List<String> rest = order.subList(at + 1, order.size());
			rest.stream().filter(overviewHeaders::containsKey).findFirst()
				.ifPresent(below -> setOverviewOrder(GoalLayout.move(order, section, below)));
		}));
		menu.add(item("Reset order", () -> setOverviewOrder(GoalLayout.overviewOrder(""))));
		header.setComponentPopupMenu(menu);
		inheritMenu(header);
	}

	/** The section whose heading or rows are under the pointer: the last heading at or above it (the first, above them all). */
	private String dropTarget(JComponent header, MouseEvent e)
	{
		int y = SwingUtilities.convertPoint(header, e.getPoint(), list).y;
		String onto = null;
		for (Map.Entry<String, JComponent> h : overviewHeaders.entrySet())
		{
			if (onto == null || h.getValue().getY() <= y)
			{
				onto = h.getKey();
			}
		}
		return onto;
	}

	/** Marks the heading a drag will land on with a gold line, above it when moving up and below it when moving down. */
	private void markDrop(JComponent target, boolean below)
	{
		if (dropMark != null)
		{
			dropMark.setBorder(dropMarkBorder);
		}
		dropMark = target;
		if (target != null)
		{
			dropMarkBorder = target.getBorder();
			target.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(below ? 0 : 2, 0, below ? 2 : 0, 0, GOLD), dropMarkBorder));
		}
	}

	/** Saves Overview's new order and shows it at once (the saved setting comes back the same). */
	private void setOverviewOrder(List<String> order)
	{
		lastExtras.overviewOrder.clear();
		lastExtras.overviewOrder.addAll(order);
		onOverviewOrder.accept(order);
		SwingUtilities.invokeLater(this::redraw);
	}

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

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** A bundled font, loaded from the plugin's jar; the RuneScape small font stands in should it fail to load. */
	private static Font loadFont(String file)
	{
		try (InputStream in = GoalsPanel.class.getResourceAsStream("fonts/" + file))
		{
			if (in == null)
			{
				return FontManager.getRunescapeSmallFont();
			}
			return Font.createFont(Font.TRUETYPE_FONT, in);
		}
		catch (FontFormatException | IOException e)
		{
			return FontManager.getRunescapeSmallFont();
		}
	}

	/**
	 * A row's background: a rounded surface with a faint white ring, the ring brighter and the surface lifted under the
	 * mouse, and a gold ring when it has keyboard focus. A flat one (a child row) only shows the lift.
	 */
	private static final class Surface extends JPanel
	{
		private final boolean flat;
		private boolean hovered;
		private boolean focused;

		Surface(boolean flat)
		{
			this.flat = flat;
			setOpaque(false);
		}

		void hover(boolean on)
		{
			hovered = on;
			repaint();
		}

		boolean isHovered()
		{
			return hovered;
		}

		void focus(boolean on)
		{
			focused = on;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), h = getHeight();
			if (!flat || hovered)
			{
				g2.setColor(hovered ? SURFACE_HOVER : ColorScheme.DARKER_GRAY_COLOR);
				g2.fillRoundRect(0, 0, w, h, 8, 8);
			}
			if (!flat)
			{
				g2.setColor(hovered ? RING_HOVER : HAIR);
				g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
			}
			if (focused)
			{
				g2.setColor(GOLD);
				g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
			}
			g2.dispose();
		}
	}

	/**
	 * A section heading that fits its name to the width the panel really gives it, laid out each time: in the usual
	 * small capitals when they fit, smaller and then without the spacing when not, and cut short with "..." only past
	 * that, so a long name (Combat achievements, with its icon) never runs under the count and ▣.
	 */
	private static final class Heading extends JPanel
	{
		private static final Font[] FITS = {HEAD, HEAD_SMALL, HEAD_TIGHT};
		JLabel name;
		Component fold;

		Heading()
		{
			super(new BorderLayout(4, 0));
		}

		@Override
		public void doLayout()
		{
			if (name != null && fold != null && getWidth() > 0)
			{
				Component right = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.EAST);
				Insets in = getInsets();
				// The two gaps either side of the hairline (which can shrink to nothing), the fold sign and its gap.
				int room = getWidth() - in.left - in.right - 8 - (right == null ? 0 : right.getPreferredSize().width)
					- fold.getPreferredSize().width - 4;
				Font pick = FITS[FITS.length - 1];
				for (Font f : FITS)
				{
					if (labelWidth(f) <= room)
					{
						pick = f;
						break;
					}
				}
				// Set only on a change: setting a font asks for another layout, which would come back here.
				if (!pick.equals(name.getFont()))
				{
					name.setFont(pick);
				}
				Dimension size = labelWidth(pick) > room ? new Dimension(Math.max(1, room), name.getPreferredSize().height) : null;
				if (!Objects.equals(size, name.isPreferredSizeSet() ? name.getPreferredSize() : null))
				{
					name.setPreferredSize(size);
				}
			}
			super.doLayout();
		}

		/** The name's width in a font, its icon and gap included; tracked text is measured with its spacing. */
		private int labelWidth(Font f)
		{
			Icon icon = name.getIcon();
			double text = f.getStringBounds(name.getText(), Wrap.FRC).getWidth();
			return (int) Math.ceil(text) + (icon == null ? 0 : icon.getIconWidth() + name.getIconTextGap()) + 1;
		}
	}

	/**
	 * A 1px hairline across the middle of its space: a section heading's line from its name to its count. Its size is
	 * set, as a bare JComponent would otherwise report whatever size it was last given and grow the heading.
	 */
	private static final class Rule extends JComponent
	{
		private static final Dimension SIZE = new Dimension(1, 1);

		@Override
		public Dimension getPreferredSize()
		{
			return SIZE;
		}

		@Override
		public Dimension getMinimumSize()
		{
			return SIZE;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			g.setColor(HAIR);
			g.fillRect(0, getHeight() / 2, getWidth(), 1);
		}
	}

	/**
	 * Text wrapped to the width it's given, measured with the font it's drawn in (Swing's html labels measured Figtree
	 * differently from how they drew it, and cut words off at the edge). It starts from a width worked out ahead, and
	 * wraps again to the width the panel really gives it, which is what keeps rows inside the panel whatever its width
	 * in the client. A word too long for a line is broken; past maxLines (0: no limit) the last line ends with "…", the
	 * row's tooltip having it all.
	 */
	private static final class Wrap extends JComponent
	{
		private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
		private static final Dimension NARROW = new Dimension(1, 1);
		private final int maxLines;
		private String text;
		private List<String> lines;
		private final Font font;
		private int width;
		private final int lineHeight;
		private final float ascent;

		Wrap(String text, Font font, Color colour, int width, int maxLines)
		{
			this.text = text;
			this.font = font;
			this.width = Math.max(1, width);
			setForeground(colour);
			lineHeight = Math.round(font.getSize2D() * 1.3f);
			LineMetrics m = font.getLineMetrics("Ag", FRC);
			ascent = m.getAscent() + (lineHeight - m.getAscent() - m.getDescent()) / 2;
			this.maxLines = maxLines;
			lines = lines(text, font, this.width, maxLines);
		}

		void setText(String text)
		{
			this.text = text;
			lines = lines(text, font, width, maxLines);
			revalidate();
			repaint();
		}

		/** Given its real width: wraps again to it, and has the panel laid out again if that changed its height. */
		@Override
		public void setBounds(int x, int y, int w, int h)
		{
			super.setBounds(x, y, w, h);
			if (w > 0 && w != width)
			{
				width = w;
				int before = lines.size();
				lines = lines(text, font, width, maxLines);
				if (lines.size() != before)
				{
					SwingUtilities.invokeLater(this::revalidate);
				}
			}
		}

		private static float width(String s, Font font)
		{
			return (float) font.getStringBounds(s, FRC).getWidth();
		}

		/** Words onto lines greedily, breaking a word only when it can't fit a line on its own. */
		static List<String> lines(String text, Font font, int width, int maxLines)
		{
			List<String> out = new ArrayList<>();
			// No text takes no room (an empty status line under the header).
			if (text.isEmpty())
			{
				return out;
			}
			StringBuilder line = new StringBuilder();
			for (String word : text.split(" "))
			{
				String tried = line.length() == 0 ? word : line + " " + word;
				if (width(tried, font) <= width)
				{
					line.setLength(0);
					line.append(tried);
					continue;
				}
				if (line.length() > 0)
				{
					out.add(line.toString());
					line.setLength(0);
				}
				String rest = word;
				while (width(rest, font) > width && rest.length() > 1)
				{
					int n = rest.length() - 1;
					while (n > 1 && width(rest.substring(0, n), font) > width)
					{
						n--;
					}
					out.add(rest.substring(0, n));
					rest = rest.substring(n);
				}
				line.append(rest);
			}
			if (line.length() > 0)
			{
				out.add(line.toString());
			}
			if (maxLines > 0 && out.size() > maxLines)
			{
				List<String> cut = new ArrayList<>(out.subList(0, maxLines));
				String last = cut.get(maxLines - 1);
				while (!last.isEmpty() && width(last + "…", font) > width)
				{
					last = last.substring(0, last.length() - 1);
				}
				cut.set(maxLines - 1, last.trim() + "…");
				return cut;
			}
			return out;
		}

		/** As tall as its lines; it asks for no width, so it never pushes a row wider than the panel. */
		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(1, lines.size() * lineHeight);
		}

		@Override
		public Dimension getMinimumSize()
		{
			return NARROW;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			g2.setFont(font);
			g2.setColor(getForeground());
			for (int i = 0; i < lines.size(); i++)
			{
				g2.drawString(lines.get(i), 0, i * lineHeight + ascent);
			}
			g2.dispose();
		}
	}

	/** A section's fold sign: a small chevron pointing right when shut, down when open. */
	private static final class Chevron implements Icon
	{
		private final boolean open;

		Chevron(boolean open)
		{
			this.open = open;
		}

		@Override
		public int getIconWidth()
		{
			return 9;
		}

		@Override
		public int getIconHeight()
		{
			return 9;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(MUTED);
			g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Path2D.Double p = new Path2D.Double();
			if (open)
			{
				p.moveTo(x + 1.5, y + 3);
				p.lineTo(x + 4.5, y + 6);
				p.lineTo(x + 7.5, y + 3);
			}
			else
			{
				p.moveTo(x + 3, y + 1.5);
				p.lineTo(x + 6, y + 4.5);
				p.lineTo(x + 3, y + 7.5);
			}
			g2.draw(p);
			g2.dispose();
		}
	}

	/** A small ✓ before a done or ready value, in the value's colour. Drawn, as the RuneScape fonts have none. */
	private static final class Check implements Icon
	{
		private final Color colour;

		Check(Color colour)
		{
			this.colour = colour;
		}

		@Override
		public int getIconWidth()
		{
			return 9;
		}

		@Override
		public int getIconHeight()
		{
			return 9;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(colour);
			g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Path2D.Double p = new Path2D.Double();
			p.moveTo(x + 1, y + 4.5);
			p.lineTo(x + 3.5, y + 7);
			p.lineTo(x + 8, y + 2);
			g2.draw(p);
			g2.dispose();
		}
	}

	/** An icon slot that scales what it's given down to its size (a child row's 18px from the usual 28px icons). */
	private static final class IconSlot extends JLabel
	{
		private final int size;

		IconSlot(int size)
		{
			this.size = size;
		}

		@Override
		public void setIcon(Icon icon)
		{
			if (icon instanceof ImageIcon && (icon.getIconWidth() > size || icon.getIconHeight() > size))
			{
				icon = new ImageIcon(fitTo(ImageUtil.bufferedImageFromImage(((ImageIcon) icon).getImage()), size));
			}
			super.setIcon(icon);
		}
	}

	/** A slim rounded progress bar: gold deepening to bright gold as it fills, green when done. */
	private static final class Bar extends JComponent
	{
		/* How thick it's drawn, across the middle of whatever height its line gives it (beside a value, that's taller). */
		private final int thickness;
		private double value;
		private boolean done;
		/* A set colour for the fill (amber for a diseased spirit tree), or null for the usual. */
		Color fixed;

		Bar(int thickness)
		{
			this.thickness = thickness;
			setPreferredSize(new Dimension(0, thickness));
		}

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
			int h = thickness;
			g2.translate(0, (getHeight() - h) / 2);
			g2.setColor(WELL);
			g2.fillRoundRect(0, 0, w, h, h, h);
			int fill = (int) Math.round(w * value);
			if (fill > 0)
			{
				if (fixed != null || done)
				{
					g2.setColor(fixed != null ? fixed : DONE);
				}
				else
				{
					g2.setPaint(new GradientPaint(0, 0, GOLD_DEEP, w, 0, GOLD));
				}
				g2.fillRoundRect(0, 0, Math.max(fill, h), h, h, h);
			}
			g2.dispose();
		}
	}

	/** ★ or ▣ at 14px: gold when on, grey when off. Drawn rather than typed, as the RuneScape fonts have neither. */
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
			g2.setColor(on ? GOLD : MUTED);
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
