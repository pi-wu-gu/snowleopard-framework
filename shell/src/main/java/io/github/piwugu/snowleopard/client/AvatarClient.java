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
        }, io.github.piwugu.snowleopard.mc.BatItems.BAT.get());
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
            // 抵消 BatModel body/head 根偏移（y=17 起，整体重心 ≈19/16）→ 模型中心到原点
            poseStack.translate(0.0, 1.1875, 0.0);
            poseStack.scale(0.45F, 0.45F, 0.45F);   // 物品格 16px；不缩放必爆格
            spreadWings();
            model.renderToBuffer(poseStack,
                    buffer.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(TEXTURE)),
                    packedLight, packedOverlay, 0xFFFFFFFF);
            poseStack.popPose();
        }

        /** 手摆平飞姿态：dummy 不 tick、AnimationState 不启动，只能直接转部件（§2.2）。 */
        private void spreadWings() {
            model.root().getAllParts().forEach(net.minecraft.client.model.geom.ModelPart::resetPose);
            var body = model.root().getChild("body");
            var head = model.root().getChild("head");
            head.xRot = -0.6F;   // 头微抬（物品看着"朝上"，倒挂默认头朝下不好认）
            body.getChild("right_wing").zRot = (float) (Math.PI / 4);
            body.getChild("left_wing").zRot = -(float) (Math.PI / 4);
            body.getChild("right_wing").getChild("right_wing_tip").zRot = (float) (Math.PI / 8);
            body.getChild("left_wing").getChild("left_wing_tip").zRot = -(float) (Math.PI / 8);
        }
    }
}
