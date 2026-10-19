package com.nibtracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.ProgressBar;

/** The Goals side panel: the character's goals from Nib Tracker, with progress. Swing thread only. */
class GoalsPanel extends PluginPanel
{
	/** One row to show. progress is 0..1, or -1 for a yes/no goal. */
	static final class Row
	{
		final String title;
		final String detail;
		final double progress;
		final boolean done;
		final int points;

		Row(String title, String detail, double progress, boolean done, int points)
		{
			this.title = title;
			this.detail = detail;
			this.progress = progress;
			this.done = done;
			this.points = points;
		}
	}

	private static final Color DONE = new Color(0x2E, 0x8B, 0x3E);
	private static final Color GOING = new Color(0xC8, 0x8A, 0x1E);

	private final JLabel heading = new JLabel("Goals");
	private final JLabel status = new JLabel();
	private final JPanel list = new JPanel(new GridLayout(0, 1, 0, 6));

	GoalsPanel()
	{
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout(0, 8));
		heading.setFont(FontManager.getRunescapeBoldFont());
		heading.setForeground(Color.WHITE);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel top = new JPanel(new BorderLayout(0, 4));
		top.add(heading, BorderLayout.NORTH);
		top.add(status, BorderLayout.CENTER);
		add(top, BorderLayout.NORTH);
		add(list, BorderLayout.CENTER);
		show("Goals", "Log in with Sync to Nib Tracker on to see your goals here.", null);
	}

	void show(String title, String message, List<Row> rows)
	{
		heading.setText(title);
		status.setText(message == null ? "" : "<html>" + escape(message) + "</html>");
		status.setVisible(message != null);
		list.removeAll();
		if (rows != null)
		{
			for (Row r : rows)
			{
				list.add(row(r));
			}
		}
		list.revalidate();
		list.repaint();
	}

	private static JPanel row(Row r)
	{
		JPanel p = new JPanel(new BorderLayout(0, 4));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		JLabel name = new JLabel("<html>" + escape(r.title) + "</html>");
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(r.done ? DONE.brighter() : Color.WHITE);
		p.add(name, BorderLayout.NORTH);

		ProgressBar bar = new ProgressBar();
		bar.setPreferredSize(new Dimension(0, 16));
		bar.setMaximumValue(1000);
		bar.setValue(r.done ? 1000 : (int) (Math.max(0, r.progress) * 1000));
		bar.setForeground(r.done ? DONE : GOING);
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bar.setLeftLabel(r.done ? "Done" : r.detail);
		bar.setRightLabel(r.done ? (r.points > 0 ? "+" + r.points + " pts" : "") : r.progress >= 0 ? Math.round(r.progress * 100) + "%" : "");
		bar.setCenterLabel("");
		p.add(bar, BorderLayout.CENTER);
		return p;
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
