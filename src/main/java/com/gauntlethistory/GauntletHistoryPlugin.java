package com.gauntlethistory;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Gauntlet History",
	description = "Tracks KC, deaths, loot, and performance stats for the Gauntlet boss.",
	tags = {"gauntlet", "corrupted", "hunllef", "history", "kc", "loot", "performance"}
)
public class GauntletHistoryPlugin extends Plugin
{
	private static final Set<Integer> HUNLLEF_IDS = ImmutableSet.of(
		NpcID.CRYSTAL_HUNLLEF_MELEE, NpcID.CRYSTAL_HUNLLEF_RANGED,
		NpcID.CRYSTAL_HUNLLEF_MAGIC, NpcID.CRYSTAL_HUNLLEF_DEATH,
		NpcID.CRYSTAL_HUNLLEF_MELEE_HM, NpcID.CRYSTAL_HUNLLEF_RANGED_HM,
		NpcID.CRYSTAL_HUNLLEF_MAGIC_HM, NpcID.CRYSTAL_HUNLLEF_DEATH_HM
	);

	private static final Set<Integer> CORRUPTED_IDS = ImmutableSet.of(
		NpcID.CRYSTAL_HUNLLEF_MELEE_HM, NpcID.CRYSTAL_HUNLLEF_RANGED_HM,
		NpcID.CRYSTAL_HUNLLEF_MAGIC_HM, NpcID.CRYSTAL_HUNLLEF_DEATH_HM
	);

	private static final Set<Integer> TORNADO_IDS = ImmutableSet.of(
		NpcID.CRYSTAL_HUNLLEF_CRYSTALS,
		NpcID.CRYSTAL_HUNLLEF_CRYSTALS_HM
	);

	private static final Pattern KC_PATTERN =
		Pattern.compile("Your (?:Corrupted )?Gauntlet kill count is: (\\d+)\\.", Pattern.CASE_INSENSITIVE);

