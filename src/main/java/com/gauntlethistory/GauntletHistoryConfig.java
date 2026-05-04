package com.gauntlethistory;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("gauntlet-history")
public interface GauntletHistoryConfig extends Config
{
	@ConfigItem(
		keyName = "autoExport",
		name = "Auto-export after run",
		description = "Automatically update the HTML export file after each completed run"
	)
	default boolean autoExport()
	{
		return false;
	}

	@ConfigItem(
		keyName = "countNoWeaponOffPrayer",
		name = "Count no-weapon off-prayer",
		description = "Count unarmed / sceptre attacks without a melee offensive prayer as wrong off-prayer"
	)
	default boolean countNoWeaponOffPrayer()
	{
		return false;
	}

	@ConfigItem(
		keyName = "maxSessions",
		name = "Max sessions to keep",
		description = "Maximum number of sessions stored in history (0 = unlimited)"
	)
	default int maxSessions()
	{
		return 500;
	}
}
