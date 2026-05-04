package com.gauntlethistory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

class HtmlExporter
{
	private static final DateTimeFormatter DATE_FMT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	static void export(List<GauntletSession> sessions, File dir) throws IOException
	{
		File out = new File(dir, "export.html");
		try (FileWriter w = new FileWriter(out))
		{
			w.write(build(sessions));
		}
	}

	private static String build(List<GauntletSession> sessions)
	{
		long kills = sessions.stream().filter(s -> s.killedBoss).count();
		long bossDeaths = sessions.stream().filter(s -> s.diedInBoss).count();
		long prepDeaths = sessions.stream().filter(s -> s.diedInPrep).count();
		int latestKc = sessions.stream()
			.mapToInt(s -> s.killCount)
			.filter(kc -> kc > 0)
			.max()
			.orElse(-1);
		String killRate = sessions.isEmpty() ? "—"
			: String.format("%.1f%%", 100.0 * kills / sessions.size());

		StringBuilder sb = new StringBuilder();
		sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
		sb.append("<meta charset=\"UTF-8\">\n");
		sb.append("<title>Gauntlet History</title>\n");
		sb.append("<style>\n").append(css()).append("</style>\n");
		sb.append("</head>\n<body>\n");
		sb.append("<h1>Gauntlet History</h1>\n");

		// Summary cards
		sb.append("<div class=\"summary\">\n");
		stat(sb, "Total Sessions", String.valueOf(sessions.size()));
		stat(sb, "Kills", String.valueOf(kills));
		stat(sb, "Boss Deaths", String.valueOf(bossDeaths));
		stat(sb, "Prep Deaths", String.valueOf(prepDeaths));
		stat(sb, "Kill Rate", killRate);
		if (latestKc > 0)
		{
			stat(sb, "Highest KC", String.valueOf(latestKc));
		}
		sb.append("</div>\n");

		if (sessions.isEmpty())
		{
			sb.append("<p class=\"empty\">No sessions recorded yet.</p>\n");
		}
		else
		{
			sb.append("<table>\n<thead><tr>");
			for (String h : new String[]{"#", "Date", "Type", "Result", "KC", "Fight Time", "Loot", "Performance"})
			{
				sb.append("<th>").append(h).append("</th>");
			}
			sb.append("</tr></thead>\n<tbody>\n");

			int idx = sessions.size();
			for (GauntletSession s : sessions)
			{
				sb.append("<tr>");
				td(sb, String.valueOf(idx--));
				td(sb, s.startTime != null ? DATE_FMT.format(s.startTime) : "—");

				if (s.corrupted)
				{
					sb.append("<td><span class=\"corrupted\">Corrupted</span></td>");
				}
				else
				{
					sb.append("<td><span class=\"normal\">Regular</span></td>");
				}

				String result;
				String cls;
				if (s.killedBoss)
				{
					result = "Kill";
					cls = "kill";
				}
				else if (s.diedInBoss)
				{
					result = "Boss Death";
					cls = "death";
				}
				else if (s.diedInPrep)
				{
					result = "Prep Death";
					cls = "death";
				}
				else
				{
					result = "Incomplete";
					cls = "incomplete";
				}
				sb.append("<td><span class=\"").append(cls).append("\">").append(result).append("</span></td>");

				td(sb, s.killCount > 0 ? String.valueOf(s.killCount) : "—");

				String fightTime = "—";
				if (s.bossStartTime != null && s.endTime != null)
				{
					Duration d = Duration.between(s.bossStartTime, s.endTime);
					fightTime = String.format("%d:%02d", d.toMinutes(), d.getSeconds() % 60);
				}
				td(sb, fightTime);

				sb.append("<td class=\"loot\">");
				if (s.loot.isEmpty())
				{
					sb.append("—");
				}
				else
				{
					for (GauntletSession.LootItem item : s.loot)
					{
						sb.append(escape(item.quantity > 1 ? item.quantity + "x " + item.name : item.name))
							.append("<br>");
					}
				}
				sb.append("</td>");

				sb.append("<td class=\"perf\">");
				if (s.perf == null)
				{
					sb.append("—");
				}
				else
				{
					perfRow(sb, s.perf);
				}
				sb.append("</td>");

				sb.append("</tr>\n");
			}
			sb.append("</tbody>\n</table>\n");
		}

		sb.append("<p class=\"footer\">Exported ").append(DATE_FMT.format(Instant.now())).append("</p>\n");
		sb.append("</body>\n</html>");
		return sb.toString();
	}

