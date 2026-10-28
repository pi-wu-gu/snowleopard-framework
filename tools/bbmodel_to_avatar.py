# -*- coding: utf-8 -*-
"""
bbmodel -> SnowLeopard 载体模型（可重复生成，不改源文件）

用法
----
    python tools/bbmodel_to_avatar.py                       # 用默认路径
    python tools/bbmodel_to_avatar.py --bbmodel <path>      # 指定源
    python tools/bbmodel_to_avatar.py --dry-run             # 只打印结构，不写文件

产物（全部落在 framework 仓内）
------------------------------
    shell/src/main/java/io/github/piwugu/snowleopard/client/model/SnowLeopardModel.java
    shell/src/main/java/io/github/piwugu/snowleopard/client/SnowLeopardRenderer.java
    shell/src/main/resources/assets/snowleopard/textures/entity/snow_leopard.png

与旧 `bbmodel_analysis/bbmodel_to_java.py` 的关键差异（都是踩过的坑）
------------------------------------------------------------------
1. **保留 group 的初始旋转**：旧生成器只写 `PartPose.offset`，把 bbmodel 里
   tail_1 的 20°、ear_right 的 ±20°、jaw 的 −10° 全丢了 → 尾巴直挺、耳朵角度不对。
   本版用 `PartPose.offsetAndRotation(x,y,z, xRot,yRot,zRot)`，且 setupAnim 里
   **在初始角度上叠加**而不是覆盖（旧版 `tail1.xRot = -0.12f` 直接把 20° 抹掉）。
2. **不改源、不覆盖眼睛坐标**：旧版有 EYE_OVERRIDE 会强制改写眼部数值，与手改冲突。
3. **输出直接指向 framework 源码树**，不再手抄。
4. **动画按存在的字段条件生成**：没有 lid/pupil/jaw 的模型不会生成对应语句（避免编译失败）。

坐标约定
--------
Blockbench: Y 向上，脚底 y = 0
Minecraft : Y 向下，脚底 y = 24（LivingEntityRenderer 的 translate(0,-1.501,0) 基准）
转换：java_y = 24 - bb_y
"""

import argparse
import base64
import json
import math
import os
import re
import sys

DEFAULT_BB = r"C:\Users\w0801\Documents\WorkBuddy\雪豹\bbmodel_analysis\snow_leopard_fixed.bbmodel"

MODID = "snowleopard"
PKG = "io.github.piwugu.snowleopard"
ENTITY = "AvatarEntity"
MODEL_CLASS = "SnowLeopardModel"
RENDERER_CLASS = "SnowLeopardRenderer"
LAYER_NAME = "snow_leopard"
TEX_SIZE = None          # 从 bbmodel 的 resolution 读
STAND_Y = 24.0


# ---------------------------------------------------------------- 工具

def camel(name):
    parts = re.split(r"[^0-9a-zA-Z]+", name)
    parts = [p for p in parts if p]
    if not parts:
        return "part"
    head = parts[0][0].lower() + parts[0][1:]
    return head + "".join(p[0].upper() + p[1:] for p in parts[1:])


def jf(v):
    s = ("%.4f" % float(v)).rstrip("0").rstrip(".")
    if s in ("-0", "", "-"):
        s = "0"
    return s + "F"


def rad(deg):
    return float(deg) * math.pi / 180.0


def has_rotation(rot):
    return rot and any(abs(float(x)) > 1e-9 for x in rot)


def to_mc_rotation(rot):
    """
    Blockbench（Y 向上，右手系）→ Minecraft 模型空间（Y 向下，左手系）。

    **X 与 Z 取反、Y 不变**——从右手系换到左手系，绕 X / Z 的正方向翻转，
    绕 Y 的两侧同时翻转因而抵消。
    实测依据（2026-10-27 真机）：tail_1 的 +20° 不取反会向下垂、head_nose 的 −20°
    会让鼻子朝反方向翘、body_mid 的 2° 也是反的——三个纯 X 部件全部反向。

    漏掉这一步的后果是"模型看着对但不像"：直尾、塌鼻、耳朵垂下来。
    """
    if not rot:
        return (0.0, 0.0, 0.0)
    return (-float(rot[0]), float(rot[1]), -float(rot[2]))


