package io.github.piwugu.snowleopard.client;

import io.github.piwugu.snowleopard.AvatarEntity;
import io.github.piwugu.snowleopard.client.model.SnowLeopardModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * 雪豹载体渲染器（SnowLeopardModel）。
 *
 * <p>贴图 128x128、约 74% 全透明（毛发镂空）→ 必须走 cutout。
 * EntityModel.renderType 默认就是 entityCutoutNoCull，故不需要覆写。
 */
public final class SnowLeopardRenderer extends MobRenderer<AvatarEntity, SnowLeopardModel> {

    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "snowleopard", "textures/entity/snow_leopard.png");

    public SnowLeopardRenderer(EntityRendererProvider.Context context) {
        super(context, new SnowLeopardModel(context.bakeLayer(SnowLeopardModel.LAYER_LOCATION)), 0.6F);
    }

    @Override
    public ResourceLocation getTextureLocation(AvatarEntity entity) {
        return TEXTURE;
    }
}
