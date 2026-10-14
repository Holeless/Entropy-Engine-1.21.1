package com.example.curses.effect;

/**
 * Scales how fast a player's exhaustion builds up, without touching vanilla code.
 *
 * <p>In vanilla, every action adds "exhaustion" to the player's {@code FoodData}. Once exhaustion is above
 * 4.0, {@code FoodData.tick} subtracts 4.0 and removes one point of saturation (or hunger). So hunger is
 * spent in direct proportion to the exhaustion gained, and gaining 50% more exhaustion means hunger drains
 * 50% faster.</p>
 *
 * <p>This class watches exhaustion around the player tick and tells the caller how much extra to add:</p>
 * <ul>
 *   <li><b>Before the tick</b>: anything gained since the end of the previous tick came from actions
 *       (moving, jumping, attacking, breaking blocks...). Vanilla only ever subtracts inside its tick, so
 *       the difference is exactly what was gained.</li>
 *   <li><b>After the tick</b>: vanilla may have subtracted 4.0 (if exhaustion was above 4.0 when the tick
 *       began) and may have added more (natural regeneration, the Hunger effect). Subtracting the known
 *       4.0 leaves exactly what was gained during the tick.</li>
 * </ul>
 *
 * <p>No Minecraft types are used here on purpose, so the maths can be tested on its own
 * (see {@code tools/FamineSimulation.java}).</p>
 */
public final class ExhaustionTracker {
    /** Vanilla subtracts this from exhaustion, once per tick, while exhaustion is above it. */
    static final float VANILLA_THRESHOLD = 4.0F;
    private static final float EPSILON = 1.0E-4F;

    private float lastTickEnd;
    private float tickStart;

    public ExhaustionTracker(float currentExhaustion) {
        this.lastTickEnd = currentExhaustion;
        this.tickStart = currentExhaustion;
    }

    /**
     * Call right before the player tick.
     *
     * @param current     the player's exhaustion right now
     * @param extraFactor extra exhaustion per point gained (0.5 = 50% faster, 0 = curse inactive)
     * @return exhaustion the caller must add now (0 if none)
     */
    public float extraBeforeTick(float current, float extraFactor) {
        float gained = current - lastTickEnd;
        return gained > EPSILON ? gained * extraFactor : 0.0F;
    }

    /** Call right after adding what {@link #extraBeforeTick} returned, with the exhaustion at that point. */
    public void tickStarted(float current) {
        this.tickStart = current;
    }

    /**
     * Call right after the player tick.
     *
     * @return exhaustion the caller must add now (0 if none)
     */
    public float extraAfterTick(float current, float extraFactor) {
        float expected = tickStart > VANILLA_THRESHOLD ? tickStart - VANILLA_THRESHOLD : tickStart;
        float gained = current - expected;
        return gained > EPSILON ? gained * extraFactor : 0.0F;
    }

    /** Call right after adding what {@link #extraAfterTick} returned, with the exhaustion at that point. */
    public void tickEnded(float current) {
        this.lastTickEnd = current;
    }
}
