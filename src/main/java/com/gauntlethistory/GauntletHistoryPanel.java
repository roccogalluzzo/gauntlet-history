package com.gauntlethistory;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

class GauntletHistoryPanel extends PluginPanel
{
	private static final DateTimeFormatter DATE_FMT =
		DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private static final Color KILL_COLOR = new Color(0x5AF542);
	private static final Color DEATH_COLOR = new Color(0xF55142);
	private static final Color GOLD_COLOR = new Color(0xC8A04A);
	private static final Color CORRUPTED_COLOR = new Color(0xC06AF5);
	private static final Color NORMAL_COLOR = new Color(0x5AB8F5);

	private final GauntletHistoryPlugin plugin;
	private final JLabel statsLabel;
	private final JPanel sessionListPanel;

	GauntletHistoryPanel(GauntletHistoryPlugin plugin)
	{
		super(false);
		this.plugin = plugin;

		setLayout(new BorderLayout(0, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		// Header
		JLabel title = new JLabel("Gauntlet History");
		title.setForeground(GOLD_COLOR);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		title.setHorizontalAlignment(SwingConstants.CENTER);
		add(title, BorderLayout.NORTH);

		// Stats
		statsLabel = new JLabel();
		statsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statsLabel.setFont(statsLabel.getFont().deriveFont(11f));
		statsLabel.setHorizontalAlignment(SwingConstants.CENTER);

		// Session list
		sessionListPanel = new JPanel();
		sessionListPanel.setLayout(new BoxLayout(sessionListPanel, BoxLayout.Y_AXIS));
		sessionListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scrollPane = new JScrollPane(sessionListPanel);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setPreferredSize(new Dimension(0, 400));

		JPanel centerPanel = new JPanel(new BorderLayout(0, 6));
		centerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		centerPanel.add(statsLabel, BorderLayout.NORTH);
		centerPanel.add(scrollPane, BorderLayout.CENTER);
		add(centerPanel, BorderLayout.CENTER);

		// Export button
		JButton exportButton = new JButton("Export to HTML");
		exportButton.setBackground(new Color(0x3A3A3A));
		exportButton.setForeground(GOLD_COLOR);
		exportButton.setFocusPainted(false);
		exportButton.addActionListener(e -> onExportClicked());
		add(exportButton, BorderLayout.SOUTH);
	}

	void refresh()
	{
		List<GauntletSession> sessions = plugin.sessions;

		long kills = sessions.stream().filter(s -> s.killedBoss).count();
		long deaths = sessions.stream().filter(s -> s.diedInBoss || s.diedInPrep).count();
		int latestKc = sessions.stream()
			.mapToInt(s -> s.killCount)
			.filter(kc -> kc > 0)
			.max()
			.orElse(-1);
		String kcText = latestKc > 0 ? " | KC: " + latestKc : "";
		statsLabel.setText(String.format("Sessions: %d | Kills: %d | Deaths: %d%s",
			sessions.size(), kills, deaths, kcText));

		sessionListPanel.removeAll();

		// Column headers
		JPanel header = rowPanel(true);
		header.add(colLabel("Date", 90, true));
		header.add(colLabel("Type", 70, true));
		header.add(colLabel("Result", 80, true));
		header.add(colLabel("KC", 30, true));
		sessionListPanel.add(header);

		int shown = Math.min(sessions.size(), 50);
		for (int i = 0; i < shown; i++)
		{
			GauntletSession s = sessions.get(i);
			JPanel row = rowPanel(i % 2 == 0);

			String dateStr = s.startTime != null ? DATE_FMT.format(s.startTime) : "—";
			row.add(colLabel(dateStr, 90, false));

			JLabel typeLabel = colLabel(s.corrupted ? "Corrupt" : "Regular", 70, false);
			typeLabel.setForeground(s.corrupted ? CORRUPTED_COLOR : NORMAL_COLOR);
			row.add(typeLabel);

			String result;
			Color resultColor;
			if (s.killedBoss)
			{
				result = "Kill";
				resultColor = KILL_COLOR;
			}
			else if (s.diedInBoss)
			{
				result = "Boss ☠";
				resultColor = DEATH_COLOR;
			}
			else if (s.diedInPrep)
			{
				result = "Prep ☠";
				resultColor = DEATH_COLOR;
			}
			else
			{
				result = "Left";
				resultColor = ColorScheme.LIGHT_GRAY_COLOR;
			}
			JLabel resultLabel = colLabel(result, 80, false);
			resultLabel.setForeground(resultColor);
			row.add(resultLabel);

			row.add(colLabel(s.killCount > 0 ? String.valueOf(s.killCount) : "—", 30, false));

			sessionListPanel.add(row);
		}

		sessionListPanel.add(Box.createVerticalGlue());
		sessionListPanel.revalidate();
		sessionListPanel.repaint();
	}

	private JPanel rowPanel(boolean evenRow)
	{
		JPanel row = new JPanel(new GridLayout(1, 4));
		row.setBackground(evenRow ? new Color(0x1E1E1E) : new Color(0x252525));
		row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		return row;
	}

	private JLabel colLabel(String text, int width, boolean header)
	{
		JLabel label = new JLabel(text);
		label.setFont(label.getFont().deriveFont(header ? Font.BOLD : Font.PLAIN, 11f));
		label.setForeground(header ? GOLD_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		label.setPreferredSize(new Dimension(width, 18));
		return label;
	}

	private void onExportClicked()
	{
		try
		{
			plugin.exportHtml();
			JOptionPane.showMessageDialog(this,
				"Exported to:\n" + GauntletHistoryPlugin.HISTORY_DIR + "\\export.html",
				"Export Complete", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (IOException e)
		{
			JOptionPane.showMessageDialog(this,
				"Export failed: " + e.getMessage(),
				"Export Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	static BufferedImage buildIcon()
	{
		BufferedImage img = new BufferedImage(25, 25, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xC8A04A));
		g.fillOval(2, 2, 21, 21);
		g.setColor(new Color(0x1A1A1A));
		g.setFont(new Font("SansSerif", Font.BOLD, 13));
		g.drawString("G", 8, 17);
		g.dispose();
		return img;
	}
}
