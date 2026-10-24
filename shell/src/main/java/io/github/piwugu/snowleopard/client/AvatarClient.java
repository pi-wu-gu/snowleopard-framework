package io.github.piwugu.snowleopard.client;

import io.github.piwugu.snowleopard.AvatarEntities;
import io.github.piwugu.snowleopard.AvatarEntity;
import net.minecraft.client.model.OcelotModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 载体渲染（M13-1，2026-10-24 01:20 (UTC+8)，仅客户端加载）。
 *
 * <p><b>复用豹猫外观，不继承豹猫实体</b>：{@code OcelotModel<T extends Entity>} 泛型上界是
 * {@code Entity}，可以直接套在自己的实体上；而 {@code OcelotRenderer} 绑死了 {@code Ocelot}
 * 不能用，所以自写这 ~15 行。贴图用原版 {@code textures/entity/cat/ocelot.png}（零新资源）。
 *
 * <p>**需真机肉眼确认的一点**（分析 §2.4）：{@code OcelotModel} 里有坐姿等状态常量，
 * 若 {@code setupAnim} 读了豹猫专属状态，我们可能永远渲染成站姿——泛型上界保证不会崩，
 * 但"看起来对不对"只能眼睛看。
 */
@EventBusSubscriber(modid = "snowleopard", value = Dist.CLIENT)
public final class AvatarClient {

