package io.github.piwugu.snowleopard.client.model;

import io.github.piwugu.snowleopard.AvatarEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 雪豹载体模型（26 骨骼 / 42 立方体 / 128x128 贴图）。
 *
 * <p>由 tools/bbmodel_to_avatar.py 从 snow_leopard_fixed.bbmodel 生成，**不要手改**——改 bbmodel 后重跑脚本。
 * 坐标系：java_y = 24 - bb_y（脚底落在 24，配合 LivingEntityRenderer 的 -1.501 平移贴地）。
 * group 的初始旋转用 PartPose.offsetAndRotation 保留，setupAnim 在初始角上叠加。
 */
public class SnowLeopardModel extends EntityModel<AvatarEntity> {

    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(
                    ResourceLocation.fromNamespaceAndPath("snowleopard", "snow_leopard"), "main");

    private final ModelPart root;
    private final ModelPart body;
    private final ModelPart bodyMid;
    private final ModelPart head;
    private final ModelPart headNose;
    private final ModelPart earRight;
    private final ModelPart earLeft;
    private final ModelPart jowlLeft;
    private final ModelPart jowlRight;
    private final ModelPart furBrow;
    private final ModelPart jaw;
    private final ModelPart eyes;
    private final ModelPart eyeLidLeft;
    private final ModelPart eyePupilLeft;
    private final ModelPart eyeLidRight;
    private final ModelPart eyePupilRight;
    private final ModelPart legFrontLeft;
    private final ModelPart legFrontRight;
    private final ModelPart legHindRight;
    private final ModelPart legHindLeft;
    private final ModelPart tail1;
    private final ModelPart tail2;
    private final ModelPart tail3;
    private final ModelPart tail4;
    private final ModelPart tail5;
    private final ModelPart tail6;
    private final ModelPart tail7;

