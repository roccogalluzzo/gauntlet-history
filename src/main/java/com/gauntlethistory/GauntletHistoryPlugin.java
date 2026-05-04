package com.gauntlethistory;

import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Prayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Gauntlet History",
	description = "Tracks KC, deaths, loot, and performance stats for the Gauntlet boss.",
	tags = {"gauntlet", "corrupted", "hunllef", "history", "kc", "loot", "performance"}
)
public class GauntletHistoryPlugin extends Plugin
{
	// Hunllef combat forms — four per variant (melee/ranged/magic/death phase)
	private static final Set<Integer> HUNLLEF_IDS = ImmutableSet.of(
		NpcID.CRYSTAL_HUNLLEF_MELEE,
		NpcID.CRYSTAL_HUNLLEF_RANGED,
		NpcID.CRYSTAL_HUNLLEF_MAGIC,
		NpcID.CRYSTAL_HUNLLEF_DEATH,
		NpcID.CRYSTAL_HUNLLEF_MELEE_HM,
		NpcID.CRYSTAL_HUNLLEF_RANGED_HM,
		NpcID.CRYSTAL_HUNLLEF_MAGIC_HM,
		NpcID.CRYSTAL_HUNLLEF_DEATH_HM
	);

	private static final Set<Integer> CORRUPTED_IDS = ImmutableSet.of(
		NpcID.CRYSTAL_HUNLLEF_MELEE_HM,
		NpcID.CRYSTAL_HUNLLEF_RANGED_HM,
		NpcID.CRYSTAL_HUNLLEF_MAGIC_HM,
		NpcID.CRYSTAL_HUNLLEF_DEATH_HM
	);

	// Tornadoes — game data names these CRYSTALS but they are the tornado null NPCs
	private static final Set<Integer> TORNADO_IDS = ImmutableSet.of(
		NpcID.CRYSTAL_HUNLLEF_CRYSTALS,     // regular Gauntlet (id 9025)
		NpcID.CRYSTAL_HUNLLEF_CRYSTALS_HM   // corrupted Gauntlet (id 9039)
	);

	// Player attack animations
	private static final int[] PLAYER_ATTACK_ANIMS = {
		AnimationID.HUMAN_CASTWAVE_STAFF,  // mage (crystal staff)
		AnimationID.HUMAN_BOW,             // ranged (crystal bow)
		AnimationID.HUMAN_SPEAR_SPIKE,     // melee
		AnimationID.HUMAN_SCYTHE_SWEEP,    // melee alt
		AnimationID.HUMAN_BLUNT_POUND,     // sceptre
		AnimationID.HUMAN_UNARMEDKICK,
		AnimationID.HUMAN_UNARMEDPUNCH
	};

	// No-weapon animations that can optionally be excluded from wrong-pray counting
	private static final Set<Integer> NO_WEAPON_ANIMS = ImmutableSet.of(
		AnimationID.HUMAN_BLUNT_POUND,
		AnimationID.HUMAN_UNARMEDKICK,
		AnimationID.HUMAN_UNARMEDPUNCH
	);

	private static final int WEAPON_ATTACK_SPEED = 4;   // crystal bow / halberd
	private static final int SCEPTRE_ATTACK_SPEED = 5;
	private static final int NORMAL_FOOD_DELAY = 3;
	private static final int FAST_FOOD_DELAY = 2;

	// Ground object ID for the Hunllef floor tiles — no gameval constant exists for this
	private static final int DAMAGE_TILE_ID = 36048;

	private static final Pattern KC_PATTERN =
		Pattern.compile("Your (?:Corrupted )?Gauntlet kill count is: (\\d+)\\.", Pattern.CASE_INSENSITIVE);

	static final File HISTORY_DIR = new File(RuneLite.RUNELITE_DIR, "gauntlet-history");

	@Inject private Client client;
	@Inject private GauntletHistoryConfig config;
	@Inject private ItemManager itemManager;
	@Inject private ClientToolbar clientToolbar;
	@Inject private Gson gson;

