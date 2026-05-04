package com.gauntlethistory;

import com.google.common.collect.ImmutableSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Prayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

class PerformanceTracker
{
	private static final int[] PLAYER_ATTACK_ANIMS = {
		AnimationID.HUMAN_CASTWAVE_STAFF,
		AnimationID.HUMAN_BOW,
		AnimationID.HUMAN_SPEAR_SPIKE,
		AnimationID.HUMAN_SCYTHE_SWEEP,
		AnimationID.HUMAN_BLUNT_POUND,
		AnimationID.HUMAN_UNARMEDKICK,
		AnimationID.HUMAN_UNARMEDPUNCH
	};

	private static final Set<Integer> NO_WEAPON_ANIMS = ImmutableSet.of(
		AnimationID.HUMAN_BLUNT_POUND,
		AnimationID.HUMAN_UNARMEDKICK,
		AnimationID.HUMAN_UNARMEDPUNCH
	);

	private static final int WEAPON_ATTACK_SPEED = 4;
	private static final int SCEPTRE_ATTACK_SPEED = 5;
	private static final int NORMAL_FOOD_DELAY = 3;
	private static final int FAST_FOOD_DELAY = 2;
	private static final int DAMAGE_TILE_ID = 36048;

	@Inject private Client client;
	@Inject private GauntletHistoryConfig config;

	private PerformanceData livePerf;
	private TickLossState tickLossState = TickLossState.NONE;
	private int previousAttackTick;
	private int currentWeaponAttackSpeed = WEAPON_ATTACK_SPEED;
	private boolean isHunllefMaging;
	private boolean inGauntlet;
	private NPC hunllef;
	private final List<NPC> tornadoes = new ArrayList<>();
	private final ArrayDeque<ItemMenuAction> actionStack = new ArrayDeque<>();

	void enterGauntlet()
	{
		inGauntlet = true;
	}

	void startBossFight()
	{
		livePerf = new PerformanceData();
		isHunllefMaging = false;
		previousAttackTick = client.getTickCount();
		currentWeaponAttackSpeed = WEAPON_ATTACK_SPEED;
		tickLossState = TickLossState.NONE;
		actionStack.clear();
	}

	/** Returns the completed fight's data and clears fight state. */
	PerformanceData endBossFight()
	{
		PerformanceData snapshot = livePerf;
		livePerf = null;
		tornadoes.clear();
		return snapshot;
	}

	void setHunllef(NPC npc)
	{
		hunllef = npc;
	}

	void addTornado(NPC npc)
	{
		tornadoes.add(npc);
	}

	void removeTornado(NPC npc)
	{
		tornadoes.remove(npc);
	}

	void reset()
	{
		inGauntlet = false;
		livePerf = null;
		hunllef = null;
		tornadoes.clear();
		actionStack.clear();
		tickLossState = TickLossState.NONE;
	}

	// -------------------------------------------------------------------------
	// Event handlers — registered with EventBus by GauntletHistoryPlugin
	// -------------------------------------------------------------------------

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (livePerf == null)
		{
			return;
		}

		livePerf.totalTicks++;

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

		WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();
		for (NPC tornado : tornadoes)
		{
			if (playerLoc.equals(tornado.getWorldLocation()))
			{
				livePerf.tornadoHits++;
			}
		}

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
		if (livePerf == null)
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
			handlePlayerAnimation(anim);
		}
		else if (actor == hunllef)
		{
			handleHunllefAnimation(anim);
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (livePerf == null)
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
	// Private helpers
	// -------------------------------------------------------------------------

	private void handlePlayerAnimation(int anim)
	{
		if (Arrays.stream(PLAYER_ATTACK_ANIMS).noneMatch(a -> a == anim))
		{
			return;
		}

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

	private void handleHunllefAnimation(int anim)
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

	private boolean hasCorrectOffensivePrayer(int anim)
	{
		boolean noWeapon = NO_WEAPON_ANIMS.contains(anim);
		if (noWeapon && !config.countNoWeaponOffPrayer())
		{
			return true;
		}

		if (anim == AnimationID.HUMAN_SPEAR_SPIKE || anim == AnimationID.HUMAN_SCYTHE_SWEEP || noWeapon)
		{
			return isPrayer(Prayer.PIETY) || isPrayer(Prayer.ULTIMATE_STRENGTH)
				|| isPrayer(Prayer.SUPERHUMAN_STRENGTH) || isPrayer(Prayer.BURST_OF_STRENGTH);
		}
		if (anim == AnimationID.HUMAN_CASTWAVE_STAFF)
		{
			return isPrayer(Prayer.AUGURY) || isPrayer(Prayer.MYSTIC_MIGHT)
				|| isPrayer(Prayer.MYSTIC_LORE) || isPrayer(Prayer.MYSTIC_WILL);
		}
		if (anim == AnimationID.HUMAN_BOW)
		{
			return isPrayer(Prayer.RIGOUR) || isPrayer(Prayer.EAGLE_EYE)
				|| isPrayer(Prayer.HAWK_EYE) || isPrayer(Prayer.SHARP_EYE);
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

	private boolean hasCorrectDefensivePrayer()
	{
		return isHunllefMaging
			? isPrayer(Prayer.PROTECT_FROM_MAGIC)
			: isPrayer(Prayer.PROTECT_FROM_MISSILES);
	}

	private boolean isPrayer(Prayer prayer)
	{
		return client.getVarbitValue(prayer.getVarbit()) != 0;
	}
}