    public SnowLeopardModel(ModelPart root) {
        this.root = root;
        this.body = root.getChild("body");
        this.bodyMid = this.body.getChild("body_mid");
        this.head = this.body.getChild("head");
        this.headNose = this.head.getChild("head_nose");
        this.earRight = this.head.getChild("ear_right");
        this.earLeft = this.head.getChild("ear_left");
        this.jowlLeft = this.head.getChild("jowl_left");
        this.jowlRight = this.head.getChild("jowl_right");
        this.furBrow = this.head.getChild("fur_brow");
        this.jaw = this.head.getChild("jaw");
        this.eyes = this.head.getChild("eyes");
        this.eyeLidLeft = this.eyes.getChild("eye_lid_left");
        this.eyePupilLeft = this.eyes.getChild("eye_pupil_left");
        this.eyeLidRight = this.eyes.getChild("eye_lid_right");
        this.eyePupilRight = this.eyes.getChild("eye_pupil_right");
        this.legFrontLeft = this.body.getChild("leg_front_left");
        this.legFrontRight = this.body.getChild("leg_front_right");
        this.legHindRight = this.body.getChild("leg_hind_right");
        this.legHindLeft = this.body.getChild("leg_hind_left");
        this.tail1 = this.body.getChild("tail_1");
        this.tail2 = this.tail1.getChild("tail_2");
        this.tail3 = this.tail2.getChild("tail_3");
        this.tail4 = this.tail3.getChild("tail_4");
        this.tail5 = this.tail4.getChild("tail_5");
        this.tail6 = this.tail5.getChild("tail_6");
        this.tail7 = this.tail6.getChild("tail_7");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition body = root.addOrReplaceChild("body",
                CubeListBuilder.create()
                .texOffs(46, 1)
                .addBox(-6F, -5.5F, -14.5F, 12F, 11F, 10F, new CubeDeformation(0F))
                .texOffs(91, 1)
                .addBox(-5F, -4.9F, 6.5F, 10F, 10F, 8F, new CubeDeformation(0.2F))
                .texOffs(1, 24)
                .addBox(-6.05F, -5.5F, -4.5F, 0.05F, 11F, 4F, new CubeDeformation(0F))
                .texOffs(1, 58)
                .addBox(-6F, 5.5F, -4.5F, 12F, 0.05F, 4F, new CubeDeformation(0F))
                .texOffs(34, 58)
                .addBox(-6F, -5.55F, -4.5F, 12F, 0.05F, 4F, new CubeDeformation(0F))
                .texOffs(10, 24)
                .addBox(6F, -5.5F, -4.5F, 0.05F, 11F, 4F, new CubeDeformation(0F)),
                PartPose.offset(0F, 9.6F, -1.5F));

        PartDefinition bodyMid = body.addOrReplaceChild("body_mid",
                CubeListBuilder.create()
                .texOffs(1, 1)
                .addBox(-5F, -5.0005F, -5.9996F, 10F, 10F, 12F, new CubeDeformation(0F)),
                PartPose.offsetAndRotation(0F, -0.022F, 1.171F, -0.0349F, 0F, 0F));

        PartDefinition head = body.addOrReplaceChild("head",
                CubeListBuilder.create()
                .texOffs(19, 24)
                .addBox(-4F, -6.5F, -6F, 8F, 6F, 8F, new CubeDeformation(0F))
                .texOffs(112, 58)
                .addBox(-5F, -4F, -4.05F, 1F, 3F, 0.05F, new CubeDeformation(0F))
                .texOffs(115, 58)
                .addBox(4F, -4F, -4.05F, 1F, 3F, 0.05F, new CubeDeformation(0F)),
                PartPose.offset(0F, -1.6F, -15.5F));

        PartDefinition headNose = head.addOrReplaceChild("head_nose",
                CubeListBuilder.create()
                .texOffs(1, 51)
                .addBox(-1F, -2F, -1F, 2F, 2F, 4F, new CubeDeformation(0F)),
                PartPose.offsetAndRotation(0F, -1.7F, -7.9F, 0.3491F, 0F, 0F));

        PartDefinition earRight = head.addOrReplaceChild("ear_right",
                CubeListBuilder.create()
                .texOffs(63, 51)
                .addBox(0F, -3F, -1F, 3F, 3F, 2F, new CubeDeformation(0F)),
                PartPose.offsetAndRotation(3.3F, -4F, 0F, 0F, -0.1745F, -0.3491F));

        PartDefinition earLeft = head.addOrReplaceChild("ear_left",
                CubeListBuilder.create()
                .texOffs(74, 51)
                .addBox(-3F, -3F, -1F, 3F, 3F, 2F, new CubeDeformation(0F)),
                PartPose.offsetAndRotation(-3.3F, -4F, 0F, 0F, 0.1745F, 0.3491F));

        PartDefinition jowlLeft = head.addOrReplaceChild("jowl_left",
                CubeListBuilder.create()
                .texOffs(14, 51)
                .addBox(-1F, -2F, -1F, 2F, 2F, 4F, new CubeDeformation(0.2F)),
                PartPose.offsetAndRotation(-1F, -1F, -8F, 0.0873F, -0.1745F, -0.1745F));

        PartDefinition jowlRight = head.addOrReplaceChild("jowl_right",
                CubeListBuilder.create()
                .texOffs(27, 51)
                .addBox(-1F, -2F, -1F, 2F, 2F, 4F, new CubeDeformation(0.2F)),
                PartPose.offsetAndRotation(1F, -1F, -8F, 0.0873F, 0.1745F, 0.1745F));

        PartDefinition furBrow = head.addOrReplaceChild("fur_brow",
                CubeListBuilder.create()
                .texOffs(73, 58)
                .addBox(-1F, -0.05F, -1F, 2F, 0.05F, 3F, new CubeDeformation(0F)),
                PartPose.offsetAndRotation(0F, -1.2F, -8.1F, 0.1745F, 0F, 0F));

        PartDefinition jaw = head.addOrReplaceChild("jaw",
                CubeListBuilder.create()
                .texOffs(50, 51)
                .addBox(-1F, -1F, -1F, 2F, 1F, 4F, new CubeDeformation(0F)),
                PartPose.offsetAndRotation(0F, -0.2F, -7.9F, 0.1745F, 0F, 0F));

        PartDefinition eyes = head.addOrReplaceChild("eyes",
                CubeListBuilder.create()
                .texOffs(98, 58)
                .addBox(-3F, -5.5F, -6.12F, 2F, 2F, 1F, new CubeDeformation(-0.1F))
                .texOffs(105, 58)
                .addBox(1F, -5.5F, -6.12F, 2F, 2F, 1F, new CubeDeformation(-0.1F)),
                PartPose.offset(0F, 0F, 0F));

        PartDefinition eyeLidLeft = eyes.addOrReplaceChild("eye_lid_left",
                CubeListBuilder.create()
                .texOffs(84, 58)
                .addBox(-1F, -1F, -0.5F, 2F, 2F, 1F, new CubeDeformation(0F)),
                PartPose.offset(-2F, -4.5F, -5.75F));

        PartDefinition eyePupilLeft = eyes.addOrReplaceChild("eye_pupil_left",
                CubeListBuilder.create()
                .texOffs(2, 1)
                .addBox(-0.5F, -0.5F, 0F, 1F, 1F, 1F, new CubeDeformation(0F)),
                PartPose.offset(-2F, -4.5F, -6.175F));

        PartDefinition eyeLidRight = eyes.addOrReplaceChild("eye_lid_right",
                CubeListBuilder.create()
                .texOffs(91, 58)
                .addBox(-1F, -1F, -0.5F, 2F, 2F, 1F, new CubeDeformation(0F)),
                PartPose.offset(2F, -4.5F, -5.75F));

        PartDefinition eyePupilRight = eyes.addOrReplaceChild("eye_pupil_right",
                CubeListBuilder.create()
                .texOffs(2, 1)
                .addBox(-0.5F, -0.5F, 0F, 1F, 1F, 1F, new CubeDeformation(0F)),
                PartPose.offset(2F, -4.5F, -6.175F));

        PartDefinition legFrontLeft = body.addOrReplaceChild("leg_front_left",
                CubeListBuilder.create()
                .texOffs(52, 24)
                .addBox(-1.5F, 0F, -2F, 3F, 10F, 4F, new CubeDeformation(0F)),
                PartPose.offset(-4F, 4.4F, -9.5F));

        PartDefinition legFrontRight = body.addOrReplaceChild("leg_front_right",
                CubeListBuilder.create()
                .texOffs(82, 24)
                .addBox(-1.5F, 0F, -2F, 3F, 10F, 4F, new CubeDeformation(0F)),
                PartPose.offset(4F, 4.4F, -9.5F));

        PartDefinition legHindRight = body.addOrReplaceChild("leg_hind_right",
                CubeListBuilder.create()
                .texOffs(97, 24)
                .addBox(-1.5F, 0F, -2F, 3F, 10F, 4F, new CubeDeformation(0F)),
                PartPose.offset(3.4F, 4.4F, 11.5F));

        PartDefinition legHindLeft = body.addOrReplaceChild("leg_hind_left",
                CubeListBuilder.create()
                .texOffs(67, 24)
                .addBox(-1.5F, 0F, -2F, 3F, 10F, 4F, new CubeDeformation(0F)),
                PartPose.offset(-3.4F, 4.4F, 11.5F));

        PartDefinition tail1 = body.addOrReplaceChild("tail_1",
                CubeListBuilder.create()
                .texOffs(1, 40)
                .addBox(-2F, -1.4F, 0F, 4F, 4F, 6F, new CubeDeformation(0F)),
                PartPose.offsetAndRotation(0F, -3.6F, 13.5F, -0.3491F, 0F, 0F));

        PartDefinition tail2 = tail1.addOrReplaceChild("tail_2",
                CubeListBuilder.create()
                .texOffs(22, 40)
                .addBox(-2F, -2F, -0.1F, 4F, 4F, 6F, new CubeDeformation(-0.05F)),
                PartPose.offsetAndRotation(0F, 0.6F, 6F, -0.1047F, 0F, 0F));

        PartDefinition tail3 = tail2.addOrReplaceChild("tail_3",
                CubeListBuilder.create()
                .texOffs(43, 40)
                .addBox(-2F, -2F, -0.3F, 4F, 4F, 6F, new CubeDeformation(-0.1F)),
                PartPose.offsetAndRotation(0F, 0F, 6F, 0.0524F, 0F, 0F));

        PartDefinition tail4 = tail3.addOrReplaceChild("tail_4",
                CubeListBuilder.create()
                .texOffs(64, 40)
                .addBox(-2F, -2F, -0.4F, 4F, 4F, 6F, new CubeDeformation(-0.15F)),
                PartPose.offsetAndRotation(0F, 0F, 5.8F, 0.0698F, 0F, 0F));

        PartDefinition tail5 = tail4.addOrReplaceChild("tail_5",
                CubeListBuilder.create()
                .texOffs(85, 40)
                .addBox(-2F, -2F, -0.3F, 4F, 4F, 6F, new CubeDeformation(-0.1F)),
                PartPose.offsetAndRotation(0F, 0F, 5.5F, 0.1745F, 0F, 0F));

        PartDefinition tail6 = tail5.addOrReplaceChild("tail_6",
                CubeListBuilder.create()
                .texOffs(106, 40)
                .addBox(-2F, -2F, -0.3F, 4F, 4F, 5F, new CubeDeformation(0F))
                .texOffs(1, 63)
                .addBox(-2F, -2.05F, 4.7F, 4F, 0.05F, 2F, new CubeDeformation(0F))
                .texOffs(14, 63)
                .addBox(-2F, 2F, 4.7F, 4F, 0.05F, 2F, new CubeDeformation(0F))
                .texOffs(40, 51)
                .addBox(-2.05F, -2F, 4.7F, 0.05F, 4F, 2F, new CubeDeformation(0F))
                .texOffs(45, 51)
                .addBox(2F, -2F, 4.7F, 0.05F, 4F, 2F, new CubeDeformation(0F)),
                PartPose.offsetAndRotation(0F, 0F, 5.7F, 0.1745F, 0F, 0F));

        PartDefinition tail7 = tail6.addOrReplaceChild("tail_7",
                CubeListBuilder.create()
                .texOffs(85, 51)
                .addBox(-2F, -1F, -0.3F, 3F, 3F, 2F, new CubeDeformation(0F))
                .texOffs(27, 63)
                .addBox(-2F, -0.05F, 1.7F, 3F, 0.05F, 1F, new CubeDeformation(0F))
                .texOffs(36, 63)
                .addBox(-2F, 1F, 1.7F, 3F, 0.05F, 1F, new CubeDeformation(0F))
                .texOffs(67, 58)
                .addBox(-1.05F, -1F, 1.7F, 0.05F, 3F, 1F, new CubeDeformation(0F))
                .texOffs(70, 58)
                .addBox(0F, -1F, 1.7F, 0.05F, 3F, 1F, new CubeDeformation(0F)),
                PartPose.offsetAndRotation(0.5F, -0.5F, 5F, 0.0873F, 0F, 0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    /**
     * 每帧驱动。所有值都在 bbmodel 的**初始角度上叠加**——直接赋值会把建模时的
     * 姿态（尾巴 20°、耳朵 ±20°、下颚 −10°）抹平，模型看着"对但不像"。
     */
    @Override
    public void setupAnim(AvatarEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        this.head.yRot = netHeadYaw * ((float) Math.PI / 180F);
        this.head.xRot = headPitch * ((float) Math.PI / 180F);

        // 对角步态（腿挂在 body 之下，故不做躯干起伏）
        float stride = Mth.cos(limbSwing * 0.6662F) * 1.4F * limbSwingAmount;
        float strideOpp = Mth.cos(limbSwing * 0.6662F + (float) Math.PI) * 1.4F * limbSwingAmount;
        this.legFrontLeft.xRot = stride;
        this.legFrontRight.xRot = strideOpp;
        this.legHindLeft.xRot = strideOpp;
        this.legHindRight.xRot = stride;

        // 尾巴：逐段衰减的波浪，叠加在各段初始角上
        float sway = Mth.cos(ageInTicks * 0.08F) * 0.10F * (0.4F + limbSwingAmount);
        this.tail1.xRot = -0.3491F + Mth.cos(ageInTicks * 0.05F) * 0.02F;
        this.tail1.yRot = sway * 1.00F;
        this.tail2.xRot = -0.1047F + Mth.cos(ageInTicks * 0.05F) * 0.02F;
        this.tail2.yRot = sway * 0.89F;
        this.tail3.xRot = 0.0524F + Mth.cos(ageInTicks * 0.05F) * 0.02F;
        this.tail3.yRot = sway * 0.78F;
        this.tail4.xRot = 0.0698F + Mth.cos(ageInTicks * 0.05F) * 0.02F;
        this.tail4.yRot = sway * 0.67F;
        this.tail5.xRot = 0.1745F + Mth.cos(ageInTicks * 0.05F) * 0.02F;
        this.tail5.yRot = sway * 0.56F;
        this.tail6.xRot = 0.1745F + Mth.cos(ageInTicks * 0.05F) * 0.02F;
        this.tail6.yRot = sway * 0.45F;
        this.tail7.xRot = 0.0873F + Mth.cos(ageInTicks * 0.05F) * 0.02F;
        this.tail7.yRot = sway * 0.35F;

        // 耳朵：低频抖动
        float flick = Mth.cos(ageInTicks * 0.1F) * 0.05F;
        this.earRight.zRot = -0.3491F + flick;
        this.earLeft.zRot = 0.3491F - flick;

        // 眨眼：眼皮默认隐藏（本模型眼皮与眼白同尺寸，静态显示会盖住眼睛），
        // 每 100 tick 眨一次、闭合 4 tick。要改成"默认闭合"只翻转两个分支即可。
        boolean blink = (ageInTicks % 100.0F) < 4.0F;
        this.eyeLidRight.visible = blink;
        this.eyeLidLeft.visible = blink;

        // 瞳孔：随头部朝向做微小偏移（±0.2px），几何修好后立即可见
        float gazeX = Mth.clamp(netHeadYaw * 0.008F, -0.2F, 0.2F);
        float gazeY = Mth.clamp(headPitch * 0.006F, -0.15F, 0.15F);
        this.eyePupilRight.x = gazeX;
        this.eyePupilRight.y = gazeY;
        this.eyePupilLeft.x = gazeX;
        this.eyePupilLeft.y = gazeY;

        // 下颚：jawOpen 0=闭 1=张，由宿主状态（被打/说话/进食）驱动
        this.jaw.xRot = 0.1745F - this.jawOpen * 0.45F;
    }

    /** 下颚张开程度（0=闭，1=全张）。由渲染侧按状态写入。 */
    public float jawOpen;

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight,
                               int packedOverlay, int color) {
        this.root.render(poseStack, buffer, packedLight, packedOverlay, color);
    }
}