	private GauntletHistoryPanel panel;
	private NavigationButton navButton;

	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	final CopyOnWriteArrayList<GauntletSession> sessions = new CopyOnWriteArrayList<>();

	// Session state
	private boolean inGauntlet;
	private boolean inBossFight;
	private NPC hunllef;
	private GauntletSession currentSession;

	// Live performance tracking — mirrors RLCGPerformanceTracker logic
	private PerformanceData livePerf;
	private TickLossState tickLossState = TickLossState.NONE;
	private int previousAttackTick;
	private int currentWeaponAttackSpeed = WEAPON_ATTACK_SPEED;
	private boolean isHunllefMaging;
	private final List<NPC> tornadoes = new ArrayList<>();
	private final ArrayDeque<ItemMenuAction> actionStack = new ArrayDeque<>();

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

		executor.submit(() ->
		{
			loadSessions();
			SwingUtilities.invokeLater(() ->
			{
				if (panel != null)
				{
					panel.refresh();
				}
			});
		});

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			inGauntlet = client.getVarbitValue(VarbitID.PLAYER_IN_GAUNTLET) == 1;
			inBossFight = client.getVarbitValue(VarbitID.GAUNTLET_BOSS_STARTED) == 1;
			if (inGauntlet)
			{
				currentSession = new GauntletSession(Instant.now());
			}
			if (inBossFight && currentSession != null)
			{
				currentSession.bossStartTime = Instant.now();
				livePerf = new PerformanceData();
				previousAttackTick = client.getTickCount();
			}
		}
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;

		if (currentSession != null)
		{
			finishSession();
		}
		executor.shutdownNow();
		reset();
	}

	// -------------------------------------------------------------------------
	// Gauntlet state via varbits (cleaner than region polling)
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
				if (currentSession == null)
				{
					currentSession = new GauntletSession(Instant.now());
					log.debug("Entered Gauntlet, started session");
				}
			}
			else
			{
				inGauntlet = false;
				log.debug("Left Gauntlet, finishing session");
				finishSession();
				reset();
			}
		}
		else if (id == VarbitID.GAUNTLET_BOSS_STARTED)
		{
			if (event.getValue() == 1)
			{
				inBossFight = true;
				if (currentSession == null)
				{
					// Plugin enabled mid-fight
					currentSession = new GauntletSession(Instant.now());
					inGauntlet = true;
				}
				currentSession.bossStartTime = Instant.now();
				resetBossFight();
				log.debug("Boss fight started");
			}
			else
			{
				// Boss phase over — snapshot perf data into session
				if (currentSession != null && livePerf != null)
				{
					currentSession.perf = livePerf;
				}
				inBossFight = false;
				tornadoes.clear();
				hunllef = null;
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
			if (currentSession != null)
			{
				finishSession();
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
			tornadoes.add(npc);
		}
		else if (HUNLLEF_IDS.contains(npc.getId()))
		{
			hunllef = npc;
			if (currentSession != null)
			{
				currentSession.corrupted = CORRUPTED_IDS.contains(npc.getId());
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
			tornadoes.remove(npc);
		}
		else if (HUNLLEF_IDS.contains(npc.getId()) && npc == hunllef)
		{
			hunllef = null;
		}
	}

	// -------------------------------------------------------------------------
	// Performance tracking — ported from RLCGPerformanceTracker
	// -------------------------------------------------------------------------

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!inBossFight || livePerf == null)
		{
			return;
		}

		livePerf.totalTicks++;

		// Tick-loss state
		int diff = client.getTickCount() - previousAttackTick;
		if (diff >= currentWeaponAttackSpeed + NORMAL_FOOD_DELAY)
		{
			tickLossState = TickLossState.LOSING;
		}
		else if (diff >= currentWeaponAttackSpeed)
		{
			tickLossState = TickLossState.POTENTIAL;
		}
		else
		{
			tickLossState = TickLossState.NONE;
		}

		// Tornado overlap
		WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();
		for (NPC tornado : tornadoes)
		{
			if (playerLoc.equals(tornado.getWorldLocation()))
			{
				livePerf.tornadoHits++;
			}
		}

		// Floor tile damage
		var scene = client.getWorldView(client.getLocalPlayer().getLocalLocation().getWorldView()).getScene();
		var tiles = scene.getTiles();
		int tileX = playerLoc.getX() - scene.getBaseX();
		int tileY = playerLoc.getY() - scene.getBaseY();
		var tile = tiles[playerLoc.getPlane()][tileX][tileY];
		if (tile != null && tile.getGroundObject() != null && tile.getGroundObject().getId() == DAMAGE_TILE_ID)
		{
			livePerf.floorTileHits++;
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (!inBossFight || livePerf == null)
		{
			return;
		}

		int anim = event.getActor().getAnimation();
		if (anim < 0)
		{
			return;
		}

		Actor actor = event.getActor();
		if (actor == client.getLocalPlayer())
		{
			if (Arrays.stream(PLAYER_ATTACK_ANIMS).anyMatch(a -> a == anim))
			{
				livePerf.playerAttacks++;

				if (hunllef != null && !hasCorrectAttackStyle(anim))
				{
					livePerf.wrongAttackStyle++;
				}
				if (!hasCorrectOffensivePrayer(anim))
				{
					livePerf.wrongOffPray++;
				}

				int now = client.getTickCount();
				int lost = (now - previousAttackTick) - currentWeaponAttackSpeed;
				if (lost > 0)
				{
					livePerf.lostTicks += lost;
				}

				currentWeaponAttackSpeed = (anim == AnimationID.HUMAN_BLUNT_POUND)
					? SCEPTRE_ATTACK_SPEED : WEAPON_ATTACK_SPEED;
				previousAttackTick = now;
			}
		}
		else if (actor == hunllef)
		{
			if (anim == AnimationID.HUNLLEF_ATTACK_RANGED)
			{
				livePerf.hunllefAttacks++;
				if (!hasCorrectDefensivePrayer())
				{
					livePerf.wrongDefPray++;
				}
			}
			else if (anim == AnimationID.HUNLLEF_ATTACK_MELEE)
			{
				livePerf.hunllefStomps++;
			}
			else if (anim == AnimationID.HUNLLEF_ATTACK_TRANSITION_MAGIC)
			{
				isHunllefMaging = true;
			}
			else if (anim == AnimationID.HUNLLEF_ATTACK_TRANSITION_RANGED)
			{
				isHunllefMaging = false;
			}
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!inBossFight || livePerf == null)
		{
			return;
		}
		int amount = event.getHitsplat().getAmount();
		if (event.getActor() == client.getLocalPlayer())
		{
			livePerf.damageTaken += amount;
		}
		else if (event.getActor() == hunllef)
		{
			livePerf.damageGiven += amount;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!inGauntlet)
		{
			return;
		}
		String option = Text.removeTags(event.getMenuOption()).toLowerCase();
		if (!option.startsWith("eat"))
		{
			return;
		}
		// Avoid duplicates in the stack for the same item slot
		final int itemId = event.getItemId();
		if (actionStack.stream().anyMatch(a -> a instanceof ItemMenuAction.ItemAction
			&& ((ItemMenuAction.ItemAction) a).itemID == itemId))
		{
			return;
		}
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv == null)
		{
			return;
		}
		int slot = event.getMenuEntry().getParam0();
		actionStack.push(new ItemMenuAction.ItemAction(inv.getItems(), itemId, slot));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!inGauntlet || event.getItemContainer().getId() != InventoryID.INV)
		{
			return;
		}
		ItemContainer newInv = event.getItemContainer();
		while (!actionStack.isEmpty())
		{
			ItemMenuAction.ItemAction action = (ItemMenuAction.ItemAction) actionStack.pop();
			if (newInv.getItems()[action.slot].getId() != action.oldInventory[action.slot].getId())
			{
				// Item was consumed — adjust tick window
				if (action.itemID == ItemID.GAUNTLET_FOOD)
				{
					previousAttackTick += NORMAL_FOOD_DELAY;
				}
				else if (action.itemID == ItemID.GAUNTLET_COMBO_FOOD || action.itemID == ItemID.GAUNTLET_COMBO_FOOD_HM)
				{
					previousAttackTick += FAST_FOOD_DELAY;
				}
			}
		}
	}

	// -------------------------------------------------------------------------
	// Kill / loot / KC detection
	// -------------------------------------------------------------------------

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (!HUNLLEF_IDS.contains(event.getNpc().getId()) || currentSession == null)
		{
			return;
		}
		currentSession.killedBoss = true;
		for (ItemStack item : event.getItems())
		{
			String name = itemManager.getItemComposition(item.getId()).getName();
			currentSession.loot.add(new GauntletSession.LootItem(item.getId(), name, item.getQuantity()));
		}
		log.debug("Received {} loot items from Hunllef", event.getItems().size());
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (currentSession == null || !inGauntlet)
		{
			return;
		}
		if (event.getActor() == client.getLocalPlayer())
		{
			if (inBossFight)
			{
				currentSession.diedInBoss = true;
			}
			else
			{
				currentSession.diedInPrep = true;
			}
			log.debug("Player died (bossPhase={})", inBossFight);
		}
		else if (event.getActor() == hunllef)
		{
			currentSession.killedBoss = true;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (currentSession == null || event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		Matcher m = KC_PATTERN.matcher(event.getMessage());
		if (m.find())
		{
			currentSession.killCount = Integer.parseInt(m.group(1));
			log.debug("KC detected: {}", currentSession.killCount);
		}
	}

	// -------------------------------------------------------------------------
	// Prayer / attack style helpers — ported from RLCGPerformanceTracker
	// -------------------------------------------------------------------------

	private boolean hasCorrectOffensivePrayer(int anim)
	{
		boolean noWeapon = NO_WEAPON_ANIMS.contains(anim);
		if (noWeapon && !config.countNoWeaponOffPrayer())
		{
			return true;
		}

		if (anim == AnimationID.HUMAN_SPEAR_SPIKE || anim == AnimationID.HUMAN_SCYTHE_SWEEP || noWeapon)
		{
			return isPrayer(Prayer.PIETY)
				|| isPrayer(Prayer.ULTIMATE_STRENGTH)
				|| isPrayer(Prayer.SUPERHUMAN_STRENGTH)
				|| isPrayer(Prayer.BURST_OF_STRENGTH);
		}
		if (anim == AnimationID.HUMAN_CASTWAVE_STAFF)
		{
			return isPrayer(Prayer.AUGURY)
				|| isPrayer(Prayer.MYSTIC_MIGHT)
				|| isPrayer(Prayer.MYSTIC_LORE)
				|| isPrayer(Prayer.MYSTIC_WILL);
		}
		if (anim == AnimationID.HUMAN_BOW)
		{
			return isPrayer(Prayer.RIGOUR)
				|| isPrayer(Prayer.EAGLE_EYE)
				|| isPrayer(Prayer.HAWK_EYE)
				|| isPrayer(Prayer.SHARP_EYE);
		}
		return false;
	}

	private boolean hasCorrectAttackStyle(int anim)
	{
		if (hunllef == null)
		{
			return true;
		}
		boolean isMelee = anim == AnimationID.HUMAN_SPEAR_SPIKE
			|| anim == AnimationID.HUMAN_SCYTHE_SWEEP
			|| NO_WEAPON_ANIMS.contains(anim);
		boolean isRanged = anim == AnimationID.HUMAN_BOW;
		boolean isMage = anim == AnimationID.HUMAN_CASTWAVE_STAFF;

		switch (hunllef.getId())
		{
			case NpcID.CRYSTAL_HUNLLEF_MELEE:
			case NpcID.CRYSTAL_HUNLLEF_MELEE_HM:
				return !isMelee;
			case NpcID.CRYSTAL_HUNLLEF_RANGED:
			case NpcID.CRYSTAL_HUNLLEF_RANGED_HM:
				return !isRanged;
			case NpcID.CRYSTAL_HUNLLEF_MAGIC:
			case NpcID.CRYSTAL_HUNLLEF_MAGIC_HM:
				return !isMage;
			default:
				return true;
		}
	}

	private boolean isPrayer(Prayer prayer)
	{
		return client.getVarbitValue(prayer.getVarbit()) != 0;
	}

	private boolean hasCorrectDefensivePrayer()
	{
		return isHunllefMaging
			? isPrayer(Prayer.PROTECT_FROM_MAGIC)
			: isPrayer(Prayer.PROTECT_FROM_MISSILES);
	}

	// -------------------------------------------------------------------------
	// Session lifecycle
	// -------------------------------------------------------------------------

	void exportHtml() throws IOException
	{
		HtmlExporter.export(new ArrayList<>(sessions), HISTORY_DIR);
	}

	private void finishSession()
	{
		if (currentSession == null)
		{
			return;
		}
		// Snapshot live perf if boss ended without the varbit firing cleanly
		if (currentSession.perf == null && livePerf != null)
		{
			currentSession.perf = livePerf;
		}
		currentSession.endTime = Instant.now();
		sessions.add(0, currentSession);

		int max = config.maxSessions();
		if (max > 0)
		{
			while (sessions.size() > max)
			{
				sessions.remove(sessions.size() - 1);
			}
		}

		GauntletSession finished = currentSession;
		currentSession = null;

		executor.submit(() ->
		{
			saveSessions();
			if (config.autoExport())
			{
				try
				{
					HtmlExporter.export(new ArrayList<>(sessions), HISTORY_DIR);
				}
				catch (IOException e)
				{
					log.warn("Failed to auto-export HTML", e);
				}
			}
		});

		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.refresh();
			}
		});

		log.debug("Session finished: kill={} diedBoss={} kc={}", finished.killedBoss, finished.diedInBoss, finished.killCount);
	}

	private void resetBossFight()
	{
		livePerf = new PerformanceData();
		isHunllefMaging = false;
		previousAttackTick = client.getTickCount();
		currentWeaponAttackSpeed = WEAPON_ATTACK_SPEED;
		tickLossState = TickLossState.NONE;
		actionStack.clear();
	}

	private void reset()
	{
		inGauntlet = false;
		inBossFight = false;
		hunllef = null;
		currentSession = null;
		livePerf = null;
		tornadoes.clear();
		actionStack.clear();
		tickLossState = TickLossState.NONE;
	}

	private void loadSessions()
	{
		File file = new File(HISTORY_DIR, "sessions.json");
		if (!file.exists())
		{
			return;
		}
		try (FileReader reader = new FileReader(file))
		{
			Gson localGson = gson.newBuilder()
				.registerTypeAdapter(Instant.class, new InstantAdapter())
				.create();
			Type listType = new TypeToken<List<GauntletSession>>()
			{
			}.getType();
			List<GauntletSession> loaded = localGson.fromJson(reader, listType);
			if (loaded != null)
			{
				sessions.addAll(loaded);
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to load session history", e);
		}
	}

	private void saveSessions()
	{
		File file = new File(HISTORY_DIR, "sessions.json");
		try (FileWriter writer = new FileWriter(file))
		{
			Gson localGson = gson.newBuilder()
				.registerTypeAdapter(Instant.class, new InstantAdapter())
				.setPrettyPrinting()
				.create();
			localGson.toJson(new ArrayList<>(sessions), writer);
		}
		catch (IOException e)
		{
			log.warn("Failed to save session history", e);
		}
	}

	@Provides
	GauntletHistoryConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GauntletHistoryConfig.class);
	}
}
