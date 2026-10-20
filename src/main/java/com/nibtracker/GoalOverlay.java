package com.nibtracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;

/**
 * The goals the player chose to show on the game screen (right-click in the Goals panel): a small icon each, with a
 * short number beside it and a bar under the icon coloured by progress. Size from the plugin settings; Alt-drag to move.
 * The entries are worked out once per change by GoalTracker; this only draws them.
 */
@Singleton
class GoalOverlay extends Overlay
{
	/** One goal on screen: its icon, a few characters of text ("73/80", "34 left") and progress 0..1 (-1: none). */
	static final class Entry
	{
		final BufferedImage icon;
		final String text;
		final double progress;
		final boolean done;

		Entry(BufferedImage icon, String text, double progress, boolean done)
		{
			this.icon = icon;
			this.text = text;
			this.progress = progress;
			this.done = done;
		}
	}

	private static final int GAP = 3;
	private static final int BAR = 3;
	private static final Color DONE = new Color(0x3F, 0xC2, 0x5F);
	private static final Color TRACK = new Color(0, 0, 0, 160);

	private final NibTrackerConfig config;
	private volatile List<Entry> entries = Collections.emptyList();

	@Inject
	GoalOverlay(NibTrackerPlugin plugin, NibTrackerConfig config)
	{
		super(plugin);
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
		g.setFont(size >= 30 ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont());
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		FontMetrics fm = g.getFontMetrics();
		int width = 0, y = 0;
		for (Entry e : list)
		{
			int textW = fm.stringWidth(e.text);
			int rowW = size + (textW > 0 ? GAP + textW + GAP : 0);
			g.setColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
			g.fillRect(0, y, rowW, size);
			if (e.icon != null)
			{
				double s = Math.min((size - 4.0) / e.icon.getWidth(), (size - 4.0 - BAR) / e.icon.getHeight());
				int w = (int) Math.round(e.icon.getWidth() * s), h = (int) Math.round(e.icon.getHeight() * s);
				g.drawImage(e.icon, (size - w) / 2, y + (size - BAR - h) / 2, w, h, null);
			}
			if (e.progress >= 0 || e.done)
			{
				g.setColor(TRACK);
				g.fillRect(2, y + size - BAR - 1, size - 4, BAR);
				g.setColor(e.done ? DONE : GoalsPanel.colour(e.progress));
				g.fillRect(2, y + size - BAR - 1, (int) Math.round((size - 4) * (e.done ? 1 : Math.min(1, e.progress))), BAR);
			}
			if (textW > 0)
			{
				int ty = y + (size + fm.getAscent() - fm.getDescent()) / 2;
				g.setColor(Color.BLACK);
				g.drawString(e.text, size + GAP + 1, ty + 1);
				g.setColor(e.done ? DONE : Color.WHITE);
				g.drawString(e.text, size + GAP, ty);
			}
			width = Math.max(width, rowW);
			y += size + GAP;
		}
		return new Dimension(width, y - GAP);
	}
}
