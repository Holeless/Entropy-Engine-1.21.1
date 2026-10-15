package com.example.curses.effect;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;
import com.example.curses.registry.CursesItems;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Incendiary, the villagers: a villager you have angered throws Molotov Cocktails at you.
 *
 * <p><b>Getting angry:</b> a villager becomes angry at a player when that player hurts it, or when that player hurts
 * an Iron Golem within {@link #GOLEM_ALERT_RANGE} blocks of it (they defend their golem, sight or no sight). The
 * anger lasts {@link #ANGER_TICKS} and is renewed by every further hit. It is not saved: after a restart villagers
 * are calm again.</p>
 *
 * <p><b>Throwing:</b> an angry villager with the player in sight, {@link #MIN_RANGE}-{@link #MAX_RANGE} blocks away,
 * takes a Molotov in its hand (visible, like the item a villager holds when trading), looks at the player for
 * {@link #WINDUP} ticks, then throws it (arm swing and the witch's throw sound). The shatter sound and the fire are
 * handled by {@link IncendiaryHandler}. One throw every {@link #COOLDOWN} ticks per villager.</p>
 *
 * <p>The villager's own AI (it still panics and runs from whoever hurt it) is left alone. Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class AngryVillagerHandler {
    private static final int ANGER_TICKS = 1200;
    private static final double GOLEM_ALERT_RANGE = 24.0;
    private static final int CHECK_INTERVAL = 5;
    private static final double MIN_RANGE = 5.0;   // the fire must not land on the villager itself
    private static final double MAX_RANGE = 20.0;
    private static final float THROW_CHANCE = 0.4F; // per check, while a throw is possible
    private static final int WINDUP = 12;          // ticks the Molotov is held before the throw
    private static final int COOLDOWN = 80;
    private static final int FIRST_DELAY = 20;

    private static final class Anger {
        UUID player;
        long until;
        long readyAt;
        long throwAt; // 0 = not winding up
    }

    // Server thread only.
    private static final Map<Villager, Anger> ANGRY = new WeakHashMap<>();

    private AngryVillagerHandler() {
    }

    private static boolean enabled(ServerLevel level) {
        return CurseData.isActive(level.getServer(), Curses.INCENDIARY);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ANGRY.clear();
    }

    // ---------------------------------------------------------------------------------------------------
    // Getting angry
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onDamaged(LivingDamageEvent.Post event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)
                || !(event.getSource().getEntity() instanceof Player player)) {
            return;
        }
        boolean villager = event.getEntity() instanceof Villager;
        boolean golem = event.getEntity() instanceof IronGolem;
        if ((!villager && !golem) || !enabled(level)) {
            return;
        }
        long now = level.getGameTime();
        if (event.getEntity() instanceof Villager hurt) {
            anger(hurt, player, now);
        } else {
            for (Villager nearby : level.getEntitiesOfClass(Villager.class,
                    event.getEntity().getBoundingBox().inflate(GOLEM_ALERT_RANGE), Villager::isAlive)) {
                anger(nearby, player, now);
            }
        }
    }

    private static void anger(Villager villager, Player player, long now) {
        Anger anger = ANGRY.get(villager);
        if (anger == null) {
            anger = new Anger();
            anger.readyAt = now + FIRST_DELAY;
            ANGRY.put(villager, anger);
        }
        anger.player = player.getUUID();
        anger.until = now + ANGER_TICKS;
    }

    // ---------------------------------------------------------------------------------------------------
    // Throwing
    // ---------------------------------------------------------------------------------------------------

    /** A Molotov left in a hand by an unloaded mid-throw villager is put away when it loads again. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Villager villager) {
            dropMolotov(villager);
        }
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()) {
            return;
        }
        Anger anger = ANGRY.get(villager);
        if (anger == null) {
            return;
        }
        ServerLevel level = (ServerLevel) villager.level();
        long now = level.getGameTime();
        Player player = level.getPlayerByUUID(anger.player);

        if (!villager.isAlive() || now >= anger.until || !enabled(level) || player == null || !player.isAlive()
                || player.isCreative() || player.isSpectator()) {
            dropMolotov(villager);
            ANGRY.remove(villager);
            return;
        }

        if (anger.throwAt > 0) {
            villager.getLookControl().setLookAt(player, 30.0F, 30.0F);
            if (villager.isSleeping() || villager.distanceToSqr(player) > MAX_RANGE * MAX_RANGE) {
                dropMolotov(villager);
                anger.throwAt = 0;
                anger.readyAt = now + COOLDOWN / 2;
            } else if (now >= anger.throwAt) {
                release(villager, level, player, anger, now);
            }
            return;
        }

        if (now < anger.readyAt || (villager.tickCount + villager.getId()) % CHECK_INTERVAL != 0
                || villager.isSleeping() || !villager.getMainHandItem().isEmpty()) {
            return;
        }
        double distSqr = villager.distanceToSqr(player);
        if (distSqr < MIN_RANGE * MIN_RANGE || distSqr > MAX_RANGE * MAX_RANGE || !villager.hasLineOfSight(player)
                || villager.getRandom().nextFloat() >= THROW_CHANCE) {
            return;
        }

        // Wind-up: the Molotov shows in the villager's hands.
        villager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(CursesItems.MOLOTOV.get()));
        villager.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        villager.getLookControl().setLookAt(player, 30.0F, 30.0F);
        anger.throwAt = now + WINDUP;
    }

    private static void release(Villager villager, ServerLevel level, Player player, Anger anger, long now) {
        anger.throwAt = 0;
        Vec3 aim = player.position().add(0.0, 1.0, 0.0);
        if (Siege.lobPotion(villager, level, aim, new ItemStack(CursesItems.MOLOTOV.get()))) {
            villager.swing(InteractionHand.MAIN_HAND);
            anger.readyAt = now + COOLDOWN;
            CursesMod.LOGGER.debug("Incendiary: villager at {} threw a Molotov at a player", villager.blockPosition());
        } else {
            anger.readyAt = now + COOLDOWN / 2; // out of reach: try again soon
        }
        dropMolotov(villager);
    }

    private static void dropMolotov(Villager villager) {
        if (villager.getMainHandItem().is(CursesItems.MOLOTOV.get())) {
            villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }
}
