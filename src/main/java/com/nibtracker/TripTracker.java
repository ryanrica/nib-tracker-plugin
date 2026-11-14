package com.nibtracker;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/**
 * Sends one "boss_trip" event per trip with at least one kill count message (see {@link BossTrip}), under the Kill
 * counts switch. Totals only, sent after the trip: nothing is shown or worked out for the player during a fight.
 * Client thread only.
 */
@Slf4j
@Singleton
class TripTracker
{
	@Inject
	private Client client;

	@Inject
	private NibTrackerConfig config;

	@Inject
	private ItemManager itemManager;

	private EventSink sink;
	private BossTrip trip;
	private boolean itemsChanged;
	private boolean bankTouched;
	private boolean awaitingRespawn;

	void start(EventSink sink)
	{
		this.sink = sink;
	}

	/** A message the chat parser read: kill counts and boss times go into the trip. */
	void onParsed(String type, JsonObject d)
	{
		Player me = client.getLocalPlayer();
		if (trip == null || me == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		if ("kc".equals(type))
		{
			// In an instance, the region of the map it was copied from, not the instance's own (which changes every time).
			trip.kill(d.get("boss").getAsString(), d.get("count").getAsInt(), now,
				WorldPoint.fromLocalInstance(client, me.getLocalLocation()).getRegionID(), client.getTickCount());
		}
		else if ("boss_time".equals(type))
		{
			trip.time(d.get("seconds").getAsDouble(), now);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		int id = e.getContainerId();
		if (id == InventoryID.BANK)
		{
			bankTouched = true;
		}
		else if (id == InventoryID.INV || id == InventoryID.WORN)
		{
			itemsChanged = true;
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		if (trip == null)
		{
			return;
		}
		Hitsplat h = e.getHitsplat();
		if (e.getActor() == client.getLocalPlayer())
		{
			if (takesHitpoints(h.getHitsplatType()))
			{
				trip.damage(h.getAmount());
			}
		}
		else if (e.getActor() instanceof NPC)
		{
			// On an NPC, "mine" is the player's own hit or miss; heals are counted whoever caused them.
			if (h.isMine())
			{
				trip.hit(e.getActor().getName(), client.getTickCount(), h.getAmount(), attackSpeed());
			}
			else if (h.getHitsplatType() == HitsplatID.HEAL)
			{
				trip.healed(e.getActor().getName(), h.getAmount());
			}
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		if (e.getActor() == client.getLocalPlayer())
		{
			end("died", 0);
			awaitingRespawn = true;
		}
		else if (trip != null && e.getActor() instanceof NPC)
		{
			trip.died(e.getActor().getName(), client.getTickCount());
		}
	}

	/** Once a tick while a character is loaded. */
	void tick()
	{
		if (sink == null || !sink.active())
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (awaitingRespawn)
		{
			// Start counting again once back on your feet, so the items lost on death aren't the next trip's.
			if (client.getBoostedSkillLevel(Skill.HITPOINTS) > 0)
			{
				awaitingRespawn = false;
				fresh(now);
			}
			return;
		}
		if (trip == null)
		{
			fresh(now);
			return;
		}
		trip.tick(client.getTickCount());
		if (bankTouched)
		{
			// Every bank change (opening it, withdrawing, depositing) starts the next trip from what you have now.
			if (trip.hasKills())
			{
				end("banked", client.getBoostedSkillLevel(Skill.HITPOINTS));
			}
			fresh(now);
			return;
		}
		if (itemsChanged)
		{
			itemsChanged = false;
			trip.items(items());
		}
		if (trip.idle(now))
		{
			if (trip.hasKills())
			{
				end("idle", client.getBoostedSkillLevel(Skill.HITPOINTS));
			}
			fresh(now);
		}
	}

	/** Logging out ends the trip; hopping worlds doesn't. */
	void logout()
	{
		end("logged_out", client.getBoostedSkillLevel(Skill.HITPOINTS));
		reset();
	}

	void reset()
	{
		trip = null;
		itemsChanged = false;
		bankTouched = false;
		awaitingRespawn = false;
	}

	private void fresh(long now)
	{
		trip = new BossTrip(now, items());
		itemsChanged = false;
		bankTouched = false;
	}

	private void end(String reason, int hp)
	{
		BossTrip t = trip;
		trip = null;
		if (t == null || !t.hasKills() || sink == null || !sink.allowed("kc", config.sendKc()))
		{
			return;
		}
		if (itemsChanged)
		{
			t.items(items());
		}
		JsonObject d = t.end(reason, System.currentTimeMillis(), hp, client.getBoostedSkillLevel(Skill.PRAYER));
		log.debug("Nib Tracker boss trip kills: {}", d.get("kills"));
		sink.emit("boss_trip", d);
	}

	/** The inventory and worn equipment together, so putting something on or taking it off changes nothing. */
	private Map<Integer, Integer> items()
	{
		Map<Integer, Integer> m = new HashMap<>();
		for (int id : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			ItemContainer c = client.getItemContainer(id);
			if (c != null)
			{
				for (Item i : c.getItems())
				{
					if (i.getId() >= 0 && i.getQuantity() > 0)
					{
						m.merge(i.getId(), i.getQuantity(), Integer::sum);
					}
				}
			}
		}
		return m;
	}

	/** The worn weapon's attack speed in ticks; 4 unarmed. */
	private int attackSpeed()
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		Item weapon = worn == null ? null : worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		ItemStats stats = weapon == null ? null : itemManager.getItemStats(weapon.getId());
		return stats == null || stats.getEquipment() == null ? 4 : stats.getEquipment().getAspeed();
	}

	/** Hitsplats that take the player's hitpoints: hits on them, and poison, venom, disease, bleed and burn. */
	static boolean takesHitpoints(int type)
	{
		switch (type)
		{
			case HitsplatID.DAMAGE_ME:
			case HitsplatID.DAMAGE_ME_CYAN:
			case HitsplatID.DAMAGE_ME_ORANGE:
			case HitsplatID.DAMAGE_ME_YELLOW:
			case HitsplatID.DAMAGE_ME_WHITE:
			case HitsplatID.DAMAGE_ME_POISE:
			case HitsplatID.DAMAGE_MAX_ME:
			case HitsplatID.DAMAGE_MAX_ME_CYAN:
			case HitsplatID.DAMAGE_MAX_ME_ORANGE:
			case HitsplatID.DAMAGE_MAX_ME_YELLOW:
			case HitsplatID.DAMAGE_MAX_ME_WHITE:
			case HitsplatID.DAMAGE_MAX_ME_POISE:
			case HitsplatID.POISON:
			case HitsplatID.VENOM:
			case HitsplatID.DISEASE:
			case HitsplatID.BLEED:
			case HitsplatID.BURN:
				return true;
			default:
				return false;
		}
	}
}
