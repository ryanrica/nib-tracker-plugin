package com.nibtracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * What the player chose to show on the game screen (▣ in the Goals panel): goals, daily tasks, patches, the farming
 * contract, groups of patches and favourites lists. Entries are drawn in boxes, one per tab or group they were pinned
 * from, stacked: a title line ("Farming · 2 ready"), then each entry; a box with a single entry is just that entry.
 * Detailed style shows, for each entry, its icon, name, value, a progress bar and one line of detail (the next teleport,
 * the task); Compact shows icon and value. Hover over an entry for everything (what's left, the whole run, how to get
 * there). Size from the plugin settings; Alt-drag to move. GoalTracker works the entries out once per change and set()
 * groups them; this only draws them.
 */
@Singleton
class GoalOverlay extends Overlay
{
	/**
	 * One entry: the box it's drawn in, icon, name, value ("73/80", "3 ready"), a line of detail, progress 0..1 (-1: no
	 * bar), and hover text.
	 */
	static final class Entry
	{
		final String box;
		final BufferedImage icon;
		final String title;
		final String value;
		final String detail;
		final double progress;
		/* Something to act on now (patches ready, tasks due): the value shows in green. */
		final boolean ready;
		final String tooltip;
		/* The value up to its first " · ", for Compact ("Ready · then see Jane..." is "Ready"), without " left". */
		final String shortValue;

		Entry(String box, BufferedImage icon, String title, String value, String detail, double progress, boolean ready, String tooltip)
		{
			this.box = box == null ? "" : box;
			this.icon = icon;
			this.title = title;
			this.value = value;
			this.detail = detail == null ? "" : detail;
			this.progress = progress;
			this.ready = ready;
			this.tooltip = tooltip;
			int dot = value.indexOf(" · ");
			// Compact drops a trailing " left" too: the Slayer task's "120 left" is "120".
			this.shortValue = (dot > 0 ? value.substring(0, dot) : value).replaceFirst(" left$", "");
		}
	}

	/** A box as drawn: its title line (null for a single entry, which needs none) and its entries. */
	private static final class Box
	{
		final String heading;
		final List<Entry> entries;

		Box(String heading, List<Entry> entries)
		{
			this.heading = heading;
			this.entries = entries;
		}
	}

	private static final String STAR = "★ ";
	private static final int BOX_GAP = 6;

	private static final Color READY = new Color(0x3F, 0xC2, 0x5F);
	private static final Color TITLE = new Color(0xF2, 0xEF, 0xE6);
	private static final Color DETAIL = new Color(0xB4, 0xB0, 0xA4);
	private static final Color HEADING = new Color(0xD8, 0xB6, 0x6A);
	private static final Color TRACK = new Color(0, 0, 0, 150);
	private static final Color EDGE = new Color(255, 255, 255, 28);
	private static final Color DIVIDER = new Color(255, 255, 255, 18);

	private final Client client;
	private final TooltipManager tooltips;
	private final NibTrackerConfig config;
	private volatile List<Box> boxes = Collections.emptyList();
	/* Each icon resized once to the size it's drawn at, smoothly, rather than stretched as each frame is drawn (which
	   blurred the pixel art). Emptied when the entries change, and again when an item's picture finishes loading. */
	private final Map<String, BufferedImage> sized = new ConcurrentHashMap<>();