    private AvatarClient() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(AvatarEntities.AVATAR.get(), AvatarRenderer::new);
    }

    /** 蝙蝠物品走自定义渲染器（v1.10.0；注册面=RegisterClientExtensionsEvent，mod 总线）。 */
    @SubscribeEvent
    public static void onRegisterClientExtensions(
            net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event) {
        event.registerItem(new net.neoforged.neoforge.client.extensions.common.IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return BatItemRenderer.INSTANCE;
            }
        }, io.github.piwugu.snowleopard.item.BatItems.BAT.get());
    }

    /** 渲染器本体（贴图原版、零新资源）。 */
    public static final class AvatarRenderer extends MobRenderer<AvatarEntity, OcelotModel<AvatarEntity>> {

        private static final ResourceLocation TEXTURE =
                ResourceLocation.withDefaultNamespace("textures/entity/cat/ocelot.png");

        public AvatarRenderer(EntityRendererProvider.Context context) {
            super(context, new OcelotModel<>(context.bakeLayer(ModelLayers.OCELOT)), 0.4f);
        }

        @Override
        public ResourceLocation getTextureLocation(AvatarEntity entity) {
            return TEXTURE;
        }
    }

    /**
     * 蝙蝠物品渲染器（v1.10.0，2026-10-24 19:00 (UTC+8)，分析 §2.2）。
     *
     * <p><b>复用原版蝙蝠外观</b>（同载体复用豹猫的先例）：{@code BatModel} +
     * 原版贴图 {@code textures/entity/bat.png}，零新资源。物品端 BEWLR 与实体渲染器互不相干。
     *
     * <p><b>姿态</b>：物品端没有会 tick 的蝙蝠实体，{@code AnimationState} 不启动 →
     * {@code setupAnim} 里 {@code animate()} 空转。这里 reset 后手动展翼
     * （zRot 对称 ±π/4，翼尖 ±π/8）——物品形态下"展开的飞行蝙蝠"最可辨认。
     * <b>需真机肉眼确认</b>：展翼角度/朝向、y 偏移 19/16 是否居中、scale 0.45 是否爆格
     * （三个数字都是起点，不好看就调）。
     */
    public static final class BatItemRenderer
            extends net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer {

        public static final BatItemRenderer INSTANCE = new BatItemRenderer();

        private static final ResourceLocation TEXTURE =
                ResourceLocation.withDefaultNamespace("textures/entity/bat.png");

        private final net.minecraft.client.model.BatModel model;

        private BatItemRenderer() {
            // BEWLR 需要 dispatcher/modelSet——客户端初始化后 Minecraft 实例已可用
            // （RegisterClientExtensionsEvent 在 mod 构造期触发，但渲染器本体是惰性单例、
            //  首次渲染前 Minecraft.getInstance() 已就绪——这里构造也在首次 getCustomRenderer 时）
            super(net.minecraft.client.Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                    net.minecraft.client.Minecraft.getInstance().getEntityModels());
            this.model = new net.minecraft.client.model.BatModel(
                    net.minecraft.client.Minecraft.getInstance().getEntityModels()
                            .bakeLayer(net.minecraft.client.model.geom.ModelLayers.BAT));
        }

        @Override
        public void renderByItem(net.minecraft.world.item.ItemStack stack,
                                 net.minecraft.world.item.ItemDisplayContext displayContext,
                                 com.mojang.blaze3d.vertex.PoseStack poseStack,
                                 net.minecraft.client.renderer.MultiBufferSource buffer,
                                 int packedLight, int packedOverlay) {
            poseStack.pushPose();
            // v1.10.3（真机裁决"蝙蝠面向玩家前方"）：平飞姿态里头朝局部 +z——
            // 第三人称手持时局部 +z = 玩家面朝方向，看上去"头朝前飞的标本"不对劲；
            // 掉落物/展示框同理。这三个 context 里再绕 X 转 −90°（头 (0,0,1)→(0,1,0)）
            // 变立式拎蝙蝠（头朝上）；GUI 和第一人称保持平飞（第一人称头朝视向自然，
            // GUI 斜视角 [20,-40,0] 也认得出来）。
            // 变换链顺序：mulPose 最先调用=最后作用于顶点（最外层）——先对中、再缩放、
            // 最后立式，转完模型仍以原点为中心，不会甩出格。
            if (displayContext == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                    || displayContext == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                    || displayContext == net.minecraft.world.item.ItemDisplayContext.FIXED
                    || displayContext == net.minecraft.world.item.ItemDisplayContext.GROUND) {
                poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90.0F));
            }
            // v1.10.2 顺序有讲究：scale 先、translate 后（后调用的先作用于顶点——
            // 要"先对中、再整体缩放"，translate 的量才能不被 scale 乘歪）。
            // 转轴后模型占据 z'∈[14,24]（原 y 范围），所以对中量作用在 z 上：−19/16。
            poseStack.scale(0.6F, 0.6F, 0.6F);   // v1.10.2：0.45 → 0.6（用户真机裁决"放大三分之一"）
            poseStack.translate(0.0F, 0.0F, -1.1875F);
            spreadWings();
            model.renderToBuffer(poseStack,
                    buffer.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(TEXTURE)),
                    packedLight, packedOverlay, 0xFFFFFFFF);
            poseStack.popPose();
        }

        /**
         * 手摆平飞姿态：dummy 不 tick、AnimationState 不启动，只能直接转部件（§2.2）。
         *
         * <p><b>v1.10.2（真机裁决"模型是倒着的"）</b>：BatModel 建模默认就是倒挂姿态
         * （body 从 y=17 向下延伸到 24，head 在 y=14..17——竖直、头在上、脚在下），
         * 上版只摆了翅膀没转身体。这里把 root 绕 X 转 +90°：y'=-z、z'=+y → 身体转成水平、
         * 头（y=14..17）落在 +z 端（朝观察者）、翅膀仍在 ±x 水平面展开——俯视平飞蝙蝠。
         * 旋转绕 root 原点，模型 y 全为正（14..24）→ 转后整体在 z'=14..24，
         * 由 poseStack 的 translate(0,0,-19/16) 对中，不会甩出格。
         */
        private void spreadWings() {
            model.root().getAllParts().forEach(net.minecraft.client.model.geom.ModelPart::resetPose);
            model.root().xRot = (float) (Math.PI / 2);
            var body = model.root().getChild("body");
            body.getChild("right_wing").zRot = (float) (Math.PI / 4);
            body.getChild("left_wing").zRot = -(float) (Math.PI / 4);
            body.getChild("right_wing").getChild("right_wing_tip").zRot = (float) (Math.PI / 8);
            body.getChild("left_wing").getChild("left_wing_tip").zRot = -(float) (Math.PI / 8);
        }
    }
}
