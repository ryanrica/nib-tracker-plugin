package com.nibtracker;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;

/**
 * Proof pictures for bingo drop tiles. When a drop or collection log slot matches an item on one of the player's live
 * drop tiles (the server lists them in settings.screenshotItems), the next game frame is captured. Once the event
 * itself has reached the server, the picture is uploaded against it; the server attaches it to the tile submission.
 * Pictures that no tile wants are thrown away by the server.
 */
@Slf4j
@Singleton
class ScreenshotService
{
	private static final int MAX_WIDTH = 1280;
	private static final int MAX_HELD = 5;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private NibTrackerApi api;

	private final Map<String, byte[]> ready = new ConcurrentHashMap<>();
	private final Set<String> capturing = ConcurrentHashMap.newKeySet();
	private final Set<String> delivered = ConcurrentHashMap.newKeySet();
	private volatile String key;

	/** Client thread. Captures the next frame for this event. */
	void capture(String eventId, String pluginKey)
	{
		if (eventId == null || capturing.size() + ready.size() >= MAX_HELD)
		{
			return;
		}
		key = pluginKey;
		capturing.add(eventId);
		drawManager.requestNextFrameListener(img -> executor.execute(() -> {
			byte[] jpg = encode(img);
			capturing.remove(eventId);
			if (jpg == null)
			{
				delivered.remove(eventId);
				return;
			}
			if (delivered.remove(eventId))
			{
				api.uploadScreenshot(key, eventId, jpg);
			}
			else
			{
				ready.put(eventId, jpg);
			}
		}));
	}

	/** Any thread. These events reached the server; upload their pictures (now, or once they're encoded). */
	void eventsDelivered(Collection<String> eventIds)
	{
		for (String id : eventIds)
		{
			byte[] jpg = ready.remove(id);
			if (jpg != null)
			{
				api.uploadScreenshot(key, id, jpg);
			}
			else if (capturing.contains(id))
			{
				delivered.add(id);
			}
		}
	}

	void reset()
	{
		ready.clear();
		capturing.clear();
		delivered.clear();
	}

	private static byte[] encode(Image img)
	{
		try
		{
			BufferedImage src = ImageUtil.bufferedImageFromImage(img);
			int w = src.getWidth(), h = src.getHeight();
			if (w > MAX_WIDTH)
			{
				h = h * MAX_WIDTH / w;
				w = MAX_WIDTH;
			}
			BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = rgb.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, w, h, null);
			g.dispose();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(rgb, "jpg", out);
			return out.toByteArray();
		}
		catch (Exception e)
		{
			log.debug("Nib Tracker couldn't encode a screenshot", e);
			return null;
		}
	}
}
