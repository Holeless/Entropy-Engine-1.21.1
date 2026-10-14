# Curses (NeoForge 1.21.1)

Choose world-wide curses when you create a world. Each curse changes how the whole world behaves.

## Using it

1. Singleplayer -> **Create New World**.
2. Click the **Curses** button next to Create / Cancel.
3. Tick the curses you want (each has a symbol, a name and a short description), press **Done**.
4. Create the world. The curses are stored with the world (`data/curses.dat`), and a chat message on join tells you which are active.

Dedicated servers have no create-world screen, so use the operator command instead:

```
/curses list
/curses add famine
/curses remove famine
```

## Curses

| Curse  | Description            | Effect                                                             |
|--------|------------------------|--------------------------------------------------------------------|
| Famine | Hunger depletes faster | Every point of exhaustion counts 1.5x, so hunger drains 50% faster. |

### How Famine works

Vanilla spends hunger from "exhaustion", which every action adds to. `FamineHandler` watches the player's
exhaustion around the server-side player tick and adds half of whatever was gained on top, both for actions
(moving, jumping, attacking, mining) and for what happens inside the tick (natural regeneration, the Hunger
effect). The maths lives in `ExhaustionTracker` and has no Minecraft dependencies, so it is tested on its own:

```
javac -d build/sim src/main/java/com/example/curses/effect/ExhaustionTracker.java tools/FamineSimulation.java
java -cp build/sim FamineSimulation
```

The simulation re-implements vanilla's `FoodData` and checks that hunger is lost 1.5x as fast and that the
curse adds exactly 0.5x of all vanilla exhaustion. CI runs it on every push.

## Building

Requires JDK 21.

```
./gradlew build
```

The mod jar ends up in `build/libs/`. On GitHub, every push runs the **Build** workflow
(`.github/workflows/build.yml`) and uploads the jar as the `curses-mod` artifact.

To test in a dev client: `./gradlew runClient`.

## Adding a curse

1. `curse/Curses.java`: add `public static final Curse MY_CURSE = register("my_curse");`
2. `assets/curses/lang/en_us.json`: add `curse.curses.my_curse.name` and `curse.curses.my_curse.description`.
3. `assets/curses/textures/gui/curse/my_curse.png`: add a 16x16 symbol.
4. Implement the effect in an `@EventBusSubscriber` class and gate it with
   `CurseData.isActive(server, Curses.MY_CURSE)` (see `effect/FamineHandler.java`).

It then shows up in the selection screen and in `/curses` automatically.

## Layout

```
src/main/java/com/example/curses/
  CursesMod.java                  mod entry point
  curse/   Curse, Curses          the curse definitions
           CurseData              per-world saved state
           PendingCurses          selection made on the create-world screen
  effect/  FamineHandler          Famine effect
           ExhaustionTracker      the hunger maths (no Minecraft dependencies)
  event/   ServerEvents           applies the selection, join message, registers /curses
  command/ CurseCommand           /curses
  client/  CurseSelectionScreen   the selection screen
           CurseList              the vanilla-style list
           CursesClientEvents     adds the button to the create-world screen
tools/FamineSimulation.java       standalone check of the Famine maths
```

## Versions

NeoForge 21.1.259 for Minecraft 1.21.1, ModDevGradle 2.0.148, Gradle 9.2.1 (wrapper), Java 21.
Change `neo_version` in `gradle.properties` to move to a newer 21.1.x build.
