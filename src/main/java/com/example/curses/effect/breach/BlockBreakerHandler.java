package com.example.curses.effect.breach;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Breach, the hands (and Tools, the tools): undead and piglins (not skeletons) break blocks to get to you.
 *
 * <p><b>Who:</b> every undead mob that walks (zombies, husks, zombie villagers, zombified piglins, zoglins...) and
 * every piglin and brute. Skeletons (strays, wither skeletons, boggeds), phantoms, the Wither and undead horses
 * are left out. A rider from Beast of Burden counts too: it breaks blocks from the saddle, and since it is taller
 * on a mount it clears a 3-block-high opening.</p>
 *
 * <p><b>Which curse:</b> with Breach on, all of them dig bare-handed. With Tools on, a mob that holds a pickaxe or an
 * axe (see {@code ToolsHandler}) digs too, even if Breach is off, and it is much faster on the blocks its tool is
 * made for (see {@link #miningTicks}).</p>
 *
 * <p><b>When:</b> the mob has a player as its target (Omniscience hands them out), is within
 * {@link #HUNT_RANGE} blocks, has hardly moved for about 40 ticks, and a path search says it cannot walk to the
 * player. Then it looks at the blocks in front of it, toward the player: a level approach takes the blocks in its
 * way, a player above takes the blocks overhead and in front (so it can climb), a player below takes the floor in
 * front (so it can drop). It mines the first solid block with a crack animation and hit sounds, then the block
 * breaks and drops like a normal one. Time depends on hardness: dirt about 1.7 s, stone 3.2 s, planks 4 s by hand;
 * stone about 1 s with a stone pickaxe.</p>
 *
 * <p><b>Limits:</b> never breaks bedrock, obsidian and other things with a blast resistance of 100 or more, anything
 * harder than 10, or anything with a block entity (chests, furnaces, beds, spawners). Respects the
 * {@code mobGriefing} game rule. At most {@link #BREAK_BUDGET} blocks break per server tick and
 * {@link #PATH_BUDGET} path searches are made per tick, so a crowd cannot lag the server. Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class BlockBreakerHandler {
    private static final int CHECK_INTERVAL = 10;
    private static final double HUNT_RANGE = 40.0;
    private static final double MIN_DIST = 2.5;
    private static final int STALL_PERIOD = 20;          // ticks between position samples
    private static final int STALL_NEEDED = 2;           // consecutive "has not moved" samples
    private static final double STALL_MOVE_SQR = 0.35 * 0.35;
    private static final int PATH_BUDGET = 2;            // path searches per server tick
    private static final int BREAK_BUDGET = 4;           // blocks broken per server tick
    private static final int RETRY_CLEAR = 40;           // nothing solid in front: look again later
    private static final int RETRY_HARD = 80;            // something unbreakable in front
    private static final int BASE_TICKS = 20;
    private static final int TOOL_BASE_TICKS = 10;       // swing-up time with the right tool
    private static final float TICKS_PER_HARDNESS = 30.0F;
    private static final double REACH_SQR = 3.5 * 3.5;

    private static final class State {
        boolean sampled;
        long sampleAt;
        double x;
        double y;
        double z;
        int still;
        long retryAt;
        BlockPos mining;
        int progress;
        int needed;
        int stage = -1;
    }

    // Server thread only.
    private static final Map<Mob, State> STATES = new WeakHashMap<>();
    private static int pathBudget;
    private static int breakBudget;

    private BlockBreakerHandler() {
    }

    @SubscribeEvent
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        pathBudget = PATH_BUDGET;
        breakBudget = BREAK_BUDGET;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.clear();
        pathBudget = 0;
        breakBudget = 0;
    }

    /** Can this mob break blocks by hand? (Undead and piglins that walk, but not skeletons.) */
    private static boolean canBreak(Mob mob) {
        if (mob instanceof AbstractSkeleton || mob instanceof Phantom || mob instanceof WitherBoss
                || mob instanceof AbstractHorse) {
            return false;
        }
        if (!(mob.getType().is(EntityTypeTags.UNDEAD) || mob instanceof AbstractPiglin)) {
            return false;
        }
        return mob.getNavigation() instanceof GroundPathNavigation;
    }

    /** The pickaxe or axe the mob digs with (Tools curse), or an empty stack if it has none or the curse is off. */
    private static ItemStack diggingTool(ServerLevel level, Mob mob) {
        ItemStack held = mob.getMainHandItem();
        if ((held.is(ItemTags.PICKAXES) || held.is(ItemTags.AXES))
                && CurseData.isActive(level.getServer(), Curses.TOOLS)) {
            return held;
        }
        return ItemStack.EMPTY;
    }

    /** Ticks to mine this block: fast with a tool that suits it, the bare-hand time otherwise. */
    private static int miningTicks(ServerLevel level, BlockPos pos, BlockState block, ItemStack tool) {
        float hardness = Math.max(0.0F, Math.min(10.0F, block.getDestroySpeed(level, pos)));
        if (!tool.isEmpty()) {
            float speed = tool.getDestroySpeed(block);
            if (speed > 1.0F) { // 1.0 means the tool is no better than a hand on this block
                return TOOL_BASE_TICKS + (int) Math.ceil(hardness * TICKS_PER_HARDNESS / speed);
            }
        }
        return BASE_TICKS + (int) (hardness * TICKS_PER_HARDNESS);
    }

    /** A block a hand can break: solid enough to matter, not too hard, not a container. Also used by the Endermen. */
    static boolean breakable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.hasBlockEntity()) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        return hardness >= 0.0F && hardness <= 10.0F && state.getBlock().getExplosionResistance() < 100.0F;
    }

    private static boolean obstacle(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && !level.isOutsideBuildHeight(pos)
                && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide() || !canBreak(mob)) {
            return;
        }
        State state = STATES.get(mob);
        boolean mining = state != null && state.mining != null;
        if (!mining && (mob.tickCount + mob.getId()) % CHECK_INTERVAL != 0) {
            return;
        }
        if (!mob.isAlive()) {
            STATES.remove(mob);
            return;
        }
        ServerLevel level = (ServerLevel) mob.level();
        ItemStack tool = diggingTool(level, mob);
        boolean breach = CurseData.isActive(level.getServer(), Curses.BREACH);
        if ((!breach && tool.isEmpty()) || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            if (mining) {
                cancel(level, mob, state);
            }
            return;
        }
        if (state == null) {
            state = new State();
            STATES.put(mob, state);
        }
        if (mining) {
            continueMining(level, mob, state);
        } else {
            decide(level, mob, state, tool);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Deciding to mine
    // ---------------------------------------------------------------------------------------------------

    private static void decide(ServerLevel level, Mob mob, State state, ItemStack tool) {
        if (!(targetOf(mob) instanceof Player player) || !player.isAlive() || player.isCreative()
                || player.isSpectator() || player.level() != level) {
            state.still = 0;
            return;
        }
        // A mounted rider is stuck when its mount is.
        Entity body = mob.getVehicle() != null ? mob.getVehicle() : mob;
        double distSqr = body.distanceToSqr(player);
        if (distSqr > HUNT_RANGE * HUNT_RANGE || distSqr < MIN_DIST * MIN_DIST) {
            state.still = 0;
            return;
        }

        long now = level.getGameTime();
        if (!state.sampled) {
            sample(state, body, now);
            return;
        }
        if (now - state.sampleAt >= STALL_PERIOD) {
            double dx = body.getX() - state.x;
            double dy = body.getY() - state.y;
            double dz = body.getZ() - state.z;
            state.still = dx * dx + dy * dy + dz * dz < STALL_MOVE_SQR ? state.still + 1 : 0;
            sample(state, body, now);
        }
        if (state.still < STALL_NEEDED || now < state.retryAt || pathBudget <= 0) {
            return;
        }

        pathBudget--;
        Path path = mob.getNavigation().createPath(player, 0);
        if (path != null && path.canReach()) {
            state.still = 0; // it can walk there, something else is holding it up
            return;
        }

        for (BlockPos pos : candidates(body, mob, player)) {
            if (!obstacle(level, pos)) {
                continue;
            }
            BlockState block = level.getBlockState(pos);
            if (!breakable(level, pos, block)) {
                state.retryAt = now + RETRY_HARD;
                return;
            }
            state.mining = pos.immutable();
            state.progress = 0;
            state.stage = -1;
            state.needed = miningTicks(level, pos, block, tool);
            return;
        }
        state.retryAt = now + RETRY_CLEAR;
    }

    private static void sample(State state, Entity body, long now) {
        state.sampled = true;
        state.sampleAt = now;
        state.x = body.getX();
        state.y = body.getY();
        state.z = body.getZ();
    }

    /** Blocks to try, in order: whichever is the first solid one is what gets mined. */
    private static List<BlockPos> candidates(Entity body, Mob rider, Player player) {
        Vec3 to = player.position().subtract(body.position());
        Direction dir = Math.abs(to.x) > Math.abs(to.z)
                ? (to.x > 0 ? Direction.EAST : Direction.WEST)
                : (to.z > 0 ? Direction.SOUTH : Direction.NORTH);
        int height = body == rider ? 2 : 3; // a rider on a horse is about three blocks tall
        BlockPos base = body.blockPosition();
        BlockPos front = base.relative(dir);

        List<BlockPos> list = new ArrayList<>(5);
        double dy = player.getY() - body.getY();
        if (dy >= 2.0) {
            // Climbing: room overhead, then the blocks above the step in front of it.
            list.add(base.above(height));
            for (int i = 1; i <= height; i++) {
                list.add(front.above(i));
            }
        } else {
            for (int i = 0; i < height; i++) {
                list.add(front.above(i));
            }
            if (dy <= -2.0) {
                list.add(front.below()); // dropping: the floor in front of it
            }
        }
        return list;
    }

    // ---------------------------------------------------------------------------------------------------
    // Mining
    // ---------------------------------------------------------------------------------------------------

    private static void continueMining(ServerLevel level, Mob mob, State state) {
        BlockPos pos = state.mining;
        if (!level.hasChunkAt(pos)) {
            cancel(level, mob, state);
            return;
        }
        BlockState block = level.getBlockState(pos);
        Entity body = mob.getVehicle() != null ? mob.getVehicle() : mob;
        if (!breakable(level, pos, block) || targetOf(mob) == null
                || body.distanceToSqr(Vec3.atCenterOf(pos)) > REACH_SQR) {
            cancel(level, mob, state);
            return;
        }

        state.progress++;
        int stage = Math.min(9, state.progress * 10 / Math.max(1, state.needed));
        if (stage != state.stage) {
            state.stage = stage;
            level.destroyBlockProgress(mob.getId(), pos, stage);
        }
        if (state.progress % 4 == 0) {
            level.playSound(null, pos, block.getSoundType().getHitSound(), SoundSource.HOSTILE, 0.6F, 0.8F);
        }
        if (state.progress % 6 == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        mob.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        if (state.progress >= state.needed && breakBudget > 0) {
            breakBudget--;
            level.destroyBlockProgress(mob.getId(), pos, -1);
            level.destroyBlock(pos, true, mob);
            state.mining = null;
            state.stage = -1;
            state.still = STALL_NEEDED; // carry straight on to the next block if it still cannot get through
        }
    }

    private static void cancel(ServerLevel level, Mob mob, State state) {
        if (state.mining != null) {
            level.destroyBlockProgress(mob.getId(), state.mining, -1);
        }
        state.mining = null;
        state.stage = -1;
        state.retryAt = level.getGameTime() + 20;
    }

    /** The mob's attack target. Piglins keep theirs in the brain instead of the usual target field. */
    private static LivingEntity targetOf(Mob mob) {
        LivingEntity target = mob.getTarget();
        // Only piglins have this memory registered; asking any other brain for it throws.
        if (target == null && mob instanceof AbstractPiglin
                && mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
            target = mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        }
        return target;
    }
}
