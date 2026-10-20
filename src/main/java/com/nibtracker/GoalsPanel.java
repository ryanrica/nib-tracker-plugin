package com.nibtracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
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

	/* Each section shows this many goals until "Show more" is clicked. */
	private static final int SECTION_LIMIT = 6;
	private static final String COMPLETED = "Completed";
	private static final String DAILIES = "Daily tasks";
	private static final String FARMING = "Farming";
	private static final String OVERVIEW = "Overview";
	private static final String ALL = "All";
	/* Sub-filter values in the order they're offered. */
	private static final List<String> TAG_ORDER = Arrays.asList("Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster", "Not started", "In progress");
	private static final BufferedImage LOGO = ImageUtil.loadImageResource(GoalsPanel.class, "goals_icon.png");
	/* The combat achievements book, as Nib Tracker shows it (OSRS Wiki's Combat achievements detail image, made small). */
	static final BufferedImage CA_BOOK = ImageUtil.loadImageResource(GoalsPanel.class, "combat_achievements.png");
	/* How far a main goal's parts are indented under it. */
	private static final int PART_INDENT = 14;

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
	/* Called with a daily task's id when it's ticked off, and with a region when it's shown or hidden. */
	private Consumer<String> onDailyDone = id -> { };
	private Consumer<String> onRegion = region -> { };
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
		if (extras.farmOn && !extras.allRegions.isEmpty())
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
			farming(extras, true);
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
			// One chip per kind of patch you have a timer on (Herbs, Trees, Fruit trees, Hespori...), each with its crop's icon.
			List<String> kinds = new ArrayList<>();
			kinds.add(ALL);
			Map<String, Integer> kindIcons = new HashMap<>();
			for (PatchView p : extras.patches)
			{
				if (!p.kindLabel.isEmpty() && !kinds.contains(p.kindLabel))
				{
					kinds.add(p.kindLabel);
				}
				kindIcons.putIfAbsent(p.kindLabel, p.itemId);
			}
			kinds.subList(1, kinds.size()).sort(String::compareTo);
			filters(kinds, kindIcons);
			farming(extras, false);
		}
		else
		{
			categoryView(view, layout);
		}
		list.revalidate();
		list.repaint();
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
					SwingUtilities.invokeLater(this::redraw);
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
		if (DAILIES.equals(v))
		{
			tab.setIcon(new ImageIcon(fitTo(GoalTracker.calendarIcon(), 22)));
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
			AsyncBufferedImage img = itemManager.getImage(item);
			img.onLoaded(() -> tab.setIcon(new ImageIcon(fitTo(img, 24))));
		}
		else
		{
			tab.setIcon(new ImageIcon(fitTo(LOGO, 22)));
		}
	}

	/** The sub-filters for the current view (tiers, quest state, patch type...), as small clickable chips. */
	private void filters(List<String> options)
	{
		filters(options, Collections.emptyMap());
	}

	/**
	 * Sub-filter chips in a grid that wraps onto as many rows as it needs, so every option shows (farming has many patch
	 * kinds). Chips can carry an item icon (a seed or crop for each patch kind).
	 */
	private void filters(List<String> options, Map<String, Integer> iconItems)
	{
		filters.removeAll();
		filters.setLayout(new GridLayout(0, iconItems.isEmpty() ? 3 : 2, 4, 4));
		String current = filter.getOrDefault(view, ALL);
		if (!options.contains(current))
		{
			current = ALL;
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
			if (item != null && item > 0 && itemManager != null)
			{
				AsyncBufferedImage img = itemManager.getImage(item);
				img.onLoaded(() -> chip.setIcon(new ImageIcon(fitTo(img, 14))));
				chip.setIconTextGap(4);
			}
			chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			chip.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					filter.put(view, o);
					redraw();
				}
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
		/* Regions in order (soonest first) with their patches, and every visible patch on its own for the type filter. */
		final List<Region> regions = new ArrayList<>();
		final List<PatchView> patches = new ArrayList<>();
		/* Every region with a timer, shown or not, for the show/hide menu. */
		final List<String> allRegions = new ArrayList<>();
		final Set<String> hiddenRegions = new HashSet<>();
		/* Patch kinds (Herbs...) shown on the game screen. */
		final Set<String> pinnedKinds = new HashSet<>();
	}

	static final class DailyView
	{
		final String id;
		final String label;
		final String status;
		final String how;
		final boolean due;

		DailyView(String id, String label, String status, String how, boolean due)
		{
			this.id = id;
			this.label = label;
			this.status = status;
			this.how = how;
			this.due = due;
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
			JComponent header = sectionHeader(DAILIES, (int) x.dailies.stream().filter(d -> d.due).count());
			header.setComponentPopupMenu(dailiesMenu(x, null));
			inheritMenu(header);
			list.add(header);
			if (collapsed.contains(DAILIES))
			{
				return;
			}
		}
		for (DailyView d : x.dailies)
		{
			if ("Due".equals(f) && !d.due || "Done".equals(f) && d.due)
			{
				continue;
			}
			JComponent line = line(null, d.label, d.status, overview || d.how.isEmpty() ? null : d.how, d.due ? AMBER : DONE, 0);
			line.setToolTipText(d.how.isEmpty() ? null : "<html>" + escape(d.label) + "<br>" + escape(d.how) + "</html>");
			line.setComponentPopupMenu(dailiesMenu(x, d));
			inheritMenu(line);
			list.add(line);
		}
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

	/**
	 * Farm timers. Overview and "All": by region, soonest first, each folding open to its patches. A patch type (Herb...):
	 * every patch of that type, soonest first, with its region and the best way there with what's in your bank.
	 */
	private void farming(Extras x, boolean overview)
	{
		if (!x.farmOn || x.allRegions.isEmpty())
		{
			return;
		}
		if (overview)
		{
			JComponent header = sectionHeader(FARMING, x.regions.size());
			header.setComponentPopupMenu(regionsMenu(x));
			inheritMenu(header);
			list.add(header);
			if (collapsed.contains(FARMING))
			{
				return;
			}
		}
		String f = overview ? ALL : currentFilter();
		if (!ALL.equals(f))
		{
			runOrder(x, f);
			return;
		}
		for (Region r : x.regions)
		{
			String key = "farm:" + r.name;
			boolean open = expanded.contains(key);
			JComponent row = line(null, (open ? "– " : "+ ") + r.name, r.summary, null, r.anyReady ? DONE : AMBER, 0);
			row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			row.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (SwingUtilities.isLeftMouseButton(e))
					{
						if (!expanded.remove(key))
						{
							expanded.add(key);
						}
						redraw();
					}
				}
			});
			JPopupMenu m = regionsMenu(x);
			m.insert(item("Hide " + r.name, () -> onRegion.accept(r.name)), 0);
			row.setComponentPopupMenu(m);
			inheritMenu(row);
			list.add(row);
			if (open)
			{
				String travel = r.patches.stream().map(p -> p.travel).filter(t -> !t.isEmpty()).findFirst().orElse("");
				for (PatchView p : r.patches)
				{
					list.add(line(p.itemId, p.crop + (p.type.isEmpty() ? "" : " (" + p.type + ")"), p.status, null, p.ready ? DONE : AMBER, PART_INDENT));
				}
				if (!travel.isEmpty())
				{
					JLabel t = new JLabel("Get there: " + travel);
					t.setFont(FontManager.getRunescapeSmallFont());
					t.setForeground(MUTED);
					t.setBorder(new EmptyBorder(0, PART_INDENT + 4, 2, 0));
					list.add(t);
				}
			}
		}
	}

	/**
	 * One kind of patch (Herbs, say) as a run: stops in the order Nib Tracker's farm run planner works out with your bank,
	 * each numbered, with the teleport to use there and its patches under it. Stops the planner didn't place go last.
	 */
	private void runOrder(Extras x, String kind)
	{
		List<PatchView> ps = new ArrayList<>();
		for (PatchView p : x.patches)
		{
			if (kind.equals(p.kindLabel))
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
		long ready = ps.stream().filter(p -> p.ready).count();
		boolean pinned = x.pinnedKinds.contains(kind);
		Runnable pin = () -> onPin.accept(GoalTracker.FARMING + ":" + kind);
		JComponent head = line(null, "Run order  ·  " + stops.size() + (stops.size() == 1 ? " stop" : " stops"), ready > 0 ? ready + " ready" : "Growing", null, ready > 0 ? DONE : AMBER, 0);
		JPopupMenu headMenu = new JPopupMenu();
		headMenu.add(item(pinned ? "Remove " + kind + " from game screen" : "Show " + kind + " on game screen", pin));
		head.setComponentPopupMenu(headMenu);
		head.setToolTipText("Right-click to " + (pinned ? "remove " + kind + " from" : "show " + kind + " on") + " the game screen");
		inheritMenu(head);
		list.add(head);
		int n = 0;
		for (List<PatchView> stop : stops.values())
		{
			n++;
			PatchView first = stop.get(0);
			long r = stop.stream().filter(p -> p.ready).count();
			String status = r > 0 ? r + " ready" : stop.stream().map(p -> p.status).min(Comparator.comparingInt(String::length).thenComparing(s -> s)).orElse("");
			JComponent row = line(null, n + "   " + first.region, status, null, r > 0 ? DONE : AMBER, 0);
			JPopupMenu m = new JPopupMenu();
			m.add(item(pinned ? "Remove " + kind + " from game screen" : "Show " + kind + " on game screen", pin));
			row.setComponentPopupMenu(m);
			inheritMenu(row);
			list.add(row);
			if (!first.travel.isEmpty())
			{
				JLabel t = new JLabel(first.travel);
				t.setFont(FontManager.getRunescapeSmallFont());
				t.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				t.setBorder(new EmptyBorder(1, PART_INDENT + 4, 1, 0));
				t.setIconTextGap(5);
				teleIcon(first, t);
				t.setToolTipText(first.travelHow.isEmpty() ? null : "<html><div style='width:180px'>" + escape(first.travelHow) + "</div></html>");
				list.add(t);
			}
			for (PatchView p : stop)
			{
				list.add(line(p.itemId, p.crop, p.status, null, p.ready ? DONE : AMBER, PART_INDENT));
			}
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
			AsyncBufferedImage img = itemManager.getImage(item);
			img.onLoaded(() -> label.setIcon(new ImageIcon(fitTo(img, 16))));
		}
	}

	/** Right-click on Farming: tick the regions to show, and put farming on the game screen. */
	private JPopupMenu regionsMenu(Extras x)
	{
		JPopupMenu m = new JPopupMenu();
		m.add(item(x.farmPinned ? "Remove farming from game screen" : "Show farming on game screen", () -> onPin.accept(GoalTracker.FARMING)));
		m.addSeparator();
		for (String region : x.allRegions)
		{
			JCheckBoxMenuItem c = new JCheckBoxMenuItem(region, !x.hiddenRegions.contains(region));
			c.addActionListener(e -> onRegion.accept(region));
			m.add(c);
		}
		return m;
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

	/** A compact row: optional item icon, a label, a status on the right, an optional muted line under it, a coloured edge. */
	private JComponent line(Integer itemId, String label, String status, String sub, Color edge, int indent)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, edge), new EmptyBorder(5, 6, 5, 8)));
		if (itemId != null && itemId > 0 && itemManager != null)
		{
			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(18, 16));
			AsyncBufferedImage img = itemManager.getImage(itemId);
			img.onLoaded(() -> icon.setIcon(new ImageIcon(ImageUtil.resizeImage(img, 18, 16, true))));
			p.add(icon, BorderLayout.WEST);
		}
		JPanel text = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
		text.setOpaque(false);
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel l = new JLabel(label);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(Color.WHITE);
		JLabel s = new JLabel(status);
		s.setFont(FontManager.getRunescapeSmallFont());
		s.setForeground(edge == DONE ? DONE : MUTED);
		top.add(l, BorderLayout.CENTER);
		top.add(s, BorderLayout.EAST);
		text.add(top);
		if (sub != null && !sub.isEmpty())
		{
			JLabel u = new JLabel("<html><div style='width:" + (HINT_WIDTH - indent) + "px'>" + escape(sub) + "</div></html>");
			u.setFont(FontManager.getRunescapeSmallFont());
			u.setForeground(MUTED);
			text.add(u);
		}
		p.add(text, BorderLayout.CENTER);
		if (indent == 0)
		{
			return p;
		}
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBorder(new EmptyBorder(0, indent, 0, 0));
		wrap.add(p);
		return wrap;
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
		String detail = g.main.progress < 0 && partsTotal > 0 && !g.main.done ? partsDone + " / " + partsTotal : g.main.detail;
		list.add(row(g.main, g.progress(), detail, 0));
		for (Row x : g.extras)
		{
			list.add(row(x, x.progress, x.detail, PART_INDENT));
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
				list.add(row(p, p.progress, p.detail, PART_INDENT));
			}
		}
	}

	/** A small clickable line ("+ 23 parts", "Show 4 more") that changes what's shown. */
	private JComponent toggle(String text, int indent, Runnable flip)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(HEADING);
		l.setBorder(new EmptyBorder(0, indent + 4, 2, 0));
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		l.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				flip.run();
				redraw();
			}
		});
		return l;
	}

	private JComponent sectionHeader(String name, int count)
	{
		boolean shut = collapsed.contains(name);
		JPanel p = new JPanel(new BorderLayout());
		p.setBorder(new EmptyBorder(6, 2, 2, 2));
		JLabel l = new JLabel((shut ? "+ " : "– ") + name.toUpperCase());
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(COMPLETED.equals(name) ? DONE : HEADING);
		JLabel n = new JLabel(String.valueOf(count));
		n.setFont(FontManager.getRunescapeSmallFont());
		n.setForeground(MUTED);
		p.add(l, BorderLayout.WEST);
		p.add(n, BorderLayout.EAST);
		p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		p.setToolTipText(shut ? "Show " + name.toLowerCase() : "Hide " + name.toLowerCase());
		p.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!collapsed.remove(name))
				{
					collapsed.add(name);
				}
				redraw();
			}
		});
		return p;
	}

	/** One goal. progress and detail can differ from the row's own (a main goal measured by its parts); parts are indented. */
	private JComponent row(Row r, double progress, String detail, int indent)
	{
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, r.done ? DONE : colour(progress)),
			new EmptyBorder(6, 6, 6, 8)));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(ICON, ICON));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		icon(r, icon);
		p.add(icon, BorderLayout.WEST);

		JPanel body = new JPanel(new DynamicGridLayout(0, 1, 0, 3));
		body.setOpaque(false);

		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setOpaque(false);
		JLabel title = new JLabel(r.title);
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setForeground(r.done ? MUTED : Color.WHITE);
		JLabel value = new JLabel(r.done ? "Done" : detail);
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(r.done ? DONE : progress < 0 ? MUTED : colour(progress).brighter());
		line.add(title, BorderLayout.CENTER);
		line.add(value, BorderLayout.EAST);
		body.add(line);

		// Quests are done or not: their state is the value, with no bar or percentage.
		boolean measured = progress >= 0 && !("Quests".equals(r.category) && r.partsTotal == 0);
		if (measured || r.done)
		{
			Bar bar = new Bar();
			bar.setPreferredSize(new Dimension(0, 5));
			bar.set(r.done ? 1 : progress, r.done);
			body.add(bar);
		}

		String hint = r.done ? (r.points > 0 ? "+" + r.points + " points" : "") : r.hint;
		if (!hint.isEmpty() || (!r.done && measured) || r.pinned)
		{
			String text = r.done || !measured ? hint : hint.isEmpty() ? Math.round(progress * 100) + "%"
				: hint + "  ·  " + Math.round(progress * 100) + "%";
			if (r.pinned)
			{
				text = text.isEmpty() ? "On screen" : text + "  ·  On screen";
			}
			// Wraps to the row's width, so a task description is shown in full.
			JLabel h = new JLabel("<html><div style='width:" + (HINT_WIDTH - indent) + "px'>" + escape(text) + "</div></html>");
			h.setFont(FontManager.getRunescapeSmallFont());
			h.setForeground(r.done ? DONE.darker() : MUTED);
			body.add(h);
		}
		p.add(body, BorderLayout.CENTER);

		p.setToolTipText("<html>" + escape(r.title) + (r.link == null ? "" : r.link.startsWith(GoalTracker.WIKI) ? "<br>Click for the OSRS Wiki" : "<br>Click to open it in Nib Tracker")
			+ "<br>Right-click to " + (r.pinned ? "remove it from" : "show it on") + " the game screen</html>");
		// Right-click: show this goal on the game screen, or take it off.
		JPopupMenu menu = new JPopupMenu();
		JMenuItem pin = new JMenuItem(r.pinned ? "Remove from game screen" : "Show on game screen");
		pin.addActionListener(e -> onPin.accept(r.id));
		menu.add(pin);
		p.setComponentPopupMenu(menu);
		inheritMenu(p);
		if (r.link != null)
		{
			p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			p.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (SwingUtilities.isLeftMouseButton(e))
					{
						LinkBrowser.browse(r.link);
					}
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					p.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}
			});
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
		if (r.item != null)
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
		img.onLoaded(() -> {
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

	void onDailyDone(Consumer<String> onDailyDone)
	{
		this.onDailyDone = onDailyDone;
	}

	void onRegion(Consumer<String> onRegion)
	{
		this.onRegion = onRegion;
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
