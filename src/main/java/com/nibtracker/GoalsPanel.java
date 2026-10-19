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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

		Row(String category, String title, String detail, String hint, double progress, boolean done, int points,
			Skill skill, String item, int fallbackItem, String link)
		{
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

	private static final Color RED = new Color(0xC8, 0x4B, 0x3C);
	private static final Color AMBER = new Color(0xE0, 0xA0, 0x30);
	private static final Color GREEN = new Color(0x4C, 0xB8, 0x50);
	private static final Color DONE = new Color(0x3F, 0xC2, 0x5F);
	private static final Color UNKNOWN = new Color(0x5A, 0x5A, 0x5A);
	private static final Color MUTED = new Color(0x9A, 0x9A, 0x9A);
	private static final Color HEADING = new Color(0xD8, 0xB6, 0x6A);
	private static final int ICON = 28;

	private final ItemManager itemManager;
	private final SkillIconManager skillIcons;
	private final Map<String, Icon> icons = new HashMap<>();
	private final Set<String> collapsed = new HashSet<>();

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

		long done = rows.stream().filter(r -> r.done).count();
		double avg = rows.stream().mapToDouble(r -> r.done ? 1 : Math.max(0, r.progress)).average().orElse(0);
		summary.setText(done + " of " + rows.size() + " done  ·  " + Math.round(avg * 100) + "% overall");
		summary.setVisible(!rows.isEmpty());
		overall.set(avg, done == rows.size());
		overall.setVisible(!rows.isEmpty());

		list.removeAll();
		Map<String, List<Row>> sections = new LinkedHashMap<>();
		for (String c : GoalProgress.CATEGORIES)
		{
			sections.put(c, new ArrayList<>());
		}
		for (Row r : rows)
		{
			sections.computeIfAbsent(r.category, k -> new ArrayList<>()).add(r);
		}
		for (Map.Entry<String, List<Row>> s : sections.entrySet())
		{
			List<Row> in = s.getValue();
			if (in.isEmpty())
			{
				continue;
			}
			// Closest to done first; finished goals at the bottom.
			in.sort(Comparator.comparing((Row r) -> r.done).thenComparing(r -> -r.progress));
			list.add(sectionHeader(s.getKey(), (int) in.stream().filter(r -> r.done).count(), in.size()));
			if (!collapsed.contains(s.getKey()))
			{
				for (Row r : in)
				{
					list.add(row(r));
				}
			}
		}
		list.revalidate();
		list.repaint();
	}

	private JComponent sectionHeader(String name, int done, int total)
	{
		boolean shut = collapsed.contains(name);
		JPanel p = new JPanel(new BorderLayout());
		p.setBorder(new EmptyBorder(6, 2, 2, 2));
		JLabel l = new JLabel((shut ? "+ " : "– ") + name.toUpperCase());
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(HEADING);
		JLabel n = new JLabel(done + " / " + total);
		n.setFont(FontManager.getRunescapeSmallFont());
		n.setForeground(done == total ? DONE : MUTED);
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

	private JComponent row(Row r)
	{
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, r.done ? DONE : colour(r.progress)),
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
		JLabel value = new JLabel(r.done ? "Done" : r.detail);
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(r.done ? DONE : r.progress < 0 ? MUTED : colour(r.progress).brighter());
		line.add(title, BorderLayout.CENTER);
		line.add(value, BorderLayout.EAST);
		body.add(line);

		// Quests are done or not: their state is the value, with no bar or percentage.
		boolean measured = r.progress >= 0 && !"Quests".equals(r.category);
		if (measured || r.done)
		{
			Bar bar = new Bar();
			bar.setPreferredSize(new Dimension(0, 5));
			bar.set(r.done ? 1 : r.progress, r.done);
			body.add(bar);
		}

		String hint = r.done ? (r.points > 0 ? "+" + r.points + " points" : "") : r.hint;
		if (!hint.isEmpty() || (!r.done && measured))
		{
			JLabel h = new JLabel(r.done || !measured ? hint : hint.isEmpty() ? Math.round(r.progress * 100) + "%"
				: hint + "  ·  " + Math.round(r.progress * 100) + "%");
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
		return p;
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
