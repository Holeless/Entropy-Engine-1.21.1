import java.util.Random;

import com.example.curses.effect.ExhaustionTracker;

/**
 * Checks the Famine maths without launching Minecraft.
 *
 * <p>Re-implements vanilla's {@code FoodData} (exhaustion, saturation, food level, natural regeneration)
 * and drives it with random player activity, with the ExhaustionTracker hooked in exactly the way
 * FamineHandler hooks it (before the tick, after the tick).</p>
 *
 * <p>Two checks:</p>
 * <ol>
 *   <li><b>Hunger rate</b> (full health, so no natural regeneration): with the same activity, the number of
 *       hunger/saturation points lost must be 1.5x the number lost without the curse.</li>
 *   <li><b>Exactness</b> (player hurt, so natural regeneration keeps adding exhaustion inside the tick):
 *       the exhaustion the curse adds must equal 0.5x everything vanilla added, however it was added.</li>
 * </ol>
 *
 * <pre>
 *   javac -d build/sim src/main/java/com/example/curses/effect/ExhaustionTracker.java tools/FamineSimulation.java
 *   java -cp build/sim FamineSimulation
 * </pre>
 */
public class FamineSimulation {

    /** Minimal copy of vanilla FoodData behaviour (1.21.1). */
    static class Food {
        float exhaustion;
        float saturation = 20.0F;
        int food = 20;
        int tickTimer;
        final boolean hurt;          // true: natural regeneration is running
        float vanillaAdded;          // exhaustion added by the "game"
        float curseAdded;            // exhaustion added by the curse
        int hungerPointsLost;        // rollovers: each costs one saturation/food point

        Food(boolean hurt) {
            this.hurt = hurt;
        }

        void add(float amount, boolean fromCurse) {
            float before = exhaustion;
            exhaustion = Math.min(exhaustion + amount, 40.0F);
            if (fromCurse) {
                curseAdded += exhaustion - before;
            } else {
                vanillaAdded += exhaustion - before;
            }
        }

        /** FoodData.tick: rollover, then natural regeneration. */
        void tick() {
            if (exhaustion > 4.0F) {
                exhaustion -= 4.0F;
                hungerPointsLost++;
                if (saturation > 0.0F) {
                    saturation = Math.max(saturation - 1.0F, 0.0F);
                } else {
                    food = Math.max(food - 1, 0);
                }
            }
            if (saturation > 0.0F && hurt && food >= 20) {
                if (++tickTimer >= 10) {
                    add(Math.min(saturation, 6.0F), false);
                    tickTimer = 0;
                }
            } else if (food >= 18 && hurt) {
                if (++tickTimer >= 80) {
                    add(6.0F, false);
                    tickTimer = 0;
                }
            } else {
                tickTimer = 0;
            }
            // Keep every run in the same state so runs stay comparable
            if (food < 18) {
                food = 20;
            }
            if (saturation < 5.0F) {
                saturation = 20.0F;
            }
        }
    }

    static Food run(long seed, int ticks, float extraFactor, boolean hurt) {
        Random random = new Random(seed);
        Food food = new Food(hurt);
        ExhaustionTracker tracker = new ExhaustionTracker(food.exhaustion);

        for (int t = 0; t < ticks; t++) {
            // Actions handled between ticks (packets): walking, sprinting, jumping, breaking, attacking
            if (random.nextInt(3) != 0) {
                food.add(0.005F * (1 + random.nextInt(8)), false);
            }
            if (random.nextInt(40) == 0) {
                food.add(0.05F, false);
            }
            if (random.nextInt(60) == 0) {
                food.add(0.1F, false);
            }

            // PlayerTickEvent.Pre
            float extra = tracker.extraBeforeTick(food.exhaustion, extraFactor);
            if (extra > 0.0F) {
                food.add(extra, true);
            }
            tracker.tickStarted(food.exhaustion);

            // vanilla player tick (food tick) plus a Hunger-effect style add inside the tick
            food.tick();
            if (random.nextInt(25) == 0) {
                food.add(0.005F, false);
            }

            // PlayerTickEvent.Post
            extra = tracker.extraAfterTick(food.exhaustion, extraFactor);
            if (extra > 0.0F) {
                food.add(extra, true);
            }
            tracker.tickEnded(food.exhaustion);
        }
        return food;
    }

    public static void main(String[] args) {
        int ticks = 20 * 60 * 30; // 30 minutes of play
        boolean ok = true;

        System.out.println("1) Hunger rate, full health (same activity, with vs without curse)");
        for (long seed = 1; seed <= 5; seed++) {
            Food normal = run(seed, ticks, 0.0F, false);
            Food famine = run(seed, ticks, 0.5F, false);
            float ratio = (float) famine.hungerPointsLost / normal.hungerPointsLost;
            boolean pass = Math.abs(ratio - 1.5F) < 0.01F;
            ok &= pass;
            System.out.printf("   seed %d: points lost normal=%d famine=%d ratio=%.4f %s%n",
                    seed, normal.hungerPointsLost, famine.hungerPointsLost, ratio, pass ? "OK" : "FAIL");
        }

        System.out.println("2) Exactness while healing (regeneration adds exhaustion inside the tick)");
        for (long seed = 1; seed <= 5; seed++) {
            Food famine = run(seed, ticks, 0.5F, true);
            float ratio = famine.curseAdded / famine.vanillaAdded;
            boolean pass = Math.abs(ratio - 0.5F) < 0.002F;
            ok &= pass;
            System.out.printf("   seed %d: vanilla=%.1f curse extra=%.1f extra/vanilla=%.4f %s%n",
                    seed, famine.vanillaAdded, famine.curseAdded, ratio, pass ? "OK" : "FAIL");
        }

        System.out.println("3) Curse off must change nothing");
        Food off = run(7, ticks, 0.0F, true);
        boolean untouched = off.curseAdded == 0.0F;
        ok &= untouched;
        System.out.println("   extra exhaustion added with curse off = " + off.curseAdded + (untouched ? " OK" : " FAIL"));

        System.out.println(ok ? "ALL OK: Famine makes hunger drain 1.5x as fast" : "FAILED");
        if (!ok) {
            System.exit(1);
        }
    }
}