# ---------------------------------------------------------------- 解析

def parse(bb):
    groups = {g["uuid"]: g for g in bb.get("groups", [])}
    elements = {e["uuid"]: e for e in bb.get("elements", [])}

    bones = []          # 顺序 = outliner 先序
    seen = set()

    def walk(node, parent_name, parent_pivot):
        if isinstance(node, str):
            return
        uuid = node.get("uuid")
        g = groups.get(uuid)
        name = node.get("name") or (g.get("name") if g else None) or ("group_" + str(uuid)[:6])
        origin = (g or node).get("origin") or [0, 0, 0]
        rotation = (g or node).get("rotation") or [0, 0, 0]
        meshes = []
        child_groups = []
        for child in node.get("children", []):
            if isinstance(child, str):
                e = elements.get(child)
                if e is not None:
                    meshes.append(e)
            else:
                child_groups.append(child)
        bones.append({
            "name": name,
            "parent": parent_name,
            "pivot_bb": origin,
            "rotation_bb": list(rotation),          # 打印/对照用
            "rotation": list(to_mc_rotation(rotation)),   # 写进 Java 的值
            "meshes": meshes,
        })
        seen.add(name)
        for cg in child_groups:
            walk(cg, name, origin)

    for root in bb.get("outliner", []):
        walk(root, None, [0, 0, 0])
    return bones


def to_java_point(p):
    return (float(p[0]), STAND_Y - float(p[1]), float(p[2]))


# ---------------------------------------------------------------- 生成：模型类

HEAD_TMPL = """package {pkg}.client.model;

import {pkg}.{entity};
import {pkg}.AvatarEyes;
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
 * 雪豹载体模型（{bones} 骨骼 / {cubes} 立方体 / {tex}x{tex} 贴图）。
 *
 * <p>由 tools/bbmodel_to_avatar.py 从 {bbname} 生成，**不要手改**——改 bbmodel 后重跑脚本。
 * 坐标系：java_y = {stand} - bb_y（脚底落在 {stand}，配合 LivingEntityRenderer 的 -1.501 平移贴地）。
 * group 的初始旋转用 PartPose.offsetAndRotation 保留，setupAnim 在初始角上叠加。
 */
public class {cls} extends EntityModel<{entity}> {{

    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(
                    ResourceLocation.fromNamespaceAndPath("{modid}", "{layer}"), "main");

    // 瞳孔默认下移 0.2px、眼皮最小高度 0.5px —— 两个量都在 AvatarEyes（纯函数、无客户端依赖），
    // 这样服务端断言能直接打它们；数值变化不需要改模型，重跑生成器也不会覆盖。

    private final ModelPart root;
"""


