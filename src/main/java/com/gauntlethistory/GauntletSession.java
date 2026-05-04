package com.gauntlethistory;

import java.time.Instant;

public class GauntletSession
{
	public Instant startTime;
	public Instant bossStartTime;
	public Instant endTime;
	public boolean corrupted;
	public boolean killedBoss;
	public boolean diedInPrep;
	public boolean diedInBoss;
	public int killCount = -1;
	/** Game-reported times in milliseconds; -1 when not available. */
	public long prepTimeMs = -1;
	public long fightTimeMs = -1;
	public long totalTimeMs = -1;
	public PerformanceData perf;

	public GauntletSession(Instant startTime)
	{
		this.startTime = startTime;
	}

	// Default constructor for Gson
	public GauntletSession()
	{
	}
}
