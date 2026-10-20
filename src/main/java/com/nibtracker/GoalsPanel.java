package com.nibtracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
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
	private static final int HINT_WIDTH = PluginPanel.PANEL_WIDTH - 82;

	private final ItemManager itemManager;
	private final SkillIconManager skillIcons;
	private final Map<String, Icon> icons = new HashMap<>();
	/* Sections folded away (Completed starts folded), sections showing all their goals, and main goals showing their parts. */
	private final Set<String> collapsed = new HashSet<>(Collections.singleton(COMPLETED));
	private final Set<String> showAll = new HashSet<>();
	private final Set<String> expanded = new HashSet<>();

	private final JLabel heading = new JLabel("Goals");
	private final JLabel summary = new JLabel();
	private final Bar overall = new Bar();
	private final JLabel status = new JLabel();
	private final JPanel list = new JPanel(new DynamicGridLayout(0, 1, 0, 4));

	private String lastTitle;
	private String lastMessage;
	private List<Row> lastRows = new ArrayList<>();

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
		top.add(status);
		add(top, BorderLayout.NORTH);
		add(list, BorderLayout.CENTER);
		show("Goals", "Log in with Sync to Nib Tracker on to see your goals here.", new ArrayList<>());
	}

	void show(String title, String message, List<Row> rows)
	{
		lastTitle = title;
		lastMessage = message;
		lastRows = rows;
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

		list.removeAll();
		for (Map.Entry<String, List<GoalLayout.Group>> s : layout.sections.entrySet())
		{
			section(s.getKey(), s.getValue());
		}
		if (!layout.completed.isEmpty())
		{
			section(COMPLETED, layout.completed);
		}
		list.revalidate();
		list.repaint();
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
				show(lastTitle, lastMessage, lastRows);
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
				show(lastTitle, lastMessage, lastRows);
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
		if (!hint.isEmpty() || (!r.done && measured))
		{
			String text = r.done || !measured ? hint : hint.isEmpty() ? Math.round(progress * 100) + "%"
				: hint + "  ·  " + Math.round(progress * 100) + "%";
			// Wraps to the row's width, so a task description is shown in full.
			JLabel h = new JLabel("<html><div style='width:" + (HINT_WIDTH - indent) + "px'>" + escape(text) + "</div></html>");
			h.setFont(FontManager.getRunescapeSmallFont());
			h.setForeground(r.done ? DONE.darker() : MUTED);
			body.add(h);
		}
		p.add(body, BorderLayout.CENTER);

		p.setToolTipText("<html>" + escape(r.title) + (r.link == null ? "" : r.link.startsWith(GoalTracker.WIKI) ? "<br>Click for the OSRS Wiki" : "<br>Click to open it in Nib Tracker") + "</html>");
		if (r.link != null)
		{
			p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			p.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					LinkBrowser.browse(r.link);
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