def build_model(bones, tex, bb_name):
    ncubes = sum(len(b["meshes"]) for b in bones)
    out = [HEAD_TMPL.format(pkg=PKG, entity=ENTITY, bones=len(bones), cubes=ncubes, tex=tex,
                            bbname=bb_name, stand=int(STAND_Y), cls=MODEL_CLASS,
                            modid=MODID, layer=LAYER_NAME)]

    for b in bones:
        out.append("    private final ModelPart %s;\n" % camel(b["name"]))
    out.append("\n")

    out.append("    public %s(ModelPart root) {\n" % MODEL_CLASS)
    out.append("        this.root = root;\n")
    for b in bones:
        parent = "root" if b["parent"] is None else "this." + camel(b["parent"])
        out.append('        this.%s = %s.getChild("%s");\n'
                   % (camel(b["name"]), parent, b["name"]))
    out.append("    }\n\n")

    out.append("    public static LayerDefinition createBodyLayer() {\n")
    out.append("        MeshDefinition mesh = new MeshDefinition();\n")
    out.append("        PartDefinition root = mesh.getRoot();\n\n")

    for b in bones:
        pivot_j = to_java_point(b["pivot_bb"])
        if b["parent"] is None:
            offset = pivot_j
            parent_var = "root"
        else:
            parent_bb = next(x for x in bones if x["name"] == b["parent"])
            parent_j = to_java_point(parent_bb["pivot_bb"])
            offset = tuple(pivot_j[i] - parent_j[i] for i in range(3))
            parent_var = camel(b["parent"])

        out.append('        PartDefinition %s = %s.addOrReplaceChild("%s",\n'
                   '                CubeListBuilder.create()\n'
                   % (camel(b["name"]), parent_var, b["name"]))

        meshes = b["meshes"]
        if not meshes:
            # 纯容器 group（无直属 cube）：给一个空 builder
            out.append("                CubeListBuilder.create(),\n")
        else:
            for i, m in enumerate(meshes):
                frm, to = m["from"], m["to"]
                size = [float(to[k]) - float(frm[k]) for k in range(3)]
                inflate = float(m.get("inflate", 0) or 0)
                # 负膨胀不得吃掉整个厚度（否则尺寸变负 → 模型崩）
                if inflate < 0 and abs(inflate) * 2 >= min(size):
                    inflate = 0.0
                min_j = (float(frm[0]), STAND_Y - float(to[1]), float(frm[2]))
                bx, by, bz = (min_j[i] - pivot_j[i] for i in range(3))
                uv = m.get("uv_offset") or [0, 0]
                # CubeListBuilder 是链式调用：中间不能有逗号，只有最后一个 addBox 后要逗号
                sep = ",\n" if i == len(meshes) - 1 else "\n"
                out.append("                .texOffs(%d, %d)\n" % (uv[0], uv[1]))
                out.append("                .addBox(%s, %s, %s, %s, %s, %s, new CubeDeformation(%s))%s"
                           % (jf(bx), jf(by), jf(bz), jf(size[0]), jf(size[1]), jf(size[2]),
                              jf(inflate), sep))

        rot = b["rotation"]
        if has_rotation(rot):
            out.append("                PartPose.offsetAndRotation(%s, %s, %s, %s, %s, %s));\n\n"
                       % (jf(offset[0]), jf(offset[1]), jf(offset[2]),
                          jf(rad(rot[0])), jf(rad(rot[1])), jf(rad(rot[2]))))
        else:
            out.append("                PartPose.offset(%s, %s, %s));\n\n"
                       % (jf(offset[0]), jf(offset[1]), jf(offset[2])))

    out.append("        return LayerDefinition.create(mesh, %d, %d);\n" % (tex, tex))
    out.append("    }\n\n")
    out.append(build_setup_anim(bones))
    out.append("""
    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight,
                               int packedOverlay, int color) {
        this.root.render(poseStack, buffer, packedLight, packedOverlay, color);
    }
}
""")
    return "".join(out)


