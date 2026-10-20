package com.nibtracker;

import java.awt.Color;
import java.awt.Dimension;
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
 * What the player chose to show on the game screen (right-click in the Goals panel): goals, daily tasks due, farming. Each
 * is a small box with an icon and a few characters of text, and a bar along the bottom of the box coloured by progress.
 * Hovering over one shows the details (what's left, the task, how to get there). Size from the plugin settings; Alt-drag
 * to move. The entries are worked out once per change by GoalTracker; this only draws them.
 */
@Singleton
class GoalOverlay extends Overlay
{
	/** One box: its icon, a few characters of text, progress 0..1 (-1: no bar), and its hover text. */
	static final class Entry
	{
		final BufferedImage icon;
		final String text;
		final double progress;
		final boolean done;
		final String tooltip;

		Entry(BufferedImage icon, String text, double progress, boolean done, String tooltip)
		{
			this.icon = icon;
			this.text = text;
			this.progress = progress;
			this.done = done;
			this.tooltip = tooltip;
		}
	}

	private static final int GAP = 3;
	private static final int PAD = 3;
	private static final Color DONE = new Color(0x3F, 0xC2, 0x5F);
	private static final Color TRACK = new Color(0, 0, 0, 150);

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
		int size = config.overlaySize().px;
		int bar = Math.max(3, size / 7);
		g.setFont(size >= 30 ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont());
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		FontMetrics fm = g.getFontMetrics();
		Point mouse = client == null ? null : client.getMouseCanvasPosition();
		Rectangle at = getBounds();
		int width = 0, y = 0;
		for (Entry e : list)
		{
			int textW = fm.stringWidth(e.text);
			int rowW = size + (textW > 0 ? GAP + textW + PAD : 0);
			int inner = size - bar - 2; // the box's height above the bar
			g.setColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
			g.fillRect(0, y, rowW, size);
			if (e.icon != null)
			{
				double s = Math.min((inner - 2.0) / e.icon.getWidth(), (inner - 2.0) / e.icon.getHeight());
				int w = (int) Math.round(e.icon.getWidth() * s), h = (int) Math.round(e.icon.getHeight() * s);
				g.drawImage(e.icon, (size - w) / 2, y + 1 + (inner - h) / 2, w, h, null);
			}
			if (textW > 0)
			{
				int ty = y + 1 + (inner + fm.getAscent() - fm.getDescent()) / 2;
				g.setColor(Color.BLACK);
				g.drawString(e.text, size + GAP + 1, ty + 1);
				g.setColor(e.done ? DONE : Color.WHITE);
				g.drawString(e.text, size + GAP, ty);
			}
			// The bar runs along the bottom of the whole box, under the icon and the text.
			if (e.progress >= 0 || e.done)
			{
				int bx = 2, by = y + size - bar - 1, bw = rowW - 4;
				g.setColor(TRACK);
				g.fillRect(bx, by, bw, bar);
				g.setColor(e.done ? DONE : GoalsPanel.colour(e.progress));
				g.fillRect(bx, by, (int) Math.round(bw * (e.done ? 1 : Math.min(1, e.progress))), bar);
			}
			if (mouse != null && at != null && e.tooltip != null && !e.tooltip.isEmpty()
				&& new Rectangle(at.x, at.y + y, rowW, size).contains(mouse.getX(), mouse.getY()))
			{
				tooltips.add(new Tooltip(e.tooltip));
			}
			width = Math.max(width, rowW);
			y += size + GAP;
		}
		return new Dimension(width, y - GAP);
	}
}
