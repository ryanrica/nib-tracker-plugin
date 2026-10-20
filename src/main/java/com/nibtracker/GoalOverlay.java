package com.nibtracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
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

/**
 * What the player chose to show on the game screen (right-click in the Goals panel): goals, daily tasks due, farming, or
 * one kind of patch. Detailed style is a card with, for each entry, its icon, name, value, a progress bar and one line of
 * detail (the next teleport, the task); Compact is a small box per entry. Hover over an entry for everything (what's left,
 * the whole run, how to get there). Size from the plugin settings; Alt-drag to move. GoalTracker works the entries out
 * once per change; this only draws them.
 */
@Singleton
class GoalOverlay extends Overlay
{
	/** One entry: icon, name, value ("73/80", "3 ready"), a line of detail, progress 0..1 (-1: no bar), and hover text. */
	static final class Entry
	{
		final BufferedImage icon;
		final String title;
		final String value;
		final String detail;
		final double progress;
		/* Something to act on now (patches ready, tasks due): the value shows in green. */
		final boolean ready;
		final String tooltip;

		Entry(BufferedImage icon, String title, String value, String detail, double progress, boolean ready, String tooltip)
		{
			this.icon = icon;
			this.title = title;
			this.value = value;
			this.detail = detail == null ? "" : detail;
			this.progress = progress;
			this.ready = ready;
			this.tooltip = tooltip;
		}
	}

	private static final Color READY = new Color(0x3F, 0xC2, 0x5F);
	private static final Color WAITING = new Color(0xFF, 0x98, 0x1F);
	private static final Color TITLE = new Color(0xF2, 0xEF, 0xE6);
	private static final Color DETAIL = new Color(0xB4, 0xB0, 0xA4);
	private static final Color HEADING = new Color(0xD8, 0xB6, 0x6A);
	private static final Color TRACK = new Color(0, 0, 0, 150);
	private static final Color EDGE = new Color(255, 255, 255, 28);
	private static final Color DIVIDER = new Color(255, 255, 255, 18);

	private final Client client;
	private final TooltipManager tooltips;
	private final NibTrackerConfig config;
	private volatile List<Entry> entries = Collections.emptyList();

	@Inject
	GoalOverlay(NibTrackerPlugin plugin, Client client, TooltipManager tooltips, NibTrackerConfig config)
	{
		super(plugin);
		this.client = client;
		this.tooltips = tooltips;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	void set(List<Entry> entries)
	{
		this.entries = entries;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		List<Entry> list = entries;
		if (list.isEmpty())
		{
			return null;
		}
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		int size = config.overlaySize().px;
		return config.overlayStyle() == NibTrackerConfig.OverlayStyle.COMPACT ? compact(g, list, size) : detailed(g, list, size);
	}

	/** The card: a small heading, then each entry with icon, name and value, a bar, and a line of detail. */
	private Dimension detailed(Graphics2D g, List<Entry> list, int size)
	{
		Font titleFont = size >= 26 ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont();
		Font smallFont = FontManager.getRunescapeSmallFont();
		FontMetrics tm = g.getFontMetrics(titleFont), sm = g.getFontMetrics(smallFont);
		int pad = 6, icon = size - 2, gap = 6, bar = Math.max(3, size / 6);
		int width = Math.round(size * 7.6f);
		int textX = pad + icon + gap, textW = width - pad - textX;

		// Lay out first, so the background can be drawn behind everything at the right height.
		int headH = sm.getAscent() + 4;
		int[] tops = new int[list.size()];
		int[] heights = new int[list.size()];
		int y = pad + headH + 2;
		for (int i = 0; i < list.size(); i++)
		{
			Entry e = list.get(i);
			int h = tm.getAscent() + 3 + ((e.progress >= 0 || e.ready) ? bar + 3 : 0) + (e.detail.isEmpty() ? 0 : sm.getAscent() + 2);
			tops[i] = y;
			heights[i] = Math.max(icon, h);
			y += heights[i] + (i < list.size() - 1 ? 7 : 0);
		}
		int height = y + pad;

		g.setColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
		g.fillRoundRect(0, 0, width, height, 6, 6);
		g.setColor(EDGE);
		g.drawRoundRect(0, 0, width - 1, height - 1, 6, 6);
		g.setFont(smallFont);
		g.setColor(HEADING);
		g.drawString("NIB TRACKER", pad, pad + sm.getAscent());

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
			shadowed(g, fit(tm, e.title, textW - valueW - 6), textX, ty, TITLE);
			shadowed(g, value, width - pad - valueW, ty, valueColour(e));
			int cy = ty + 3;
			if (e.progress >= 0 || e.ready)
			{
				g.setColor(TRACK);
				g.fillRect(textX, cy, textW, bar);
				g.setColor(valueColour(e));
				g.fillRect(textX, cy, (int) Math.round(textW * (e.progress < 0 ? 1 : Math.min(1, e.progress))), bar);
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

	/** Small boxes: icon and value, with the bar along the bottom of the box. */
	private Dimension compact(Graphics2D g, List<Entry> list, int size)
	{
		int bar = Math.max(3, size / 7);
		g.setFont(size >= 30 ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont());
		FontMetrics fm = g.getFontMetrics();
		Point mouse = mousePoint();
		Rectangle at = getBounds();
		int width = 0, y = 0;
		for (Entry e : list)
		{
			int textW = fm.stringWidth(e.value);
			int rowW = size + (textW > 0 ? 3 + textW + 4 : 0);
			int inner = size - bar - 2;
			g.setColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
			g.fillRect(0, y, rowW, size);
			drawIcon(g, e.icon, (size - inner) / 2, y + 1, inner);
			if (textW > 0)
			{
				shadowed(g, e.value, size + 3, y + 1 + (inner + fm.getAscent() - fm.getDescent()) / 2, e.ready ? READY : Color.WHITE);
			}
			if (e.progress >= 0 || e.ready)
			{
				g.setColor(TRACK);
				g.fillRect(2, y + size - bar - 1, rowW - 4, bar);
				g.setColor(valueColour(e));
				g.fillRect(2, y + size - bar - 1, (int) Math.round((rowW - 4) * (e.progress < 0 ? 1 : Math.min(1, e.progress))), bar);
			}
			hover(e, mouse, at, new Rectangle(0, y, rowW, size));
			width = Math.max(width, rowW);
			y += size + 3;
		}
		return new Dimension(width, y - 3);
	}

	private static Color valueColour(Entry e)
	{
		return e.ready ? READY : e.progress >= 0 ? GoalsPanel.colour(e.progress).brighter() : WAITING;
	}

	private static void drawIcon(Graphics2D g, BufferedImage img, int x, int y, int box)
	{
		if (img == null || img.getWidth() <= 0)
		{
			return;
		}
		double s = Math.min((double) box / img.getWidth(), (double) box / img.getHeight());
		int w = (int) Math.round(img.getWidth() * s), h = (int) Math.round(img.getHeight() * s);
		g.drawImage(img, x + (box - w) / 2, y + (box - h) / 2, w, h, null);
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