def build_setup_anim(bones):
    names = {b["name"] for b in bones}
    rot = {b["name"]: b["rotation"] for b in bones}
    L = []
    A = L.append

    A("    /**\n"
      "     * 每帧驱动。所有值都在 bbmodel 的**初始角度上叠加**——直接赋值会把建模时的\n"
      "     * 姿态（尾巴 20°、耳朵 ±20°、下颚 −10°）抹平，模型看着\"对但不像\"。\n"
      "     */\n")
    A("    @Override\n")
    A("    public void setupAnim(%s entity, float limbSwing, float limbSwingAmount,\n"
      "                          float ageInTicks, float netHeadYaw, float headPitch) {\n" % ENTITY)

    # 头部跟随
    if "head" in names:
        A("        this.head.yRot = netHeadYaw * ((float) Math.PI / 180F);\n")
        A("        this.head.xRot = headPitch * ((float) Math.PI / 180F);\n")

    # 对角步态
    legs = [n for n in ("leg_front_left", "leg_front_right", "leg_hind_left", "leg_hind_right")
            if n in names]
    if legs:
        A("\n        // 对角步态（腿挂在 body 之下，故不做躯干起伏）\n")
        A("        float stride = Mth.cos(limbSwing * 0.6662F) * 1.4F * limbSwingAmount;\n")
        A("        float strideOpp = Mth.cos(limbSwing * 0.6662F + (float) Math.PI) * 1.4F * limbSwingAmount;\n")
        pairs = {"leg_front_left": "stride", "leg_hind_right": "stride",
                 "leg_front_right": "strideOpp", "leg_hind_left": "strideOpp"}
        for n in legs:
            base = rot[n][0] if has_rotation(rot[n]) else 0.0
            expr = pairs[n] if abs(base) < 1e-9 else "%s + %s" % (jf(rad(base)), pairs[n])
            A("        this.%s.xRot = %s;\n" % (camel(n), expr))

    # 尾巴
    tails = [n for n in names if n.startswith("tail_")]
    if tails:
        tails.sort(key=lambda n: int(re.sub(r"\D", "", n) or 0))
        A("\n        // 尾巴：逐段衰减的波浪，叠加在各段初始角上\n")
        A("        float sway = Mth.cos(ageInTicks * 0.08F) * 0.10F * (0.4F + limbSwingAmount);\n")
        for i, n in enumerate(tails):
            base = rot[n][0] if has_rotation(rot[n]) else 0.0
            decay = max(0.35, 1.0 - i * 0.11)
            if abs(base) < 1e-9:
                A("        this.%s.yRot = sway * %.2fF;\n" % (camel(n), decay))
            else:
                A("        this.%s.xRot = %s + Mth.cos(ageInTicks * 0.05F) * 0.02F;\n"
                  % (camel(n), jf(rad(base))))
                A("        this.%s.yRot = sway * %.2fF;\n" % (camel(n), decay))

    # 耳朵
    ears = [n for n in names if n.startswith("ear_")]
    if ears:
        A("\n        // 耳朵：低频抖动\n")
        A("        float flick = Mth.cos(ageInTicks * 0.1F) * 0.05F;\n")
        for i, n in enumerate(ears):
            base = rot[n][2] if has_rotation(rot[n]) else 0.0
            sign = 1.0 if i == 0 else -1.0
            expr = "flick" if abs(base) < 1e-9 else "%s %s flick" % (
                jf(rad(base)), "+" if sign > 0 else "-")
            A("        this.%s.zRot = %s;\n" % (camel(n), expr))

    # 眼皮（眨眼）
    lids = [n for n in names if n.startswith("eye_lid")]
    if lids:
        A("\n        // 眨眼（2026-10-28 改法）：眼皮改成**高度可变**，常态保留最小高度 0.5px 的窄边。\n")
        A("        //\n")
        A("        // 旧做法是 visible 开关：睁眼时高度 0、闭眼时整块盖住 —— 视觉上是\"灰白毛突然\n")
        A("        // 出现又突然消失\"，而不是\"眼皮滑下来\"。现在常态就有 0.5px，眨眼时展开到满高。\n")
        A("        // 缩放绕 part pivot（盒子中心），所以展开时要把中心上移 (1 − s) 才能**固定上缘**。\n")
        A("        boolean blink = (ageInTicks % 100.0F) < 4.0F;\n")
        A("        float lidScale = AvatarEyes.lidScale(blink ? AvatarEyes.LID_FULL_HEIGHT\n"
          "                : AvatarEyes.LID_MIN_HEIGHT);\n")
        for n in lids:
            A("        this.%s.resetPose();\n" % camel(n))
            A("        this.%s.yScale = lidScale;\n" % camel(n))
            A("        this.%s.y += AvatarEyes.lidYOffset(lidScale);\n" % camel(n))

    # 瞳孔（预留：几何修好后自动生效）
    pupils = [n for n in names if n.startswith("eye_pupil")]
    if pupils:
        A("\n        // 瞳孔：随头部朝向做微小偏移（±0.2px）。\n")
        A("        // 必须先 resetPose() 回到 PartPose 基准再加：ModelPart.x/y/z 就是基准位移本身，\n")
        A("        // 直接 x = gazeX 会把 PartPose 的偏移整个抹掉——v1.12.2 真机即如此，\n")
        A("        // 瞳孔掉到眼睛组 pivot（= 头的 pivot 高度），看起来就是\"跑到下颚中间\"。\n")
        A("        // 左右同值（MC 玩家模型的规矩，别镜像）；y 再叠一步默认下移 0.2。\n")
        A("        float gazeX = Mth.clamp(netHeadYaw * 0.008F, -0.2F, 0.2F);\n")
        A("        float gazeY = Mth.clamp(headPitch * 0.006F, -0.15F, 0.15F);\n")
        for n in pupils:
            A("        this.%s.resetPose();\n" % camel(n))
            A("        this.%s.x += gazeX;\n" % camel(n))
            A("        this.%s.y = AvatarEyes.pupilRestY(this.%s.y) + gazeY;   "
              "// 基准 + 默认下移 0.2（+y 才是下）\n" % (camel(n), camel(n)))

    # 下颚（外部状态驱动）
    if "jaw" in names:
        base = rot["jaw"][0] if has_rotation(rot["jaw"]) else 0.0
        A("\n        // 下颚：jawOpen 0=闭 1=张，由宿主状态（被打/说话/进食）驱动\n")
        A("        this.jaw.xRot = %s - this.jawOpen * 0.45F;\n" % jf(rad(base)))
        A("    }\n")

    A("\n    /** 下颚张开程度（0=闭，1=全张）。由渲染侧按状态写入。 */\n")
    A("    public float jawOpen;\n")
    return "".join(L)


