package com.example.curses.client;

import com.example.curses.effect.InconspicuousHandler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.CreeperModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.CreeperRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.monster.Creeper;

/**
 * The vanilla creeper renderer, except that creepers carrying the Inconspicuous marker are drawn with 80%
 * transparency (alpha 51 of 255) through a translucent render type. Every other creeper renders exactly as before.
 *
 * <p>Client only. It replaces the vanilla creeper renderer (see {@code CursesClientSetup}), so another mod that
 * also replaces it will win or lose depending on load order.</p>
 */
public class InconspicuousCreeperRenderer extends CreeperRenderer {
    /** 80% transparent = 20% opaque. */
    private static final int ALPHA = 51;

    public InconspicuousCreeperRenderer(EntityRendererProvider.Context context) {
        super(context);
        // Same model as vanilla, but able to draw itself with an alpha. Layers (charged swirl) keep their own models.
        this.model = new GhostModel(context.bakeLayer(ModelLayers.CREEPER));
    }

    @Override
    public void render(Creeper entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        ((GhostModel) this.model).ghost = InconspicuousHandler.isInconspicuous(entity);
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    protected RenderType getRenderType(Creeper entity, boolean bodyVisible, boolean translucent, boolean glowing) {
        if (bodyVisible && !glowing && InconspicuousHandler.isInconspicuous(entity)) {
            // The normal creeper type is cutout (no blending); alpha only shows through a translucent type.
            return RenderType.entityTranslucent(this.getTextureLocation(entity));
        }
        return super.getRenderType(entity, bodyVisible, translucent, glowing);
    }

    private static final class GhostModel extends CreeperModel<Creeper> {
        boolean ghost;

        GhostModel(ModelPart root) {
            super(root);
        }

        @Override
        public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay, int color) {
            super.renderToBuffer(poseStack, buffer, packedLight, packedOverlay,
                    ghost ? (ALPHA << 24) | (color & 0x00FFFFFF) : color);
        }
    }
}
