package com.example.curses.registry;

import com.example.curses.CursesMod;
import com.example.curses.entity.ExplosiveArrow;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Entities added by the mod. */
public final class CursesEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, CursesMod.MOD_ID);

    /** The exploding arrow skeletons shoot under the Ordnance curse. */
    public static final DeferredHolder<EntityType<?>, EntityType<ExplosiveArrow>> EXPLOSIVE_ARROW =
            ENTITY_TYPES.register("explosive_arrow", () -> EntityType.Builder.<ExplosiveArrow>of(ExplosiveArrow::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(4)
                    .updateInterval(20)
                    .build(CursesMod.id("explosive_arrow").toString()));

    private CursesEntities() {
    }
}