	@Inject
	GoalOverlay(NibTrackerPlugin plugin, Client client, TooltipManager tooltips, NibTrackerConfig config)
	{
		super(plugin);
		this.client = client;
		this.tooltips = tooltips;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	/** Any thread. Groups the entries into boxes here, once per change, so render only draws. */
	void set(List<Entry> entries)
	{
		sized.clear();
		for (Entry e : entries)
		{
			if (e.icon instanceof AsyncBufferedImage)
			{
				((AsyncBufferedImage) e.icon).onLoaded(sized::clear);
			}
		}
		List<Box> out = new ArrayList<>();
		boolean favouriteTitles = config.overlayFavouriteTitles();
		for (Map.Entry<String, List<Entry>> b : boxes(entries))
		{
			// A favourites box can go without its "★ Farming favourites · 6 ready" title (the plugin setting).
			String title = !favouriteTitles && b.getKey().startsWith(STAR) ? null : heading(b.getKey(), b.getValue());
			out.add(new Box(title, b.getValue()));
		}
		this.boxes = Collections.unmodifiableList(out);
	}

	/** The entries grouped by box: boxes in the order first seen, entries in their order within each box. */
	static List<Map.Entry<String, List<Entry>>> boxes(List<Entry> entries)
	{
		Map<String, List<Entry>> byBox = new LinkedHashMap<>();
		for (Entry e : entries)
		{
			byBox.computeIfAbsent(e.box, k -> new ArrayList<>()).add(e);
		}
		List<Map.Entry<String, List<Entry>>> out = new ArrayList<>();
		for (Map.Entry<String, List<Entry>> b : byBox.entrySet())
		{
			out.add(new AbstractMap.SimpleImmutableEntry<>(b.getKey(), Collections.unmodifiableList(b.getValue())));
		}
		return out;
	}

	/**
	 * A box's title line: its name and how many of its entries are ready ("Farming · 2 ready"; "due" for daily tasks).
	 * Null for a single pinned entry, which is a one-line box; a favourites list keeps its title even with one.
	 */
	static String heading(String box, List<Entry> entries)
	{
		if (entries.size() == 1 && !box.startsWith(STAR))
		{
			return null;
		}
		long ready = entries.stream().filter(e -> e.ready).count();
		return ready == 0 ? box : box + " · " + ready + (box.contains("Daily tasks") ? " due" : " ready");
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		List<Box> list = boxes;
		if (list.isEmpty())
		{
			return null;
		}
		int size = config.overlaySize().px;
		boolean compact = config.overlayStyle() == NibTrackerConfig.OverlayStyle.COMPACT;
		// The boxes stacked one under the other, in one overlay, so Alt-drag moves them together.
		int width = 0, y = 0;
		for (Box b : list)
		{
			Dimension d = compact ? compact(g, b, size, y) : detailed(g, b, size, y);
			width = Math.max(width, d.width);
			y += d.height + BOX_GAP;
		}
		return new Dimension(width, y - BOX_GAP);
	}

	/**
	 * A card at y0: the title line, then each entry with icon, name and value, a bar, and a line of detail. A value too
	 * long to sit beside the name (the contract's "Ready · then see Jane...") gets its own line under it.
	 */
	private Dimension detailed(Graphics2D g, Box box, int size, int y0)
	{
		List<Entry> list = box.entries;
		Font titleFont = size >= 26 ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont();
		Font smallFont = FontManager.getRunescapeSmallFont();
		FontMetrics tm = g.getFontMetrics(titleFont), sm = g.getFontMetrics(smallFont);
		int pad = 6, icon = size - 2, gap = 6, bar = Math.max(3, size / 6);
		int width = Math.round(size * 7.6f);
		int textX = pad + icon + gap, textW = width - pad - textX;

		// Lay out first, so the background can be drawn behind everything at the right height.
		int headH = box.heading == null ? 0 : sm.getAscent() + 6;
		int[] tops = new int[list.size()];
		int[] heights = new int[list.size()];
		boolean[] ownLine = new boolean[list.size()];
		int y = pad + headH;
		for (int i = 0; i < list.size(); i++)
		{
			Entry e = list.get(i);
			ownLine[i] = tm.stringWidth(e.value) > textW / 2;
			int h = tm.getAscent() + 3 + (ownLine[i] ? sm.getAscent() + 2 : 0) + ((e.progress >= 0 || e.ready) ? bar + 3 : 0)
				+ (e.detail.isEmpty() ? 0 : sm.getAscent() + 2);
			tops[i] = y0 + y;
			heights[i] = Math.max(icon, h);
			y += heights[i] + (i < list.size() - 1 ? 7 : 0);
		}
		int height = y + pad;

		g.setColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
		g.fillRoundRect(0, y0, width, height, 6, 6);
		g.setColor(EDGE);
		g.drawRoundRect(0, y0, width - 1, height - 1, 6, 6);
		if (box.heading != null)
		{
			g.setFont(smallFont);
			drawHeading(g, sm, box.heading, pad, y0 + pad + sm.getAscent(), width - 2 * pad);
			// A hairline under the title, as the panel's section headings have.
			g.setColor(DIVIDER);
			g.drawLine(pad, y0 + pad + headH - 3, width - pad, y0 + pad + headH - 3);
		}

		Point mouse = mousePoint();
		Rectangle at = getBounds();
		for (int i = 0; i < list.size(); i++)
		{
			Entry e = list.get(i);
			int top = tops[i];
			if (i > 0)
			{
				g.setColor(DIVIDER);
				g.drawLine(pad, top - 4, width - pad, top - 4);
			}
			drawIcon(g, e.icon, pad, top, icon);
			// Name on the left, value on the right; the name gives way if both don't fit.
			g.setFont(titleFont);
			String value = e.value;
			int valueW = tm.stringWidth(value);
			int ty = top + tm.getAscent();
			shadowed(g, fit(tm, e.title, ownLine[i] ? textW : textW - valueW - 6), textX, ty, TITLE);
			if (!ownLine[i])
			{
				shadowed(g, value, width - pad - valueW, ty, valueColour(e));
			}
			int cy = ty + 3;
			if (ownLine[i])
			{
				g.setFont(smallFont);
				shadowed(g, fit(sm, value, textW), textX, cy + sm.getAscent() - 1, valueColour(e));
				cy += sm.getAscent() + 2;
			}
			if (e.progress >= 0 || e.ready)
			{
				drawBar(g, e, textX, cy, textW, bar);
				cy += bar + 3;
			}
			if (!e.detail.isEmpty())
			{
				g.setFont(smallFont);
				shadowed(g, fit(sm, e.detail, textW), textX, cy + sm.getAscent() - 1, DETAIL);
			}
			hover(e, mouse, at, new Rectangle(0, top - 3, width, heights[i] + 6));
		}
		return new Dimension(width, height);
	}

	/** Small boxes at y0: the title line, then icon and value per entry, with the bar along the bottom of each. */
	private Dimension compact(Graphics2D g, Box box, int size, int y0)
	{
		List<Entry> list = box.entries;
		int bar = Math.max(3, size / 7);
		Point mouse = mousePoint();
		Rectangle at = getBounds();
		int width = 0, y = y0;
		if (box.heading != null)
		{
			Font smallFont = FontManager.getRunescapeSmallFont();
			FontMetrics sm = g.getFontMetrics(smallFont);
			int h = sm.getAscent() + 5, w = headingWidth(sm, box.heading) + 8;
			g.setColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
			g.fillRoundRect(0, y, w, h, 6, 6);
			g.setFont(smallFont);
			drawHeading(g, sm, box.heading, 4, y + 2 + sm.getAscent(), w - 8);
			width = w;
			y += h + 2;
		}
		g.setFont(size >= 30 ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont());
		FontMetrics fm = g.getFontMetrics();
		for (Entry e : list)
		{
			String value = e.shortValue;
			int textW = fm.stringWidth(value);
			int rowW = size + (textW > 0 ? 3 + textW + 4 : 0);
			int inner = size - bar - 2;
			g.setColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
			g.fillRoundRect(0, y, rowW, size, 6, 6);
			drawIcon(g, e.icon, (size - inner) / 2, y + 1, inner);
			if (textW > 0)
			{
				shadowed(g, value, size + 3, y + 1 + (inner + fm.getAscent() - fm.getDescent()) / 2, valueColour(e));
			}
			if (e.progress >= 0 || e.ready)
			{
				drawBar(g, e, 3, y + size - bar - 2, rowW - 6, bar);
			}
			hover(e, mouse, at, new Rectangle(0, y, rowW, size));
			width = Math.max(width, rowW);
			y += size + 3;
		}
		return new Dimension(width, y - 3 - y0);
	}

	/** Green when it's ready, light for a measured value, gold for something waiting on you (a yes/no goal, a timer). */
	private static Color valueColour(Entry e)
	{
		return e.ready ? READY : e.progress >= 0 ? TITLE : GoalsPanel.GOLD;
	}

	/**
	 * A slim rounded bar, as the panel draws it: a dark track, then gold deepening to bright gold as it fills, or green
	 * when ready.
	 */
	private static void drawBar(Graphics2D g, Entry e, int x, int y, int w, int h)
	{
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(TRACK);
		g.fillRoundRect(x, y, w, h, h, h);
		int fill = (int) Math.round(w * (e.progress < 0 ? 1 : Math.min(1, e.progress)));
		if (fill > 0)
		{
			Paint before = g.getPaint();
			g.setPaint(e.ready ? READY : new GradientPaint(x, 0, GoalsPanel.GOLD_DEEP, x + w, 0, GoalsPanel.GOLD));
			g.fillRoundRect(x, y, Math.max(fill, h), h, h, h);
			g.setPaint(before);
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
	}

	/* The RuneScape fonts have no ★, so a favourites title gets a drawn star in its place. */
	private static int headingWidth(FontMetrics fm, String text)
	{
		return text.startsWith(STAR) ? fm.getAscent() + 2 + fm.stringWidth(text.substring(STAR.length())) : fm.stringWidth(text);
	}

	private static void drawHeading(Graphics2D g, FontMetrics fm, String text, int x, int baseline, int width)
	{
		if (text.startsWith(STAR))
		{
			int s = fm.getAscent();
			g.setColor(Color.BLACK);
			g.fillPolygon(star(x + 1, baseline - s + 1, s));
			g.setColor(HEADING);
			g.fillPolygon(star(x, baseline - s, s));
			x += s + 2;
			width -= s + 2;
			text = text.substring(STAR.length());
		}
		shadowed(g, fit(fm, text, width), x, baseline, HEADING);
	}

	/** A five-pointed star in an s by s square at x, y. */
	private static Polygon star(int x, int y, int s)
	{
		Polygon p = new Polygon();
		double cx = x + s / 2.0, cy = y + s / 2.0 + s * 0.05, outer = s / 2.0, inner = outer * 0.45;
		for (int i = 0; i < 10; i++)
		{
			double r = i % 2 == 0 ? outer : inner, a = -Math.PI / 2 + i * Math.PI / 5;
			p.addPoint((int) Math.round(cx + r * Math.cos(a)), (int) Math.round(cy + r * Math.sin(a)));
		}
		return p;
	}

	private void drawIcon(Graphics2D g, BufferedImage img, int x, int y, int box)
	{
		if (img == null || img.getWidth() <= 0)
		{
			return;
		}
		double s = Math.min((double) box / img.getWidth(), (double) box / img.getHeight());
		int w = Math.max(1, (int) Math.round(img.getWidth() * s)), h = Math.max(1, (int) Math.round(img.getHeight() * s));
		BufferedImage fit = w == img.getWidth() && h == img.getHeight() ? img
			: sized.computeIfAbsent(System.identityHashCode(img) + ":" + w + "x" + h, k -> ImageUtil.resizeImage(img, w, h));
		g.drawImage(fit, x + (box - w) / 2, y + (box - h) / 2, null);
	}

	private static void shadowed(Graphics2D g, String text, int x, int y, Color c)
	{
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, y + 1);
		g.setColor(c);
		g.drawString(text, x, y);
	}

	/** Text cut to fit a width, ending ".." when it's cut. */
	private static String fit(FontMetrics fm, String text, int width)
	{
		if (fm.stringWidth(text) <= width)
		{
			return text;
		}
		String t = text;
		while (t.length() > 1 && fm.stringWidth(t + "..") > width)
		{
			t = t.substring(0, t.length() - 1);
		}
		return t.trim() + "..";
	}

	private Point mousePoint()
	{
		return client == null ? null : client.getMouseCanvasPosition();
	}

	private void hover(Entry e, Point mouse, Rectangle at, Rectangle area)
	{
		if (mouse != null && at != null && e.tooltip != null && !e.tooltip.isEmpty()
			&& new Rectangle(at.x + area.x, at.y + area.y, area.width, area.height).contains(mouse.getX(), mouse.getY()))
		{
			tooltips.add(new Tooltip(e.tooltip));
		}
	}
}
