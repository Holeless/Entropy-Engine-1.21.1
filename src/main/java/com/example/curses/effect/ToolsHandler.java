package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Tools: zombies have a chance to spawn with a pickaxe or an axe in their main hand, and use it to dig through
 * blocks to get to you.
 *
 * <p><b>Who:</b> every zombie (husks and zombie villagers too, not drowned, which hold tridents). Each zombie rolls
 * once (remembered in its saved data): {@link #PICKAXE_CHANCE} for a pickaxe, {@link #AXE_CHANCE} for an axe, nothing
 * otherwise. A zombie that already holds something in its main hand is left alone. The tool is not loot.</p>
 *
 * <p><b>The tool:</b> an axe is wooden (30%), stone (40%) or iron (30%). A pickaxe is diamond {@link #DIAMOND_CHANCE} of
 * the time, otherwise wooden, stone or iron in the same 3:4:3 split. {@link #ENCHANT_CHANCE} of all tools carry
 * Efficiency I-V (random level).</p>
 *
 * <p><b>Fighting:</b> the tool keeps its normal attack bonus, so a zombie hits for its own damage plus the tool's.</p>
 *
 * <p><b>Digging:</b> done by {@code BlockBreakerHandler}: a zombie that is hunting you and cannot walk to you mines the
 * blocks in its way, at the speed its tool (and Efficiency) would give a player. Works with Tools alone, no Breach
 * needed. Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class ToolsHandler {
    /** Chance for each zombie to get a pickaxe. */
    public static final float PICKAXE_CHANCE = 0.20F;
    /** Chance for each zombie to get an axe. */
    public static final float AXE_CHANCE = 0.20F;
    /** Chance for a pickaxe to be diamond. */
    public static final float DIAMOND_CHANCE = 0.20F;
    /** Chance for a tool to be enchanted with Efficiency. */
    public static final float ENCHANT_CHANCE = 0.40F;
    private static final int MAX_EFFICIENCY = 5;

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
        if (random.nextFloat() < ENCHANT_CHANCE) {
            Holder<Enchantment> efficiency = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.EFFICIENCY);
            tool.enchant(efficiency, 1 + random.nextInt(MAX_EFFICIENCY));
        }
        zombie.setItemSlot(EquipmentSlot.MAINHAND, tool);
        zombie.setDropChance(EquipmentSlot.MAINHAND, 0.0F); // the curse's tool is not loot
        CursesMod.LOGGER.debug("Tools: gave {} to {} at {}", tool, zombie.getType(), zombie.blockPosition());
    }

    private static ItemStack pickaxe(RandomSource random) {
        if (random.nextFloat() < DIAMOND_CHANCE) {
            return new ItemStack(Items.DIAMOND_PICKAXE);
        }
        int tier = random.nextInt(10);
        return new ItemStack(tier < 3 ? Items.WOODEN_PICKAXE : tier < 7 ? Items.STONE_PICKAXE : Items.IRON_PICKAXE);
    }

    private static ItemStack axe(RandomSource random) {
        int tier = random.nextInt(10);
        return new ItemStack(tier < 3 ? Items.WOODEN_AXE : tier < 7 ? Items.STONE_AXE : Items.IRON_AXE);
    }
}