	private static void perfRow(StringBuilder sb, PerformanceData p)
	{
		int used = p.totalTicks - p.lostTicks;
		String usedPct = p.totalTicks > 0
			? String.format("%.1f%%", 100f * used / p.totalTicks) : "—";
		float dpsTaken = p.totalTicks > 0 ? p.damageTaken / (p.totalTicks * 0.6f) : 0;
		float dpsGiven = p.totalTicks > 0 ? p.damageGiven / (p.totalTicks * 0.6f) : 0;

		sb.append("<table class=\"perf-inner\">");
		perfLine(sb, "Total ticks", p.totalTicks);
		perfLine(sb, "Lost ticks", p.lostTicks);
		sb.append("<tr><td>Used ticks</td><td>").append(escape(usedPct)).append("</td></tr>");
		perfLine(sb, "Player attacks", p.playerAttacks);
		perfLine(sb, "Wrong off pray", p.wrongOffPray);
		perfLine(sb, "Wrong att style", p.wrongAttackStyle);
		perfLine(sb, "Hunllef attacks", p.hunllefAttacks);
		perfLine(sb, "Wrong def pray", p.wrongDefPray);
		perfLine(sb, "Hunllef stomps", p.hunllefStomps);
		perfLine(sb, "Tornado hits", p.tornadoHits);
		perfLine(sb, "Floor tile hits", p.floorTileHits);
		perfLine(sb, "Damage taken", p.damageTaken);
		sb.append("<tr><td>DPS taken</td><td>").append(String.format("%.3f", dpsTaken)).append("</td></tr>");
		sb.append("<tr><td>DPS given</td><td>").append(String.format("%.3f", dpsGiven)).append("</td></tr>");
		sb.append("</table>");
	}

	private static void perfLine(StringBuilder sb, String label, int value)
	{
		sb.append("<tr><td>").append(label).append("</td><td>").append(value).append("</td></tr>");
	}

	private static void stat(StringBuilder sb, String label, String value)
	{
		sb.append("<div class=\"stat-card\"><div class=\"stat-label\">")
			.append(label)
			.append("</div><div class=\"stat-value\">")
			.append(value)
			.append("</div></div>\n");
	}

	private static void td(StringBuilder sb, String text)
	{
		sb.append("<td>").append(escape(text)).append("</td>");
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String css()
	{
		return "* { box-sizing: border-box; margin: 0; padding: 0; }\n"
			+ "body { background: #1a1a1a; color: #c8c8c8; font-family: 'Segoe UI', Arial, sans-serif; padding: 24px; }\n"
			+ "h1 { color: #c8a04a; font-size: 22px; margin-bottom: 20px; }\n"
			+ ".summary { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 24px; }\n"
			+ ".stat-card { background: #252525; border: 1px solid #3a3a3a; border-radius: 4px; padding: 10px 18px; min-width: 110px; }\n"
			+ ".stat-label { font-size: 10px; color: #888; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 4px; }\n"
			+ ".stat-value { font-size: 22px; color: #c8a04a; font-weight: bold; }\n"
			+ "table { width: 100%; border-collapse: collapse; font-size: 13px; }\n"
			+ "th { background: #252525; color: #c8a04a; padding: 8px 10px; text-align: left; border-bottom: 2px solid #3a3a3a; white-space: nowrap; }\n"
			+ "tr:nth-child(even) { background: #1e1e1e; }\n"
			+ "tr:nth-child(odd) { background: #1a1a1a; }\n"
			+ "td { padding: 7px 10px; border-bottom: 1px solid #282828; vertical-align: top; }\n"
			+ ".kill { color: #5af542; font-weight: bold; }\n"
			+ ".death { color: #f55142; font-weight: bold; }\n"
			+ ".incomplete { color: #888; }\n"
			+ ".corrupted { color: #c06af5; }\n"
			+ ".normal { color: #5ab8f5; }\n"
			+ ".loot { font-size: 12px; line-height: 1.5; }\n"
			+ ".perf { font-size: 11px; color: #aaa; }\n"
			+ ".perf-inner { border-collapse: collapse; font-size: 11px; width: 100%; }\n"
			+ ".perf-inner td { padding: 1px 4px; border: none; color: #aaa; background: transparent; }\n"
			+ ".perf-inner td:last-child { text-align: right; color: #c8c8c8; }\n"
			+ ".empty { color: #888; padding: 20px 0; }\n"
			+ ".footer { color: #555; font-size: 11px; margin-top: 16px; }\n";
	}
}
