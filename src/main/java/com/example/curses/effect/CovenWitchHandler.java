package com.example.curses.effect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;
import com.example.curses.registry.CursesItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Coven, the witches' tricks (the spawning itself is in {@link CovenHandler}). All of it is server only and does
 * nothing while the Coven curse is off.
 *
 * <ul>
 *   <li><b>Self-levitation.</b> A witch that is hunting a player who is well above her (a tower, a pillar, a
 *       ledge) and has no walking route to them drinks a Levitation potion (the mod's own
 *       {@code curses:potion_of_levitation} item for the normal drink time) and then <i>flies</i> to them. The flight
 *       is planned before she drinks, see {@link #plan}:
 *       <ul>
 *         <li>She picks a <b>landing spot</b> on a floor near the player (the top of their tower), or, if there is
 *             none, a <b>hover spot</b> 2.5-7 blocks from them with a clear line of sight (a pillar, a sealed roof).</li>
 *         <li>The route there is tested against the real blocks with her own body size: straight, up-then-across,
 *             across-then-up, or up and over the top (6, 14 or 24 blocks above). She only drinks if one of those is
 *             clear, so she never wastes the potion under a ceiling.</li>
 *         <li>While flying she steers herself in 3D along the route (not just straight up), re-plans if she gets
 *             stuck or if the player moves away, and keeps her target on the player so she throws potions on the way.</li>
 *         <li>At a landing spot she drops the last bit with Slow Falling and fights from there. At a hover spot she
 *             hangs in the air throwing potions for up to {@link #HOVER_TICKS} ticks (looking every few seconds for a
 *             floor to land on), then comes down gently.</li>
 *       </ul></li>
 *   <li><b>Curse cleansing.</b> A witch with a player in sight 5-14 blocks away lobs a Milk Potion when the
 *       player has more positive effects than negative ones. The potion is the mod's own
 *       {@code curses:splash_potion_of_milk} item (your texture), tagged so that on impact it clears every effect from
 *       players in the splash radius (what milk does). Nothing else is touched.</li>
 *   <li><b>Ally support.</b> A witch that sees a burning hostile mob nearby throws a vanilla Splash Potion of Water
 *       at it (this puts the fire out). A moment later, if that mob is low on health, she most likely follows with
 *       a Splash Potion of Regeneration. Neither is ever thrown if a player is anywhere near the splash, and
 *       mobs that water would hurt (Endermen, Blazes...) are never targeted.</li>
 * </ul>
 *
 * <p>The witch's normal potion throwing (poison, harming, the occasional buff for other mobs) is untouched; these
 * are extra actions that are checked twice a second per witch, with cooldowns.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class CovenWitchHandler {
    private static final String KEY_MILK = "curses_coven_milk";

    private static final int CHECK_INTERVAL = 10;          // ticks between decisions per witch

    // Levitation: deciding
    private static final double LEV_MIN_RISE = 2.5;        // the player must be at least this far above
    private static final double LEV_MAX_RISE = 40.0;
    private static final double LEV_MAX_HORIZONTAL = 32.0; // sideways distance limit
    private static final double LEV_SEARCH = 32.0;         // looks for a player this far when it has no target
    private static final float LEV_CHANCE = 0.5F;          // per check
    private static final int LEV_COOLDOWN = 400;
    private static final int LEV_RECHECK = 100;            // after finding the player walkable, look again later
    private static final int LEV_NO_ROUTE = 100;           // no clear flight right now: look again soon
    private static final int FLIGHT_START_GRACE = 80;      // drink never finished: forget it after this

    // Levitation: planning
    private static final int CANDIDATES = 28;              // landing / hover spots tried around the player
    private static final double SPOT_MIN_RADIUS = 2.5;
    private static final double SPOT_MAX_RADIUS = 7.0;
    private static final int MAX_ROUTE_TESTS = 4;          // spots (nearest first) whose routes are really swept
    private static final double SWEEP_STEP = 0.4;
    private static final double[] OVER_TOP = {6.0, 14.0, 24.0};
    private static final int BUILD_MARGIN = 6;

    // Levitation: flying
    private static final double CRUISE = 0.24;             // blocks per tick (about 5 blocks a second)
    private static final double REACH = 1.0;               // a waypoint counts as reached this close
    private static final double REACH_LAST = 0.6;
    private static final int MAX_FLIGHT = 900;             // ticks, the whole flight
    private static final int HOVER_TICKS = 500;
    private static final int REPLAN_INTERVAL = 60;
    private static final double DRIFT_SQR = 6.0 * 6.0;     // the player moved this far from where the route aims
    private static final int STUCK_PERIOD = 20;
    private static final double STUCK_MOVE_SQR = 0.5 * 0.5;
    private static final int STUCK_LIMIT = 2;              // stuck periods in a row before a re-plan
    private static final int MAX_REPLAN_FAILS = 2;
    private static final double LAND_DRIFT = 0.1;          // sideways blocks per tick while gently falling

    // Milk
    private static final double MILK_MIN = 5.0;
    private static final double MILK_MAX = 14.0;
    private static final float MILK_CHANCE = 0.4F;         // per check
    private static final int MILK_COOLDOWN = 300;          // per witch
    private static final int MILK_PLAYER_GRACE = 160;      // per player: no second milk this soon, whichever witch
    private static final int MILK_COLOR = 0xFFFFFF;        // splash particle colour on impact

    // Ally support
    private static final double ALLY_RANGE = 14.0;
    private static final double ALLY_MIN = 3.0;
    private static final float ALLY_CHANCE = 0.6F;         // per check, while a burning ally is in sight
    private static final int ALLY_COOLDOWN = 100;
    private static final int FOLLOW_DELAY = 12;            // ticks between the water and the regeneration check
    private static final float REGEN_CHANCE = 0.8F;        // "most likely"
    private static final float LOW_HEALTH = 0.5F;          // regeneration is for allies at or below this share of health
    private static final double SPLASH_RADIUS = 4.0;       // vanilla splash radius
    private static final double PLAYER_CLEARANCE = SPLASH_RADIUS + 0.5;

    private enum Stage {
        DRINK, FLY, HOVER, LAND
    }

    /** Where she is going: waypoints (feet positions, the first leg starts where she stands), and whether the last one is a floor. */
    private record Plan(List<Vec3> route, boolean landing) {
    }

    private static final class Flight {
        final Player player;
        final long started;
        Stage stage = Stage.DRINK;
        List<Vec3> route = List.of();
        int leg;
        boolean landing;
        Vec3 hoverPoint;
        Vec3 anchor;          // where the player was when the route was made
        long hoverSince;
        long nextReplan;
        long sampleAt;
        Vec3 samplePos;
        int stuck;
        int replanFails;

        Flight(Player player, long started) {
            this.player = player;
            this.started = started;
            this.anchor = player.position();
        }

        void adopt(Plan plan, Player target) {
            this.route = plan.route();
            this.leg = 0;
            this.landing = plan.landing();
            this.hoverPoint = null;
            this.anchor = target.position();
            this.stuck = 0;
            this.stage = Stage.FLY;
        }
    }

    private static final class Follow {
        final Mob ally;
        final long due;

        Follow(Mob ally, long due) {
            this.ally = ally;
            this.due = due;
        }
    }

    // Server thread only.
    private static final Map<Witch, Flight> FLIGHTS = new WeakHashMap<>();
    private static final Map<Witch, Follow> FOLLOWS = new WeakHashMap<>();
    private static final Map<Witch, Long> LEV_READY = new WeakHashMap<>();
    private static final Map<Witch, Long> MILK_READY = new WeakHashMap<>();
    private static final Map<Witch, Long> ALLY_READY = new WeakHashMap<>();
    private static final Map<Player, Long> MILKED = new WeakHashMap<>();
    /** True while a milk potion is being thrown, so the join event can tag it. */
    private static boolean throwingMilk;

    private CovenWitchHandler() {
    }

    private static boolean enabled(ServerLevel level) {
        return CurseData.isActive(level.getServer(), Curses.COVEN);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        FLIGHTS.clear();
        FOLLOWS.clear();
        LEV_READY.clear();
        MILK_READY.clear();
        ALLY_READY.clear();
        MILKED.clear();
        throwingMilk = false;
    }

    private static boolean valid(Witch witch, Player player) {
        return player.isAlive() && !player.isSpectator() && !player.isCreative() && player.level() == witch.level();
    }

    // ---------------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onWitchTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Witch witch) || witch.level().isClientSide() || !witch.isAlive()) {
            return;
        }
        ServerLevel level = (ServerLevel) witch.level();
        long now = level.getGameTime();

        Flight flight = FLIGHTS.get(witch);
        if (flight != null) {
            tickFlight(level, witch, flight, now);
        }
        Follow follow = FOLLOWS.get(witch);
        if (follow != null && now >= follow.due) {
            FOLLOWS.remove(witch);
            tryRegeneration(witch, level, follow.ally);
        }

        if ((witch.tickCount + witch.getId()) % CHECK_INTERVAL != 0) {
            return;
        }
        if (witch.isDrinkingPotion() || witch.isUsingItem() || witch.isPassenger() || !enabled(level)) {
            return;
        }
        if (tryAllySupport(witch, level, now) || tryMilk(witch, level, now)) {
            return;
        }
        tryLevitation(witch, level, now);
    }

    // ---------------------------------------------------------------------------------------------------
    // Self-levitation: deciding
    // ---------------------------------------------------------------------------------------------------

    private static void tryLevitation(Witch witch, ServerLevel level, long now) {
        if (FLIGHTS.containsKey(witch) || !witch.onGround() || !witch.getMainHandItem().isEmpty()) {
            return;
        }
        Long ready = LEV_READY.get(witch);
        if (ready != null && now < ready) {
            return;
        }
        Player player = witch.getTarget() instanceof Player target ? target : level.getNearestPlayer(witch, LEV_SEARCH);
        if (player == null || !valid(witch, player)) {
            return;
        }
        double rise = player.getY() - witch.getY();
        double dx = player.getX() - witch.getX();
        double dz = player.getZ() - witch.getZ();
        if (rise < LEV_MIN_RISE || rise > LEV_MAX_RISE || Math.sqrt(dx * dx + dz * dz) > LEV_MAX_HORIZONTAL) {
            return;
        }
        if (witch.getRandom().nextFloat() >= LEV_CHANCE) {
            return;
        }
        // The path search is the costly part, so it only happens after the roll.
        if (Siege.canWalkTo(witch, player)) {
            LEV_READY.put(witch, now + LEV_RECHECK);
            return;
        }
        // She only drinks if there is a flight that is clear of blocks all the way, so she never wastes the potion
        // under a ceiling.
        Plan plan = plan(level, witch, player);
        if (plan == null) {
            LEV_READY.put(witch, now + LEV_NO_ROUTE);
            return;
        }
        LEV_READY.put(witch, now + LEV_COOLDOWN);

        // The witch holds the mod's own levitation potion item (your texture) for the normal drinking time. Vanilla
        // clears her hand when the drink ends but only applies effects for a real potion, so tickFlight applies
        // the Levitation itself at that moment.
        ItemStack potion = new ItemStack(CursesItems.LEVITATION_POTION.get());
        witch.setItemSlot(EquipmentSlot.MAINHAND, potion);
        witch.setUsingItem(true);
        witch.usingTime = potion.getUseDuration(witch);
        witch.getNavigation().stop();
        level.playSound(null, witch.getX(), witch.getY(), witch.getZ(), SoundEvents.WITCH_DRINK, witch.getSoundSource(),
                1.0F, 0.8F + witch.getRandom().nextFloat() * 0.4F);

        Flight flight = new Flight(player, now);
        flight.route = plan.route();
        flight.landing = plan.landing();
        FLIGHTS.put(witch, flight);
        CursesMod.LOGGER.debug("Coven: witch at {} drinks levitation to reach a player {} blocks above ({} route)",
                witch.blockPosition(), (int) rise, plan.landing() ? "landing" : "hover");
    }

    // ---------------------------------------------------------------------------------------------------
    // Self-levitation: planning
    // ---------------------------------------------------------------------------------------------------

    /**
     * A flight from where the witch stands to a spot near the player, or null if no clear one exists. Floors near
     * the player are preferred (she lands and fights there); otherwise spots in the air with a line of sight.
     */
    private static Plan plan(ServerLevel level, Witch witch, Player player) {
        Vec3 start = witch.position();
        RandomSource random = witch.getRandom();
        Vec3 feet = player.position();
        Vec3 eye = player.getEyePosition();
        int playerY = (int) Math.floor(feet.y);

        List<Vec3> landings = new ArrayList<>();
        List<Vec3> hovers = new ArrayList<>();
        for (int i = 0; i < CANDIDATES; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = SPOT_MIN_RADIUS + random.nextDouble() * (SPOT_MAX_RADIUS - SPOT_MIN_RADIUS);
            int x = (int) Math.floor(feet.x + Math.cos(angle) * radius);
            int z = (int) Math.floor(feet.z + Math.sin(angle) * radius);

            Vec3 floor = findFloor(level, witch, x, playerY, z);
            if (floor != null) {
                landings.add(floor);
            }
            Vec3 hover = new Vec3(x + 0.5, feet.y + 0.5 + random.nextDouble() * 2.0, z + 0.5);
            if (fits(level, witch, hover) && sees(level, witch, hover, eye)) {
                hovers.add(hover);
            }
        }
        Comparator<Vec3> nearest = Comparator.comparingDouble(v -> v.distanceToSqr(start));
        landings.sort(nearest);
        hovers.sort(nearest);

        Plan plan = firstRoute(level, witch, start, landings, true);
        return plan != null ? plan : firstRoute(level, witch, start, hovers, false);
    }

    private static Plan firstRoute(ServerLevel level, Witch witch, Vec3 start, List<Vec3> goals, boolean landing) {
        for (int i = 0; i < Math.min(goals.size(), MAX_ROUTE_TESTS); i++) {
            List<Vec3> route = route(level, witch, start, goals.get(i));
            if (route != null) {
                return new Plan(route, landing);
            }
        }
        return null;
    }

    /** The first of a few simple routes whose every leg is free for the witch's body, or null. */
    private static List<Vec3> route(ServerLevel level, Witch witch, Vec3 start, Vec3 goal) {
        List<List<Vec3>> options = new ArrayList<>();
        options.add(List.of(start, goal));                                                   // straight
        options.add(List.of(start, new Vec3(start.x, goal.y, start.z), goal));               // up, then across
        options.add(List.of(start, new Vec3(goal.x, start.y, goal.z), goal));                // across, then up
        for (double over : OVER_TOP) {                                                       // up and over the top
            double y = Math.min(Math.max(start.y, goal.y) + over, level.getMaxBuildHeight() - BUILD_MARGIN);
            options.add(List.of(start, new Vec3(start.x, y, start.z), new Vec3(goal.x, y, goal.z), goal));
        }
        for (List<Vec3> option : options) {
            boolean clear = true;
            for (int i = 1; i < option.size() && clear; i++) {
                clear = sweep(level, witch, option.get(i - 1), option.get(i));
            }
            if (clear) {
                return new ArrayList<>(option.subList(1, option.size()));
            }
        }
        return null;
    }

    /** Can the witch's body move in a straight line from a to b without touching a block? (Unloaded chunks count as blocked.) */
    private static boolean sweep(ServerLevel level, Witch witch, Vec3 a, Vec3 b) {
        Vec3 d = b.subtract(a);
        int steps = Math.max(1, (int) Math.ceil(d.length() / SWEEP_STEP));
        EntityDimensions size = witch.getDimensions(witch.getPose());
        for (int i = 1; i <= steps; i++) {
            Vec3 p = a.add(d.scale((double) i / steps));
            BlockPos pos = BlockPos.containing(p);
            if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos) || !level.noCollision(witch, size.makeBoundingBox(p))) {
                return false;
            }
        }
        return true;
    }

    /** Is there room for the witch's body with its feet here? */
    private static boolean fits(ServerLevel level, Witch witch, Vec3 feet) {
        BlockPos pos = BlockPos.containing(feet);
        return level.hasChunkAt(pos) && !level.isOutsideBuildHeight(pos)
                && level.noCollision(witch, witch.getDimensions(witch.getPose()).makeBoundingBox(feet));
    }

    /** Could a witch hovering here see the player's eyes? */
    private static boolean sees(ServerLevel level, Witch witch, Vec3 feet, Vec3 playerEye) {
        Vec3 eye = feet.add(0.0, 1.6, 0.0);
        return level.clip(new ClipContext(eye, playerEye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, witch))
                .getType() == HitResult.Type.MISS;
    }

    /** A floor she can stand on in this column, from 2 blocks above the player's feet down to 3 below, or null. */
    private static Vec3 findFloor(ServerLevel level, Witch witch, int x, int playerY, int z) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos feet = new BlockPos(x, playerY + dy, z);
            if (!level.hasChunkAt(feet)) {
                return null;
            }
            BlockPos below = feet.below();
            BlockState floor = level.getBlockState(below);
            if (!floor.isFaceSturdy(level, below, Direction.UP) || !floor.getFluidState().isEmpty()
                    || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CACTUS) || floor.is(BlockTags.CAMPFIRES)) {
                continue;
            }
            Vec3 spot = Vec3.atBottomCenterOf(feet);
            if (fits(level, witch, spot)) {
                return spot;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------
    // Self-levitation: flying
    // ---------------------------------------------------------------------------------------------------

    private static void tickFlight(ServerLevel level, Witch witch, Flight flight, long now) {
        Player player = flight.player;
        if (!enabled(level) || !valid(witch, player) || now - flight.started > MAX_FLIGHT) {
            endFlight(witch);
            return;
        }

        // The drink: wait for vanilla to finish it, then apply the levitation the potion stands for.
        if (flight.stage == Stage.DRINK) {
            if (witch.isDrinkingPotion() || now <= flight.started + 1) {
                if (now - flight.started > FLIGHT_START_GRACE) {
                    clearPotion(witch);
                    FLIGHTS.remove(witch); // the drink never finished
                }
                return;
            }
            clearPotion(witch);
            flight.stage = Stage.FLY;
            flight.sampleAt = now;
            flight.samplePos = witch.position();
            flight.nextReplan = now + REPLAN_INTERVAL;
        }

        witch.resetFallDistance();
        witch.getLookControl().setLookAt(player, 30.0F, 30.0F);

        if (flight.stage == Stage.LAND) {
            tickLanding(witch, flight);
            return;
        }

        // Flying or hovering: the witch is steered here, her own pathing is switched off, her potions stay on.
        witch.getNavigation().stop();
        keepLevitating(witch);
        if (witch.getTarget() != player) {
            witch.setTarget(player);
        }
        Vec3 pos = witch.position();

        if (flight.stage == Stage.HOVER && now - flight.hoverSince > HOVER_TICKS) {
            flight.landing = false;
            startLanding(witch, flight);
            return;
        }

        // Re-plan: the player moved away from where the route aims, or (hovering) look for a floor to land on.
        if (now >= flight.nextReplan) {
            flight.nextReplan = now + REPLAN_INTERVAL;
            boolean drifted = player.position().distanceToSqr(flight.anchor) > DRIFT_SQR;
            if (drifted || flight.stage == Stage.HOVER) {
                Plan plan = plan(level, witch, player);
                if (plan != null && (drifted || plan.landing())) {
                    flight.adopt(plan, player);
                }
            }
        }

        // Stuck (a ceiling, a wall the route did not account for): find another way, or give up.
        if (flight.stage == Stage.FLY && now - flight.sampleAt >= STUCK_PERIOD) {
            flight.stuck = pos.distanceToSqr(flight.samplePos) < STUCK_MOVE_SQR ? flight.stuck + 1 : 0;
            flight.sampleAt = now;
            flight.samplePos = pos;
            if (flight.stuck >= STUCK_LIMIT) {
                flight.stuck = 0;
                Plan plan = plan(level, witch, player);
                if (plan != null) {
                    flight.adopt(plan, player);
                } else if (++flight.replanFails >= MAX_REPLAN_FAILS) {
                    flight.landing = false;
                    startLanding(witch, flight);
                    return;
                }
            }
        }

        if (flight.stage == Stage.HOVER) {
            steerHover(witch, flight.hoverPoint);
            return;
        }

        // Fly the route.
        Vec3 waypoint = flight.route.get(flight.leg);
        boolean last = flight.leg == flight.route.size() - 1;
        double reach = last ? REACH_LAST : REACH;
        if (pos.distanceToSqr(waypoint) < reach * reach) {
            if (!last) {
                flight.leg++;
                waypoint = flight.route.get(flight.leg);
            } else if (flight.landing) {
                startLanding(witch, flight);
                return;
            } else {
                flight.stage = Stage.HOVER;
                flight.hoverPoint = waypoint;
                flight.hoverSince = now;
                steerHover(witch, waypoint);
                return;
            }
        }
        steer(witch, waypoint);
    }

    /** Pulls the witch toward {@code target} at cruise speed (easing velocity, not snapping it). */
    private static void steer(Witch witch, Vec3 target) {
        Vec3 to = target.subtract(witch.position());
        double dist = to.length();
        if (dist < 1.0E-3) {
            return;
        }
        Vec3 want = dist > CRUISE ? to.scale(CRUISE / dist) : to;
        Vec3 motion = witch.getDeltaMovement();
        witch.setDeltaMovement(motion.add(want.subtract(motion).scale(0.3)));
    }

    /** Holds the witch in the air at {@code point}. */
    private static void steerHover(Witch witch, Vec3 point) {
        Vec3 want = point.subtract(witch.position()).scale(0.25);
        double length = want.length();
        if (length > 0.2) {
            want = want.scale(0.2 / length);
        }
        Vec3 motion = witch.getDeltaMovement();
        witch.setDeltaMovement(motion.add(want.subtract(motion).scale(0.3)));
    }

    /** The levitation effect is only for show now (the witch is steered directly), but it keeps gravity off and shows the swirl. */
    private static void keepLevitating(Witch witch) {
        MobEffectInstance effect = witch.getEffect(MobEffects.LEVITATION);
        if (effect == null || effect.getDuration() < 20) {
            witch.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 60, 0));
        }
    }

    private static void startLanding(Witch witch, Flight flight) {
        flight.stage = Stage.LAND;
        witch.setDeltaMovement(witch.getDeltaMovement().scale(0.3));
        settle(witch);
    }

    /** Coming down gently (after a landing spot was reached, or when the hover is over). */
    private static void tickLanding(Witch witch, Flight flight) {
        if (witch.onGround() || witch.isInWater()) {
            FLIGHTS.remove(witch);
            return;
        }
        if (!flight.landing) {
            Vec3 to = flight.player.position().subtract(witch.position());
            double dist = Math.sqrt(to.x * to.x + to.z * to.z);
            if (dist > 1.5) {
                Vec3 motion = witch.getDeltaMovement();
                witch.setDeltaMovement(motion.x + (to.x / dist * LAND_DRIFT - motion.x) * 0.25, motion.y,
                        motion.z + (to.z / dist * LAND_DRIFT - motion.z) * 0.25);
            }
        }
    }

    private static void clearPotion(Witch witch) {
        if (witch.getMainHandItem().is(CursesItems.LEVITATION_POTION.get())) {
            witch.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    private static void endFlight(Witch witch) {
        FLIGHTS.remove(witch);
        clearPotion(witch);
        if (witch.hasEffect(MobEffects.LEVITATION)) {
            settle(witch);
        }
    }

    private static void settle(Witch witch) {
        witch.removeEffect(MobEffects.LEVITATION);
        witch.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0));
    }

    // ---------------------------------------------------------------------------------------------------
    // Curse cleansing (milk)
    // ---------------------------------------------------------------------------------------------------

    private static boolean tryMilk(Witch witch, ServerLevel level, long now) {
        Long ready = MILK_READY.get(witch);
        if (ready != null && now < ready) {
            return false;
        }
        if (!(witch.getTarget() instanceof Player player) || !valid(witch, player) || !moreGoodThanBad(player)) {
            return false;
        }
        double distance = witch.distanceTo(player);
        if (distance < MILK_MIN || distance > MILK_MAX || !witch.hasLineOfSight(player)) {
            return false;
        }
        Long last = MILKED.get(player);
        if (last != null && now - last < MILK_PLAYER_GRACE) {
            return false;
        }
        if (witch.getRandom().nextFloat() >= MILK_CHANCE) {
            return false;
        }

        // The mod's own milk potion item (your texture). It is not a vanilla potion, so nothing else reacts to it;
        // onImpact below clears the effects.
        ItemStack milk = new ItemStack(CursesItems.MILK_POTION.get());
        if (!lob(witch, level, player.position().add(0.0, 1.0, 0.0), milk, true)) {
            MILK_READY.put(witch, now + 40);
            return false;
        }
        witch.swing(InteractionHand.MAIN_HAND);
        MILK_READY.put(witch, now + MILK_COOLDOWN);
        MILKED.put(player, now);
        return true;
    }

    /** More positive effects than negative ones (and at least one). Neutral effects do not count. */
    private static boolean moreGoodThanBad(Player player) {
        int good = 0;
        int bad = 0;
        for (MobEffectInstance effect : player.getActiveEffects()) {
            switch (effect.getEffect().value().getCategory()) {
                case BENEFICIAL -> good++;
                case HARMFUL -> bad++;
                default -> {
                }
            }
        }
        return good > 0 && good > bad;
    }

    /** Tags the milk potion as it enters the world. */
    @SubscribeEvent
    public static void onPotionJoin(EntityJoinLevelEvent event) {
        if (throwingMilk && event.getEntity() instanceof ThrownPotion potion) {
            potion.getPersistentData().putBoolean(KEY_MILK, true);
        }
    }

    /** A milk splash clears every effect from the players inside its radius, like drinking milk. */
    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownPotion potion) || !(potion.level() instanceof ServerLevel level)
                || !potion.getPersistentData().getBoolean(KEY_MILK)) {
            return;
        }
        event.setCanceled(true);
        if (potion.isRemoved()) {
            return;
        }
        Vec3 at = event.getRayTraceResult().getLocation();
        for (Player player : level.getEntitiesOfClass(Player.class, AABB.ofSize(at, 8.0, 4.0, 8.0))) {
            if (!player.isSpectator() && player.distanceToSqr(at) < SPLASH_RADIUS * SPLASH_RADIUS) {
                player.removeAllEffects();
            }
        }
        level.levelEvent(2002, BlockPos.containing(at), MILK_COLOR); // glass shatter + splash particles
        potion.discard();
    }

    // ---------------------------------------------------------------------------------------------------
    // Ally support (water, then regeneration)
    // ---------------------------------------------------------------------------------------------------

    private static boolean tryAllySupport(Witch witch, ServerLevel level, long now) {
        Long ready = ALLY_READY.get(witch);
        if (ready != null && now < ready) {
            return false;
        }
        Mob ally = findBurningAlly(witch);
        if (ally == null || witch.getRandom().nextFloat() >= ALLY_CHANCE) {
            return false;
        }
        Vec3 aim = ally.position().add(0.0, 0.5, 0.0);
        if (playerNear(level, aim)) {
            return false;
        }
        ItemStack water = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.WATER);
        if (!lob(witch, level, aim, water, false)) {
            ALLY_READY.put(witch, now + 40);
            return false;
        }
        witch.swing(InteractionHand.MAIN_HAND);
        ALLY_READY.put(witch, now + ALLY_COOLDOWN);
        FOLLOWS.put(witch, new Follow(ally, now + FOLLOW_DELAY));
        return true;
    }

    /** The nearest hostile mob in sight that is on fire and would not be hurt by water, or null. */
    private static Mob findBurningAlly(Witch witch) {
        List<Mob> found = witch.level().getEntitiesOfClass(Mob.class, witch.getBoundingBox().inflate(ALLY_RANGE),
                m -> m != witch && m instanceof Enemy && m.isAlive() && m.isOnFire() && !m.fireImmune()
                        && !m.isSensitiveToWater());
        Mob best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Mob candidate : found) {
            double distance = witch.distanceTo(candidate);
            if (distance < ALLY_MIN || distance > ALLY_RANGE || distance >= bestDistance || !witch.hasLineOfSight(candidate)) {
                continue;
            }
            bestDistance = distance;
            best = candidate;
        }
        return best;
    }

    /** The follow-up to the water: a Splash Potion of Regeneration for an ally that is low on health. */
    private static void tryRegeneration(Witch witch, ServerLevel level, Mob ally) {
        if (!enabled(level) || witch.isDrinkingPotion() || !ally.isAlive() || ally.level() != level) {
            return;
        }
        if (ally.getHealth() > ally.getMaxHealth() * LOW_HEALTH || witch.getRandom().nextFloat() >= REGEN_CHANCE) {
            return;
        }
        double distance = witch.distanceTo(ally);
        if (distance < ALLY_MIN || distance > ALLY_RANGE || !witch.hasLineOfSight(ally)) {
            return;
        }
        Vec3 aim = ally.position().add(0.0, 0.5, 0.0);
        if (playerNear(level, aim)) {
            return;
        }
        ItemStack regen = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.REGENERATION);
        if (lob(witch, level, aim, regen, false)) {
            witch.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** Is any player (creative ones too) close enough to be caught by a splash that lands here? */
    private static boolean playerNear(ServerLevel level, Vec3 at) {
        List<Player> near = new ArrayList<>();
        for (Player player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(at) <= PLAYER_CLEARANCE * PLAYER_CLEARANCE) {
                near.add(player);
            }
        }
        return !near.isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------
    // Throwing
    // ---------------------------------------------------------------------------------------------------

    private static boolean lob(Witch witch, ServerLevel level, Vec3 aim, ItemStack stack, boolean milk) {
        throwingMilk = milk;
        try {
            return Siege.lobPotion(witch, level, aim, stack);
        } finally {
            throwingMilk = false;
        }
    }
}