# ---------------------------------------------------------------- 生成：渲染器

RENDERER_TMPL = """package {pkg}.client;

import {pkg}.{entity};
import {pkg}.client.model.{model};
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * 雪豹载体渲染器（{model}）。
 *
 * <p>贴图 {tex}x{tex}、约 74% 全透明（毛发镂空）→ 必须走 cutout。
 * EntityModel.renderType 默认就是 entityCutoutNoCull，故不需要覆写。
 */
public final class {renderer} extends MobRenderer<{entity}, {model}> {{

    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "{modid}", "textures/entity/snow_leopard.png");

    public {renderer}(EntityRendererProvider.Context context) {{
        super(context, new {model}(context.bakeLayer({model}.LAYER_LOCATION)), {shadow}F);
    }}

    @Override
    public ResourceLocation getTextureLocation({entity} entity) {{
        return TEXTURE;
    }}
}}
"""


def build_renderer(tex, shadow):
    return RENDERER_TMPL.format(pkg=PKG, entity=ENTITY, model=MODEL_CLASS,
                                renderer=RENDERER_CLASS, tex=tex, modid=MODID,
                                shadow=("%.2f" % shadow).rstrip("0").rstrip("."))


# ---------------------------------------------------------------- 贴图导出

def export_texture(bb, out_path):
    texs = bb.get("textures") or []
    if not texs:
        return None
    src = texs[0].get("source") or ""
    if "," in src and src.startswith("data:"):
        raw = base64.b64decode(src.split(",", 1)[1])
    else:
        return None
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(raw)
    return len(raw)


# ---------------------------------------------------------------- 自检

