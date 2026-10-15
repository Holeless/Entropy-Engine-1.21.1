package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Tools: zombies have a chance to spawn with a pickaxe or an axe in their main hand, and use it to dig through
 * blocks to get to you.
 *
 * <p><b>Who:</b> every zombie (husks and zombie villagers too, not drowned, which hold tridents). Each zombie rolls
 * once (remembered in its saved data): {@link #PICKAXE_CHANCE} for a pickaxe, {@link #AXE_CHANCE} for an axe, nothing
 * otherwise. A zombie that already holds something in its main hand is left alone. The tool is wooden, stone or iron
 * and is not loot.</p>
 *
 * <p><b>Digging:</b> done by {@code BlockBreakerHandler} (the same hand-mining the Breach curse uses): a zombie that is
 * hunting you and cannot walk to you mines the blocks in its way. With a tool in hand that works with Tools alone
 * (no Breach needed), and it is much faster than bare hands on the blocks the tool is made for (stone and ore with a
 * pickaxe, wood with an axe).</p>
 *
 * <p>The tool's attack bonus is removed, so a zombie hits exactly as hard as in vanilla. Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class ToolsHandler {
    /** Chance for each zombie to get a pickaxe. */
    public static final float PICKAXE_CHANCE = 0.20F;
    /** Chance for each zombie to get an axe. */
    public static final float AXE_CHANCE = 0.20F;

    private static final String KEY_ROLLED = "curses_tools_rolled";

    private ToolsHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Zombie zombie)
                || zombie instanceof Drowned) {
            return;
        }
        CompoundTag data = zombie.getPersistentData();
        if (data.getBoolean(KEY_ROLLED) || !CurseData.isActive(level.getServer(), Curses.TOOLS)) {
            return;
        }
        data.putBoolean(KEY_ROLLED, true);

        RandomSource random = zombie.getRandom();
        float roll = random.nextFloat();
        boolean pickaxe = roll < PICKAXE_CHANCE;
        boolean axe = !pickaxe && roll < PICKAXE_CHANCE + AXE_CHANCE;
        if ((!pickaxe && !axe) || !zombie.getMainHandItem().isEmpty()) {
            return;
        }

        ItemStack tool = pickaxe ? pickaxe(random) : axe(random);
        // Only the digging matters: without this a zombie with an iron axe would hit for 11 instead of 3.
        tool.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        zombie.setItemSlot(EquipmentSlot.MAINHAND, tool);
        zombie.setDropChance(EquipmentSlot.MAINHAND, 0.0F); // the curse's tool is not loot
        CursesMod.LOGGER.debug("Tools: gave {} to {} at {}", tool.getItem(), zombie.getType(), zombie.blockPosition());
    }

    private static ItemStack pickaxe(RandomSource random) {
        int tier = random.nextInt(10);
        return new ItemStack(tier < 3 ? Items.WOODEN_PICKAXE : tier < 7 ? Items.STONE_PICKAXE : Items.IRON_PICKAXE);
    }

    private static ItemStack axe(RandomSource random) {
        int tier = random.nextInt(10);
        return new ItemStack(tier < 3 ? Items.WOODEN_AXE : tier < 7 ? Items.STONE_AXE : Items.IRON_AXE);
    }
}
