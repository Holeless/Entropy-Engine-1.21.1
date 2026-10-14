package com.example.curses.client;

import com.example.curses.entity.ExplosiveArrow;
import com.example.curses.registry.CursesItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws the Ordnance arrow as the {@code curses:explosive_arrow} item sprite, turned to point along its
 * flight path. The sprite is drawn twice, the second copy rolled 90 degrees around the flight direction, so
 * it looks like a cross from every side instead of vanishing when seen edge-on.
 *
 * <p>The sprite is assumed to point up and to the right, like the vanilla arrow icon. If it ever shows
 * pointing backwards, change {@link #SPRITE_ROTATION} by 180.</p>
 */
public class ExplosiveArrowRenderer extends EntityRenderer<ExplosiveArrow> {
    private static final float SPRITE_ROTATION = -45.0F; // turns an up-right sprite so its tip points along +X
    private static final float SCALE = 0.9F;

    private final ItemRenderer itemRenderer;

    public ExplosiveArrowRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(ExplosiveArrow entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        ItemStack stack = new ItemStack(CursesItems.EXPLOSIVE_ARROW.get());

        poseStack.pushPose();
        // Same orientation vanilla arrows use: after these two turns +X is the direction of flight.
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, entity.yRotO, entity.getYRot()) - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTicks, entity.xRotO, entity.getXRot())));

        for (int i = 0; i < 2; i++) {
            poseStack.pushPose();
            if (i == 1) {
                poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            }
            poseStack.mulPose(Axis.ZP.rotationDegrees(SPRITE_ROTATION));
            poseStack.scale(SCALE, SCALE, SCALE);
            this.itemRenderer.renderStatic(stack, ItemDisplayContext.NONE, packedLight, OverlayTexture.NO_OVERLAY,
                    poseStack, buffer, entity.level(), entity.getId());
            poseStack.popPose();
        }

        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(ExplosiveArrow entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