def self_check(java, bones):
    problems = []
    ncubes = sum(len(b["meshes"]) for b in bones)
    if java.count("{") != java.count("}"):
        problems.append("大括号不平衡")
    if java.count("(") != java.count(")"):
        problems.append("小括号不平衡")
    for bad in ("NaN", "Infinity", "nanF"):
        if bad in java:
            problems.append("异常数值 " + bad)
    if re.search(r",\s*,", java):
        problems.append("连续逗号")
    if java.count("addOrReplaceChild") != len(bones):
        problems.append("addOrReplaceChild %d != 骨骼 %d"
                        % (java.count("addOrReplaceChild"), len(bones)))
    # 只数生成出来的调用行（带缩进），注释里出现 "PartPose." 不算
    # 位移类动画必须先 resetPose 再 "+="：直接赋值会抹掉 PartPose 基准（v1.12.2 真机踩过）
    for line in java.splitlines():
        t = line.strip()
        if t.startswith("this.") and t.endswith(";") and (".x =" in t or ".y =" in t or ".z =" in t):
            problems.append("位移直接赋值会抹掉基准: " + t)

    n_pose = java.count("                PartPose.offset(") + \
        java.count("                PartPose.offsetAndRotation(")
    if n_pose != len(bones):
        problems.append("PartPose 数量 %d != 骨骼 %d" % (n_pose, len(bones)))
    if java.count(".texOffs(") != ncubes:
        problems.append("texOffs %d != 网格 %d" % (java.count(".texOffs("), ncubes))
    if java.count(".addBox(") != ncubes:
        problems.append("addBox 数量不匹配")
    for b in bones:
        if ("this.%s = " % camel(b["name"])) not in java:
            problems.append("成员未赋值: " + camel(b["name"]))
    names = [b["name"] for b in bones]
    if len(names) != len(set(names)):
        problems.append("骨骼名重复")
    return problems


def footprint(bones):
    """结构指纹：以后换模型时对比用（多了/少了哪个 part、pivot 有没有动）。"""
    lines = []
    for b in bones:
        p = b["pivot_bb"]
        r = b["rotation_bb"]
        rmc = b["rotation"]
        note = "" if not has_rotation(r) else "  rot_mc=(%.1f,%.1f,%.1f)" % (rmc[0], rmc[1], rmc[2])
        lines.append("%-20s parent=%-20s pivot=(%.2f,%.2f,%.2f) rot_bb=(%.1f,%.1f,%.1f) cubes=%d%s"
                     % (b["name"], str(b["parent"]), p[0], p[1], p[2], r[0], r[1], r[2],
                        len(b["meshes"]), note))
    return lines


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bbmodel", default=DEFAULT_BB)
    ap.add_argument("--repo", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    help="snowleopard-framework 仓根目录")
    ap.add_argument("--shadow", type=float, default=0.6)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    if not os.path.isfile(a.bbmodel):
        print("[error] 找不到 bbmodel: %s" % a.bbmodel, file=sys.stderr)
        return 2

    bb = json.load(open(a.bbmodel, encoding="utf-8"))
    res = bb.get("resolution") or {}
    tex = int(res.get("width") or 64)
    bones = parse(bb)
    if not bones:
        print("[error] bbmodel 里没有解析到任何骨骼", file=sys.stderr)
        return 2

    print("源: %s" % a.bbmodel)
    print("纹理: %dx%d | 骨骼 %d | 立方体 %d"
          % (tex, int(res.get("height") or tex), len(bones),
             sum(len(b["meshes"]) for b in bones)))
    for line in footprint(bones):
        print("  " + line)

    java = build_model(bones, tex, os.path.basename(a.bbmodel))
    renderer = build_renderer(tex, a.shadow)
    problems = self_check(java, bones)
    if problems:
        print("\n[自检失败] " + "; ".join(problems), file=sys.stderr)
        return 1
    print("\n自检: 通过")

    if a.dry_run:
        print("\n[dry-run] 不写文件")
        return 0

    model_path = os.path.join(a.repo, "shell/src/main/java", PKG.replace(".", "/"),
                              "client/model", MODEL_CLASS + ".java")
    renderer_path = os.path.join(a.repo, "shell/src/main/java", PKG.replace(".", "/"),
                                 "client", RENDERER_CLASS + ".java")
    tex_path = os.path.join(a.repo, "shell/src/main/resources/assets", MODID,
                            "textures/entity/snow_leopard.png")
    for p, content in ((model_path, java), (renderer_path, renderer)):
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        print("[ok] %s" % os.path.relpath(p, a.repo))

    n = export_texture(bb, tex_path)
    if n:
        print("[ok] %s (%d B)" % (os.path.relpath(tex_path, a.repo), n))
    else:
        print("[warn] bbmodel 没有内嵌贴图，请手动放 %s" % tex_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
