package com.gauntlethistory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

class HtmlExporter
{
	private static final DateTimeFormatter DATE_FMT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private static final int MAX_CHART_POINTS = 50;
	private static final int PAGE_SIZE = 50;

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
		List<GauntletSession> regular = sessions.stream()
			.filter(s -> !s.corrupted).collect(Collectors.toList());
		List<GauntletSession> corrupted = sessions.stream()
			.filter(s -> s.corrupted).collect(Collectors.toList());
		boolean hasRegular = !regular.isEmpty();
		boolean hasCorrupted = !corrupted.isEmpty();
		boolean showTabs = hasRegular && hasCorrupted;

		StringBuilder sb = new StringBuilder();
		sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
		sb.append("<meta charset=\"UTF-8\">\n");
		sb.append("<title>Gauntlet History</title>\n");
		sb.append("<style>\n").append(css()).append("</style>\n");
		sb.append("<script>\n").append(js()).append("</script>\n");
		sb.append("</head>\n<body>\n");
		sb.append("<h1>Gauntlet History</h1>\n");

		if (sessions.isEmpty())
		{
			sb.append("<p class=\"empty\">No sessions recorded yet.</p>\n");
		}
		else
		{
			if (showTabs)
			{
				sb.append("<div class=\"tabs\">\n");
				sb.append("<button id=\"tab-all\" class=\"tab active\" onclick=\"showTab('all')\">All</button>\n");
				sb.append("<button id=\"tab-regular\" class=\"tab\" onclick=\"showTab('regular')\">Regular</button>\n");
				sb.append("<button id=\"tab-corrupted\" class=\"tab\" onclick=\"showTab('corrupted')\">Corrupted</button>\n");
				sb.append("</div>\n");
			}
			if (hasRegular)
			{
				sb.append("<div id=\"section-regular\">\n");
				section(sb, "Regular Gauntlet", "regular", regular);
				sb.append("</div>\n");
			}
			if (hasCorrupted)
			{
				sb.append("<div id=\"section-corrupted\">\n");
				section(sb, "Corrupted Gauntlet", "corrupted", corrupted);
				sb.append("</div>\n");
			}
		}

