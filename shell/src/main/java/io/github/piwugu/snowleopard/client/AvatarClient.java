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
}
