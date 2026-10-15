package com.example.curses.effect;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Inconspicuous: creepers have a chance to spawn 80% transparent, and those creepers make no sound when they
 * ignite.
 *
 * <p><b>The marker.</b> Each creeper rolls once (remembered in its saved data). A creeper that wins the roll gets a
 * permanent attribute modifier with a fixed id and a value of 0 on its max health. It changes nothing in play, but
 * attribute modifiers are saved with the entity and sent to clients, so this is how the client renderer (see
 * {@code InconspicuousCreeperRenderer}) knows which creepers to draw see-through, without any custom network
 * packet. {@link #isInconspicuous} reads it on either side.</p>
 *
 * <p><b>Silent ignition.</b> The hiss is played by the creeper's own tick the moment its fuse starts (swell goes
 * from 0 upwards). Just before that tick, a marked creeper that is about to start its fuse is made silent, and
 * the flag is put back right after, so only the hiss is lost: hurt, death and explosion sounds are untouched.</p>
 *
 * <p>The marker belongs to the creeper, not to the curse: creepers that already rolled keep it if the curse is
 * lifted. Server only (the renderer is the client half).</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class InconspicuousHandler {
    /** Chance for each creeper to be inconspicuous. */
    public static final float CHANCE = 0.25F;

    private static final ResourceLocation MARKER_ID = CursesMod.id("inconspicuous");
    private static final String KEY_ROLLED = "curses_inconspicuous_rolled";

    // Server thread only: creepers this handler has silenced for the current tick.
    private static final Set<Creeper> MUTED = Collections.newSetFromMap(new WeakHashMap<>());

    private InconspicuousHandler() {
    }

    /** Does this creeper carry the Inconspicuous marker? Safe on both client and server. */
    public static boolean isInconspicuous(LivingEntity creeper) {
        AttributeInstance health = creeper.getAttribute(Attributes.MAX_HEALTH);
        return health != null && health.hasModifier(MARKER_ID);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Creeper creeper)) {
            return;
        }
        CompoundTag data = creeper.getPersistentData();
        if (data.getBoolean(KEY_ROLLED) || !CurseData.isActive(level.getServer(), Curses.INCONSPICUOUS)) {
            return;
        }
        data.putBoolean(KEY_ROLLED, true);
        if (creeper.getRandom().nextFloat() >= CHANCE) {
            return;
        }

        AttributeInstance health = creeper.getAttribute(Attributes.MAX_HEALTH);
        if (health != null && !health.hasModifier(MARKER_ID)) {
            // +0 max health: no effect on the creeper, but saved with it and synced to clients.
            health.addPermanentModifier(new AttributeModifier(MARKER_ID, 0.0, AttributeModifier.Operation.ADD_VALUE));
            CursesMod.LOGGER.debug("Inconspicuous: creeper at {} is see-through and silent", creeper.blockPosition());
        }
    }

    @SubscribeEvent
    public static void onTickPre(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof Creeper creeper) || creeper.level().isClientSide()) {
            return;
        }
        // If an earlier Post never ran (another mod cancelled that tick), undo the mute now.
        if (MUTED.remove(creeper)) {
            creeper.setSilent(false);
        }
        // The fuse hiss plays at the start of this tick if the fuse is about to start.
        if (!creeper.isAlive() || creeper.swell != 0 || creeper.isSilent()) {
            return;
        }
        if ((creeper.getSwellDir() > 0 || creeper.isIgnited()) && isInconspicuous(creeper)) {
            creeper.setSilent(true);
            MUTED.add(creeper);
        }
    }

    @SubscribeEvent
    public static void onTickPost(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof Creeper creeper && MUTED.remove(creeper)) {
            creeper.setSilent(false);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MUTED.clear();
    }
}