	// "Challenge duration: 6:10.20 (new personal best)." or without the suffix
	private static final Pattern DURATION_PATTERN =
		Pattern.compile("Challenge duration: (\\d+):(\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE);

	// "Preparation time: 2:59.40. Hunllef kill time: 3:10.80."
	private static final Pattern PREP_FIGHT_PATTERN =
		Pattern.compile("Preparation time: (\\d+):(\\d+\\.\\d+)\\. Hunllef kill time: (\\d+):(\\d+\\.\\d+)\\.",
			Pattern.CASE_INSENSITIVE);

	// "PlayerName received a drop: 440 x Adamant arrow"
	private static final Pattern LOOT_PATTERN =
		Pattern.compile("(.+) received a drop: (?:(\\d+) x )?(.+)", Pattern.CASE_INSENSITIVE);

	static final File HISTORY_DIR = new File(RuneLite.RUNELITE_DIR, "gauntlet-history");

	@Inject private Client client;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ItemManager itemManager;
	@Inject private EventBus eventBus;
	@Inject private PerformanceTracker performanceTracker;
	@Inject private SessionManager sessionManager;

	private GauntletHistoryPanel panel;
	private NavigationButton navButton;
	private boolean inGauntlet;
	private boolean inBossFight;

	@Override
	protected void startUp()
	{
		HISTORY_DIR.mkdirs();

		panel = new GauntletHistoryPanel(this);
		navButton = NavigationButton.builder()
			.tooltip("Gauntlet History")
			.icon(GauntletHistoryPanel.buildIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		eventBus.register(performanceTracker);

		sessionManager.loadAsync(() ->
		{
			if (panel != null)
			{
				panel.refresh();
			}
		});

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			inGauntlet = client.getVarbitValue(VarbitID.PLAYER_IN_GAUNTLET) == 1;
			inBossFight = client.getVarbitValue(VarbitID.GAUNTLET_BOSS_STARTED) == 1;
			if (inGauntlet)
			{
				performanceTracker.enterGauntlet();
				sessionManager.startSession(Instant.now());
			}
			if (inBossFight && sessionManager.current() != null)
			{
				sessionManager.current().bossStartTime = Instant.now();
				performanceTracker.startBossFight();
			}
		}
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(performanceTracker);
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;

		if (sessionManager.current() != null)
		{
			finishCurrentSession();
		}
		sessionManager.shutdown();
		performanceTracker.reset();
		reset();
	}

	// -------------------------------------------------------------------------
	// Gauntlet state machine
	// -------------------------------------------------------------------------

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		final int id = event.getVarbitId();

		if (id == VarbitID.PLAYER_IN_GAUNTLET)
		{
			if (event.getValue() == 1)
			{
				inGauntlet = true;
				performanceTracker.enterGauntlet();
				if (sessionManager.current() == null)
				{
					sessionManager.startSession(Instant.now());
					log.debug("Entered Gauntlet, started session");
				}
			}
			else
			{
				inGauntlet = false;
				log.debug("Left Gauntlet, finishing session");
				finishCurrentSession();
				reset();
			}
		}
		else if (id == VarbitID.GAUNTLET_BOSS_STARTED)
		{
			if (event.getValue() == 1)
			{
				inBossFight = true;
				if (sessionManager.current() == null)
				{
					// Plugin enabled mid-fight
					inGauntlet = true;
					performanceTracker.enterGauntlet();
					sessionManager.startSession(Instant.now());
				}
				sessionManager.current().bossStartTime = Instant.now();
				performanceTracker.startBossFight();
				log.debug("Boss fight started");
			}
			else
			{
				inBossFight = false;
				GauntletSession current = sessionManager.current();
				if (current != null && current.perf == null)
				{
					current.perf = performanceTracker.endBossFight();
				}
				else
				{
					performanceTracker.endBossFight();
				}
				log.debug("Boss fight ended");
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			if (sessionManager.current() != null)
			{
				finishCurrentSession();
			}
			reset();
		}
	}

	// -------------------------------------------------------------------------
	// NPC tracking
	// -------------------------------------------------------------------------

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (!inGauntlet)
		{
			return;
		}
		NPC npc = event.getNpc();
		if (TORNADO_IDS.contains(npc.getId()))
		{
			performanceTracker.addTornado(npc);
		}
		else if (HUNLLEF_IDS.contains(npc.getId()))
		{
			performanceTracker.setHunllef(npc);
			GauntletSession current = sessionManager.current();
			if (current != null)
			{
				current.corrupted = CORRUPTED_IDS.contains(npc.getId());
			}
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (!inGauntlet)
		{
			return;
		}
		NPC npc = event.getNpc();
		if (TORNADO_IDS.contains(npc.getId()))
		{
			performanceTracker.removeTornado(npc);
		}
		else if (HUNLLEF_IDS.contains(npc.getId()))
		{
			performanceTracker.setHunllef(null);
		}
	}

	// -------------------------------------------------------------------------
	// Kill / loot / death / KC
	// -------------------------------------------------------------------------

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		GauntletSession current = sessionManager.current();
		if (!HUNLLEF_IDS.contains(event.getNpc().getId()) || current == null)
		{
			return;
		}
		current.killedBoss = true;
		for (ItemStack item : event.getItems())
		{
			String name = itemManager.getItemComposition(item.getId()).getName();
			current.loot.add(new GauntletSession.LootItem(item.getId(), name, item.getQuantity()));
		}
		log.debug("Received {} loot items from Hunllef", event.getItems().size());
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		GauntletSession current = sessionManager.current();
		if (current == null || !inGauntlet)
		{
			return;
		}
		if (event.getActor() == client.getLocalPlayer())
		{
			if (inBossFight)
			{
				current.diedInBoss = true;
			}
			else
			{
				current.diedInPrep = true;
			}
			log.debug("Player died (bossPhase={})", inBossFight);
		}
		else if (HUNLLEF_IDS.contains(((NPC) event.getActor()).getId()))
		{
			current.killedBoss = true;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		GauntletSession current = sessionManager.current();
		if (current == null || event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		final String msg = event.getMessage();

		Matcher kc = KC_PATTERN.matcher(msg);
		if (kc.find())
		{
			current.killCount = Integer.parseInt(kc.group(1));
			log.debug("KC detected: {}", current.killCount);
		}

		Matcher dur = DURATION_PATTERN.matcher(msg);
		if (dur.find())
		{
			current.totalTimeMs = parseTimeMs(dur.group(1), dur.group(2));
			log.debug("Total time: {}ms", current.totalTimeMs);
		}

		Matcher pf = PREP_FIGHT_PATTERN.matcher(msg);
		if (pf.find())
		{
			current.prepTimeMs = parseTimeMs(pf.group(1), pf.group(2));
			current.fightTimeMs = parseTimeMs(pf.group(3), pf.group(4));
			log.debug("Prep: {}ms  Fight: {}ms", current.prepTimeMs, current.fightTimeMs);
		}

		Matcher loot = LOOT_PATTERN.matcher(msg);
		if (loot.find() && inGauntlet)
		{
			String localName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
			if (localName != null && localName.equals(loot.group(1)))
			{
				int qty = loot.group(2) != null ? Integer.parseInt(loot.group(2)) : 1;
				String itemName = loot.group(3);
				current.loot.add(new GauntletSession.LootItem(-1, itemName, qty));
				log.debug("Loot: {} x {}", qty, itemName);
			}
		}
	}

	private static long parseTimeMs(String minutes, String seconds)
	{
		return Long.parseLong(minutes) * 60_000L + Math.round(Double.parseDouble(seconds) * 1000);
	}

	// -------------------------------------------------------------------------
	// Internal helpers
	// -------------------------------------------------------------------------

	List<GauntletSession> getSessions()
	{
		return sessionManager.getSessions();
	}

	void exportHtml() throws IOException
	{
		sessionManager.exportHtml();
	}

	private void finishCurrentSession()
	{
		// Snapshot perf if the boss-ended varbit didn't fire (e.g. logout mid-fight)
		GauntletSession current = sessionManager.current();
		if (current != null && current.perf == null && inBossFight)
		{
			current.perf = performanceTracker.endBossFight();
		}

		sessionManager.finishSession(() ->
		{
			if (panel != null)
			{
				panel.refresh();
			}
		});
	}

	private void reset()
	{
		inGauntlet = false;
		inBossFight = false;
		performanceTracker.reset();
	}

	@Provides
	GauntletHistoryConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GauntletHistoryConfig.class);
	}
}