		sb.append("<p class=\"footer\">Exported ").append(DATE_FMT.format(Instant.now())).append("</p>\n");
		sb.append("</body>\n</html>");
		return sb.toString();
	}

	private static void section(StringBuilder sb, String title, String prefix, List<GauntletSession> sessions)
	{
		long kills = sessions.stream().filter(s -> s.killedBoss).count();
		long bossDeaths = sessions.stream().filter(s -> s.diedInBoss).count();
		long prepDeaths = sessions.stream().filter(s -> s.diedInPrep).count();
		int latestKc = sessions.stream().mapToInt(s -> s.killCount).filter(kc -> kc > 0).max().orElse(-1);
		String killRate = String.format("%.1f%%", 100.0 * kills / sessions.size());

		sb.append("<h2>").append(title).append("</h2>\n");
		sb.append("<div class=\"summary\">\n");
		stat(sb, "Total Runs", String.valueOf(sessions.size()));
		stat(sb, "Kills", String.valueOf(kills));
		stat(sb, "Boss Deaths", String.valueOf(bossDeaths));
		stat(sb, "Prep Deaths", String.valueOf(prepDeaths));
		stat(sb, "Kill Rate", killRate);
		if (latestKc > 0)
		{
			stat(sb, "Highest KC", String.valueOf(latestKc));
		}
		sb.append("</div>\n");

		// Charts before table
		charts(sb, prefix, sessions);
		table(sb, prefix, sessions);
	}

	// -------------------------------------------------------------------------
	// Charts
	// -------------------------------------------------------------------------

	private static void charts(StringBuilder sb, String prefix, List<GauntletSession> sessions)
	{
		List<GauntletSession> chrono = new ArrayList<>(sessions);
		Collections.reverse(chrono);

		List<GauntletSession> withPerf = chrono.stream()
			.filter(s -> s.perf != null).collect(Collectors.toList());
		if (withPerf.size() > MAX_CHART_POINTS)
		{
			withPerf = withPerf.subList(withPerf.size() - MAX_CHART_POINTS, withPerf.size());
		}

		List<GauntletSession> withFight = chrono.stream()
			.filter(s -> s.fightTimeMs >= 0 || (s.bossStartTime != null && s.endTime != null))
			.collect(Collectors.toList());
		if (withFight.size() > MAX_CHART_POINTS)
		{
			withFight = withFight.subList(withFight.size() - MAX_CHART_POINTS, withFight.size());
		}

		if (withPerf.isEmpty() && withFight.isEmpty())
		{
			return;
		}

		sb.append("<details class=\"charts-details\" open>\n");
		sb.append("<summary class=\"charts-summary\">Performance Charts</summary>\n");
		sb.append("<div class=\"charts-controls\">\n");
		sb.append("<button class=\"ma-toggle\" onclick=\"toggleMA()\">Hide Avg</button>\n");
		sb.append("</div>\n");
		sb.append("<div class=\"charts-grid\">\n");

		chartCanvas(sb, prefix + "-tick-eff", "Tick Efficiency (%)");
		chartCanvas(sb, prefix + "-fight-time", "Fight Time");
		chartCanvas(sb, prefix + "-damage", "Damage Taken");
		chartCanvas(sb, prefix + "-wrong-off", "Wrong Offensive Prayers");
		chartCanvas(sb, prefix + "-wrong-def", "Wrong Defensive Prayers");
		chartCanvas(sb, prefix + "-wrong-style", "Wrong Attack Style");

		sb.append("</div>\n");
		sb.append("<script>\n");

		if (!withPerf.isEmpty())
		{
			drawCall(sb, prefix + "-tick-eff", withPerf,
				s -> s.perf.totalTicks > 0
					? String.format("%.1f", 100f * (s.perf.totalTicks - s.perf.lostTicks) / s.perf.totalTicks)
					: "0",
				"'#5af542'", "0", "100", "null");
			drawCall(sb, prefix + "-damage", withPerf,
				s -> String.valueOf(s.perf.damageTaken),
				"'#f55142'", "null", "null", "null");
			drawCall(sb, prefix + "-wrong-off", withPerf,
				s -> String.valueOf(s.perf.wrongOffPray),
				"'#f5a142'", "null", "null", "null");
			drawCall(sb, prefix + "-wrong-def", withPerf,
				s -> String.valueOf(s.perf.wrongDefPray),
				"'#f5d442'", "null", "null", "null");
			drawCall(sb, prefix + "-wrong-style", withPerf,
				s -> String.valueOf(s.perf.wrongAttackStyle),
				"'#c06af5'", "null", "null", "null");
		}
		if (!withFight.isEmpty())
		{
			// Use ms precision; chart value = seconds (with decimal), label formatted as M:SS
			drawCall(sb, prefix + "-fight-time", withFight,
				s -> {
					long ms = s.fightTimeMs >= 0 ? s.fightTimeMs
						: Duration.between(s.bossStartTime, s.endTime).toMillis();
					return String.format("%.1f", ms / 1000.0);
				},
				"'#5ab8f5'", "null", "null",
				"function(v){return Math.floor(v/60)+':'+(('0'+Math.floor(v%60)).slice(-2));}");
		}

		sb.append("</script>\n");
		sb.append("</details>\n");
	}

	private static void chartCanvas(StringBuilder sb, String id, String title)
	{
		sb.append("<div class=\"chart-card\">\n");
		sb.append("<div class=\"chart-title\">").append(title).append("</div>\n");
		sb.append("<canvas id=\"").append(id).append("\" width=\"560\" height=\"140\"></canvas>\n");
		sb.append("</div>\n");
	}

	private static void drawCall(StringBuilder sb, String id, List<GauntletSession> sessions,
		Function<GauntletSession, String> value,
		String color, String yMin, String yMax, String fmt)
	{
		sb.append("drawChart('").append(id).append("',[");
		for (int i = 0; i < sessions.size(); i++)
		{
			if (i > 0)
			{
				sb.append(",");
			}
			sb.append(value.apply(sessions.get(i)));
		}
		sb.append("],").append(color).append(",").append(yMin).append(",").append(yMax)
			.append(",").append(fmt).append(");\n");
	}

	// -------------------------------------------------------------------------
	// Table
	// -------------------------------------------------------------------------

	private static void table(StringBuilder sb, String prefix, List<GauntletSession> sessions)
	{
		String tableId = "tbl-" + prefix;
		String pagId = "pag-" + prefix;

		sb.append("<table id=\"").append(tableId).append("\">\n<thead><tr>");

		String[][] cols = {
			{"#", "num"},
			{"Date", "str"},
			{"Result", "num"},
			{"KC", "num"},
			{"Prep Time", "num"},
			{"Fight Time", "num"},
			{"Total Time", "num"},
			{"Loot", "str"},
			{"Performance", null}
		};
		for (int c = 0; c < cols.length; c++)
		{
			String label = cols[c][0];
			String sortType = cols[c][1];
			if (sortType != null)
			{
				sb.append("<th class=\"sortable\" onclick=\"sortTable('")
					.append(tableId).append("',").append(c).append(",'")
					.append(sortType).append("')\">").append(label).append("</th>");
			}
			else
			{
				sb.append("<th>").append(label).append("</th>");
			}
		}
		sb.append("</tr></thead>\n<tbody>\n");

		int idx = sessions.size();
		for (GauntletSession s : sessions)
		{
			sb.append("<tr>");

			// # — data-val for numeric sort
			sb.append("<td data-val=\"").append(idx).append("\">").append(idx).append("</td>");
			idx--;

			// Date
			td(sb, s.startTime != null ? DATE_FMT.format(s.startTime) : "—");

			// Result — data-val encodes sort priority
			String result;
			String cls;
			int resultVal;
			if (s.killedBoss)
			{
				result = "Kill"; cls = "kill"; resultVal = 0;
			}
			else if (s.diedInBoss)
			{
				result = "Boss Death"; cls = "death"; resultVal = 1;
			}
			else if (s.diedInPrep)
			{
				result = "Prep Death"; cls = "death"; resultVal = 2;
			}
			else
			{
				result = "Incomplete"; cls = "incomplete"; resultVal = 3;
			}
			sb.append("<td data-val=\"").append(resultVal).append("\">")
				.append("<span class=\"").append(cls).append("\">").append(result).append("</span></td>");

			// KC
			tdNum(sb, s.killCount > 0 ? s.killCount : Long.MIN_VALUE,
				s.killCount > 0 ? String.valueOf(s.killCount) : "—");

			// Prep / Fight / Total time — prefer game-reported ms; fall back to wall-clock
			long prepMs = s.prepTimeMs >= 0 ? s.prepTimeMs
				: (s.startTime != null && s.bossStartTime != null
					? Duration.between(s.startTime, s.bossStartTime).toMillis() : Long.MIN_VALUE);
			long fightMs = s.fightTimeMs >= 0 ? s.fightTimeMs
				: (s.bossStartTime != null && s.endTime != null
					? Duration.between(s.bossStartTime, s.endTime).toMillis() : Long.MIN_VALUE);
			long totalMs = s.totalTimeMs >= 0 ? s.totalTimeMs
				: (s.startTime != null && s.endTime != null
					? Duration.between(s.startTime, s.endTime).toMillis() : Long.MIN_VALUE);

			// data-val = total milliseconds for sorting
			tdNum(sb, prepMs, formatMs(prepMs));
			tdNum(sb, fightMs, formatMs(fightMs));
			tdNum(sb, totalMs, formatMs(totalMs));

			// Loot
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

			// Performance
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

		// Pagination controls
		sb.append("<div class=\"pagination\" id=\"").append(pagId).append("\">\n");
		sb.append("<button onclick=\"changePage('").append(tableId).append("',-1)\">&#8592; Prev</button>\n");
		sb.append("<span id=\"").append(pagId).append("-info\"></span>\n");
		sb.append("<button onclick=\"changePage('").append(tableId).append("',1)\">Next &#8594;</button>\n");
		sb.append("</div>\n");
		sb.append("<script>showPage('").append(tableId).append("',1);</script>\n");
	}

	// -------------------------------------------------------------------------
	// Perf inner table
	// -------------------------------------------------------------------------

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

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	/** Format milliseconds as M:SS.s (one decimal), e.g. 2:59.4 — or "—" if ms < 0. */
	private static String formatMs(long ms)
	{
		if (ms < 0)
		{
			return "—";
		}
		long secs = ms / 1000;
		int tenths = (int) ((ms % 1000) / 100);
		return String.format("%d:%02d.%d", secs / 60, secs % 60, tenths);
	}

	private static void stat(StringBuilder sb, String label, String value)
	{
		sb.append("<div class=\"stat-card\"><div class=\"stat-label\">")
			.append(label).append("</div><div class=\"stat-value\">")
			.append(value).append("</div></div>\n");
	}

	private static void td(StringBuilder sb, String text)
	{
		sb.append("<td>").append(escape(text)).append("</td>");
	}

	/** Cell with a hidden numeric data-val for sorting; Long.MIN_VALUE → empty (sorts to bottom). */
	private static void tdNum(StringBuilder sb, long val, String display)
	{
		String dataVal = (val != Long.MIN_VALUE) ? String.valueOf(val) : "";
		sb.append("<td data-val=\"").append(dataVal).append("\">").append(escape(display)).append("</td>");
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	// -------------------------------------------------------------------------
	// JavaScript
	// -------------------------------------------------------------------------

	private static String js()
	{
		return ""
			// ---- Tab switching ----
			+ "function showTab(tab){\n"
			+ "  document.querySelectorAll('.tab').forEach(function(t){t.classList.remove('active');});\n"
			+ "  document.getElementById('tab-'+tab).classList.add('active');\n"
			+ "  var r=document.getElementById('section-regular');\n"
			+ "  var c=document.getElementById('section-corrupted');\n"
			+ "  r.style.display=(tab==='corrupted')?'none':'';\n"
			+ "  c.style.display=(tab==='regular')?'none':'';\n"
			+ "}\n"
			// ---- Chart drawing ----
			+ "var _showMA=true,_chartData={},_MA_WIN=5;\n"
			+ "function movingAvg(data,w){\n"
			+ "  return data.map(function(_,i){\n"
			+ "    var s=Math.max(0,i-w+1),sl=data.slice(s,i+1);\n"
			+ "    return sl.reduce(function(a,b){return a+b;},0)/sl.length;\n"
			+ "  });\n"
			+ "}\n"
			+ "function toggleMA(){\n"
			+ "  _showMA=!_showMA;\n"
			+ "  var lbl=_showMA?'Hide Avg':'Show Avg';\n"
			+ "  document.querySelectorAll('.ma-toggle').forEach(function(b){b.textContent=lbl;});\n"
			+ "  for(var id in _chartData){var d=_chartData[id];drawChart(id,d.data,d.color,d.yMin,d.yMax,d.fmt);}\n"
			+ "}\n"
			+ "var _tt=null;\n"
			+ "function getTooltip(){\n"
			+ "  if(!_tt){_tt=document.createElement('div');_tt.className='chart-tooltip';document.body.appendChild(_tt);}\n"
			+ "  return _tt;\n"
			+ "}\n"
			+ "function niceGridLines(lo,hi){\n"
			+ "  var range=hi-lo,rawStep=range/4;\n"
			+ "  if(rawStep===0)return[lo];\n"
			+ "  var mag=Math.pow(10,Math.floor(Math.log10(rawStep)));\n"
			+ "  var norm=rawStep/mag;\n"
			+ "  var step=norm<=1?mag:norm<=2?2*mag:norm<=5?5*mag:10*mag;\n"
			+ "  var start=Math.ceil(lo/step)*step;\n"
			+ "  var lines=[];\n"
			+ "  for(var v=start;v<=hi+step*0.001;v+=step)\n"
			+ "    lines.push(parseFloat(v.toFixed(10)));\n"
			+ "  if(lines[0]-lo>step*0.1)lines.unshift(parseFloat((start-step).toFixed(10)));\n"
			+ "  return lines;\n"
			+ "}\n"
			+ "function drawChart(id,data,color,yMin,yMax,fmt){\n"
			+ "  _chartData[id]={data:data,color:color,yMin:yMin,yMax:yMax,fmt:fmt};\n"
			+ "  var canvas=document.getElementById(id);\n"
			+ "  if(!canvas||data.length===0)return;\n"
			+ "  var W=canvas.width,H=canvas.height,ctx=canvas.getContext('2d');\n"
			+ "  var pad={top:16,right:16,bottom:30,left:52};\n"
			+ "  var w=W-pad.left-pad.right,h=H-pad.top-pad.bottom;\n"
			+ "  var lo=yMin!=null?yMin:Math.min.apply(null,data);\n"
			+ "  var hi=yMax!=null?yMax:Math.max.apply(null,data);\n"
			+ "  if(lo===hi){lo=Math.max(0,lo-1);hi=hi+1;}\n"
			+ "  var gridLines=niceGridLines(lo,hi);\n"
			+ "  lo=Math.min(lo,gridLines[0]);hi=Math.max(hi,gridLines[gridLines.length-1]);\n"
			+ "  var range=hi-lo;\n"
			+ "  function xOf(i){return pad.left+(data.length>1?i/(data.length-1):0.5)*w;}\n"
			+ "  function yOf(v){return pad.top+h-((v-lo)/range)*h;}\n"
			// background
			+ "  ctx.clearRect(0,0,W,H);ctx.fillStyle='#1e1e1e';ctx.fillRect(0,0,W,H);\n"
			// grid + y-axis labels using nice grid lines
			+ "  for(var gi=0;gi<gridLines.length;gi++){\n"
			+ "    var gv=gridLines[gi],gy=yOf(gv);\n"
			+ "    ctx.strokeStyle='#2a2a2a';ctx.lineWidth=1;\n"
			+ "    ctx.beginPath();ctx.moveTo(pad.left,gy);ctx.lineTo(pad.left+w,gy);ctx.stroke();\n"
			+ "    ctx.fillStyle='#aaa';ctx.font='bold 11px sans-serif';ctx.textAlign='right';\n"
			+ "    var lbl=fmt?fmt(gv):(Number.isInteger(gv)?gv:parseFloat(gv.toFixed(1)));\n"
			+ "    ctx.fillText(lbl,pad.left-6,gy+4);\n"
			+ "  }\n"
			// fill under line
			+ "  ctx.beginPath();ctx.moveTo(xOf(0),yOf(data[0]));\n"
			+ "  for(var i=1;i<data.length;i++)ctx.lineTo(xOf(i),yOf(data[i]));\n"
			+ "  ctx.lineTo(xOf(data.length-1),pad.top+h);ctx.lineTo(xOf(0),pad.top+h);\n"
			+ "  ctx.closePath();ctx.fillStyle=color+'33';ctx.fill();\n"
			// raw data line
			+ "  ctx.strokeStyle=color;ctx.lineWidth=1.5;ctx.lineJoin='round';ctx.globalAlpha=0.5;\n"
			+ "  ctx.beginPath();ctx.moveTo(xOf(0),yOf(data[0]));\n"
			+ "  for(var i=1;i<data.length;i++)ctx.lineTo(xOf(i),yOf(data[i]));\n"
			+ "  ctx.stroke();ctx.globalAlpha=1;\n"
			// moving average line
			+ "  if(_showMA&&data.length>=3){\n"
			+ "    var ma=movingAvg(data,Math.min(_MA_WIN,data.length));\n"
			+ "    ctx.strokeStyle=color;ctx.lineWidth=2.5;ctx.lineJoin='round';\n"
			+ "    ctx.beginPath();ctx.moveTo(xOf(0),yOf(ma[0]));\n"
			+ "    for(var i=1;i<ma.length;i++)ctx.lineTo(xOf(i),yOf(ma[i]));\n"
			+ "    ctx.stroke();\n"
			+ "  }\n"
			// dots on raw data
			+ "  for(var i=0;i<data.length;i++){\n"
			+ "    ctx.fillStyle=color;ctx.beginPath();\n"
			+ "    ctx.arc(xOf(i),yOf(data[i]),2.5,0,Math.PI*2);ctx.fill();\n"
			+ "  }\n"
			// x-axis labels
			+ "  ctx.fillStyle='#888';ctx.textAlign='center';ctx.font='10px sans-serif';\n"
			+ "  [0,Math.floor((data.length-1)/2),data.length-1].forEach(function(i){\n"
			+ "    if(i>=0&&i<data.length)ctx.fillText(i+1,xOf(i),pad.top+h+20);\n"
			+ "  });\n"
			// tooltip
			+ "  canvas.onmousemove=function(e){\n"
			+ "    var rect=canvas.getBoundingClientRect(),sx=W/rect.width;\n"
			+ "    var mx=(e.clientX-rect.left)*sx;\n"
			+ "    var idx=data.length>1?Math.round((mx-pad.left)/w*(data.length-1)):0;\n"
			+ "    idx=Math.max(0,Math.min(data.length-1,idx));\n"
			+ "    var tt=getTooltip(),val=fmt?fmt(data[idx]):data[idx];\n"
			+ "    tt.textContent='Run '+(idx+1)+': '+val;\n"
			+ "    tt.style.display='block';tt.style.left=(e.clientX+12)+'px';tt.style.top=(e.clientY-28)+'px';\n"
			+ "  };\n"
			+ "  canvas.onmouseleave=function(){getTooltip().style.display='none';};\n"
			+ "}\n"
			// ---- Table pagination ----
			+ "var _pages={},_PAGE=50;\n"
			+ "function showPage(tid,page){\n"
			+ "  var table=document.getElementById(tid);if(!table)return;\n"
			+ "  var rows=Array.from(table.querySelector('tbody').querySelectorAll(':scope > tr'));\n"
			+ "  var total=rows.length,pages=Math.max(1,Math.ceil(total/_PAGE));\n"
			+ "  page=Math.max(1,Math.min(page,pages));_pages[tid]=page;\n"
			+ "  rows.forEach(function(r,i){\n"
			+ "    r.style.display=(i>=(page-1)*_PAGE&&i<page*_PAGE)?'':' none';\n"
			+ "  });\n"
			+ "  var pid=tid.replace('tbl-','pag-');\n"
			+ "  var info=document.getElementById(pid+'-info');\n"
			+ "  if(info)info.textContent='Page '+page+' of '+pages;\n"
			+ "  var pdiv=document.getElementById(pid);\n"
			+ "  if(pdiv)pdiv.style.display=pages>1?'flex':'none';\n"
			+ "}\n"
			+ "function changePage(tid,d){showPage(tid,(_pages[tid]||1)+d);}\n"
			// ---- Table sorting ----
			+ "function sortTable(tid,col,type){\n"
			+ "  var table=document.getElementById(tid);\n"
			+ "  var tbody=table.querySelector('tbody');\n"
			+ "  var rows=Array.from(tbody.querySelectorAll(':scope > tr'));\n"
			+ "  var ths=Array.from(table.querySelectorAll('th'));\n"
			+ "  var prevCol=table.dataset.sortCol!==undefined?parseInt(table.dataset.sortCol):-1;\n"
			+ "  var asc=(prevCol===col&&table.dataset.sortDir==='asc')?false:true;\n"
			+ "  table.dataset.sortCol=col;table.dataset.sortDir=asc?'asc':'desc';\n"
			+ "  ths.forEach(function(th,i){\n"
			+ "    th.classList.remove('sort-asc','sort-desc');\n"
			+ "    if(i===col)th.classList.add(asc?'sort-asc':'sort-desc');\n"
			+ "  });\n"
			+ "  rows.sort(function(a,b){\n"
			+ "    var ac=a.cells[col],bc=b.cells[col];\n"
			+ "    var av=ac.dataset.val!==undefined?ac.dataset.val:ac.textContent.trim();\n"
			+ "    var bv=bc.dataset.val!==undefined?bc.dataset.val:bc.textContent.trim();\n"
			+ "    if(type==='num'){\n"
			+ "      av=av===''?(asc?Infinity:-Infinity):parseFloat(av);\n"
			+ "      bv=bv===''?(asc?Infinity:-Infinity):parseFloat(bv);\n"
			+ "      return asc?av-bv:bv-av;\n"
			+ "    }\n"
			+ "    return asc?av.localeCompare(bv):bv.localeCompare(av);\n"
			+ "  });\n"
			+ "  rows.forEach(function(r){tbody.appendChild(r);});\n"
			+ "  showPage(tid,1);\n"
			+ "}\n";
	}

	// -------------------------------------------------------------------------
	// CSS
	// -------------------------------------------------------------------------

	private static String css()
	{
		return "* { box-sizing: border-box; margin: 0; padding: 0; }\n"
			+ "body { background: #1a1a1a; color: #c8c8c8; font-family: 'Segoe UI', Arial, sans-serif; padding: 24px; }\n"
			+ "h1 { color: #c8a04a; font-size: 22px; margin-bottom: 20px; }\n"
			+ "h2 { color: #c8a04a; font-size: 16px; margin: 28px 0 12px; border-bottom: 1px solid #3a3a3a; padding-bottom: 6px; }\n"
			+ ".tabs { display: flex; gap: 8px; margin-bottom: 8px; }\n"
			+ ".tab { padding: 6px 18px; background: #252525; border: 1px solid #3a3a3a; border-radius: 4px; color: #888; cursor: pointer; font-size: 13px; font-family: inherit; }\n"
			+ ".tab.active { background: #2e2e2e; color: #c8a04a; border-color: #c8a04a; }\n"
			+ ".tab:hover:not(.active) { background: #2a2a2a; color: #aaa; }\n"
			+ ".summary { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 24px; }\n"
			+ ".stat-card { background: #252525; border: 1px solid #3a3a3a; border-radius: 4px; padding: 10px 18px; min-width: 110px; }\n"
			+ ".stat-label { font-size: 10px; color: #888; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 4px; }\n"
			+ ".stat-value { font-size: 22px; color: #c8a04a; font-weight: bold; }\n"
			// Charts
			+ ".charts-details { margin-bottom: 24px; }\n"
			+ ".charts-summary { color: #c8a04a; cursor: pointer; font-size: 14px; font-weight: bold; padding: 6px 2px; user-select: none; list-style: none; }\n"
			+ ".charts-summary::-webkit-details-marker { display: none; }\n"
			+ ".charts-summary::before { content: '\\25B8  '; }\n"
			+ "details[open] .charts-summary::before { content: '\\25BE  '; }\n"
			+ ".charts-controls { display: flex; justify-content: flex-end; margin: 8px 0 4px; }\n"
			+ ".ma-toggle { padding: 4px 12px; background: #252525; border: 1px solid #3a3a3a; border-radius: 4px; color: #c8a04a; cursor: pointer; font-size: 12px; font-family: inherit; }\n"
			+ ".ma-toggle:hover { background: #2e2e2e; }\n"
			+ ".charts-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 4px; }\n"
			+ ".chart-card { background: #1e1e1e; border: 1px solid #2a2a2a; border-radius: 4px; padding: 10px 10px 6px; }\n"
			+ ".chart-title { font-size: 12px; font-weight: bold; color: #c8c8c8; letter-spacing: 0.3px; margin-bottom: 6px; }\n"
			+ ".chart-card canvas { width: 100%; height: auto; display: block; }\n"
			+ ".chart-tooltip { position: fixed; background: #2a2a2a; border: 1px solid #c8a04a; color: #c8c8c8; padding: 3px 8px; font-size: 11px; pointer-events: none; display: none; border-radius: 3px; z-index: 100; }\n"
			// Table
			+ "table { width: 100%; border-collapse: collapse; font-size: 13px; margin-bottom: 8px; }\n"
			+ "th { background: #252525; color: #c8a04a; padding: 8px 10px; text-align: left; border-bottom: 2px solid #3a3a3a; white-space: nowrap; }\n"
			+ "th.sortable { cursor: pointer; user-select: none; }\n"
			+ "th.sortable:hover { background: #2e2e2e; }\n"
			+ "th.sort-asc::after { content: '  \\25B2'; font-size: 9px; }\n"
			+ "th.sort-desc::after { content: '  \\25BC'; font-size: 9px; }\n"
			+ "tr:nth-child(even) { background: #1e1e1e; }\n"
			+ "tr:nth-child(odd) { background: #1a1a1a; }\n"
			+ "td { padding: 7px 10px; border-bottom: 1px solid #282828; vertical-align: top; }\n"
			+ ".kill { color: #5af542; font-weight: bold; }\n"
			+ ".death { color: #f55142; font-weight: bold; }\n"
			+ ".incomplete { color: #888; }\n"
			+ ".loot { font-size: 12px; line-height: 1.5; }\n"
			+ ".perf { font-size: 11px; color: #aaa; }\n"
			+ ".perf-inner { border-collapse: collapse; font-size: 11px; width: 100%; }\n"
			+ ".perf-inner td { padding: 1px 4px; border: none; color: #aaa; background: transparent; }\n"
			+ ".perf-inner td:last-child { text-align: right; color: #c8c8c8; }\n"
			// Pagination
			+ ".pagination { display: none; align-items: center; gap: 12px; margin: 8px 0 20px; }\n"
			+ ".pagination button { padding: 4px 12px; background: #252525; border: 1px solid #3a3a3a; border-radius: 4px; color: #c8a04a; cursor: pointer; font-size: 12px; font-family: inherit; }\n"
			+ ".pagination button:hover { background: #2e2e2e; }\n"
			+ ".pagination span { color: #888; font-size: 12px; }\n"
			+ ".empty { color: #888; padding: 20px 0; }\n"
			+ ".footer { color: #555; font-size: 11px; margin-top: 16px; }\n";
	}
}
