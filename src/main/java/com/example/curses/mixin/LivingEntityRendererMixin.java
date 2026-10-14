package com.example.curses.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;

/**
 * Client only. Gives mobs that raise a shield (Bastion curse) the same arm pose a blocking player has: the
 * shield-holding arm is bent up in front of the body. Without this, zombies keep their arms-out zombie pose
 * and the shield ends up floating above their head.
 *
 * <p>It runs right after the model has posed itself for the frame, so it works for every humanoid model
 * (zombies, skeletons, piglins) regardless of how each one positions its arms. {@code require = 0}: if this
 * ever stops matching (a changed vanilla method) the mod still loads and the shield just looks like before.</p>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    // Same angles vanilla uses for a blocking arm (HumanoidModel.ArmPose.BLOCK)
    private static final float BLOCK_X = -0.9424779F;
    private static final float BLOCK_Y = 0.5235988F;

    @Shadow
    public abstract EntityModel<?> getModel();

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void curses$blockPose(LivingEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
                                  MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (!(entity instanceof Mob mob) || !(getModel() instanceof HumanoidModel<?> model)) {
            return;
        }
        if (!mob.isUsingItem() || mob.getUsedItemHand() != InteractionHand.OFF_HAND || !mob.getOffhandItem().is(Items.SHIELD)) {
            return;
        }
        // The shield is in the off hand: the left arm for a right-handed mob, the right arm for a left-handed one.
        boolean rightArm = mob.getMainArm() == HumanoidArm.LEFT;
        ModelPart arm = rightArm ? model.rightArm : model.leftArm;
        arm.xRot = BLOCK_X;
        arm.yRot = rightArm ? -BLOCK_Y : BLOCK_Y;
        arm.zRot = 0.0F;
    }
}
