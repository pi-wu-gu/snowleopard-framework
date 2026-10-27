#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""受限核心 bbmodel → 方块模型 JSON + 贴图 PNG（+ 可选等轴预览图）。

用法（路径全部入参，脚本不含本机路径——两仓公开，2026-10-27 铁律）：

    python tools/bbmodel_to_blockmodel.py <受限核心.bbmodel> <shell/src/main/resources> [--preview out.png]

产物（相对 resources 根）：
    assets/snowleopard/models/block/shackled_core.json
    assets/snowleopard/textures/block/shackled_core.png

单位口径（分析 v3 §二，三处一致）：bbmodel 1 模型单位 = **1 格**，整体 6.95→9.05 ≈ 2.1 格。
java_block JSON 是 16 单位 = 1 格，故几何体绕 (8,8,8) 整体放大 16 倍：
    p_json = (p_bb - 8) * 16 + 8    →    跨度 -8.8 .. 24.8（合法区间 [-16, 32]）
UV/贴图不缩放（与 Blockbench 预览同观感）。源 bbmodel 改动后必须重跑本脚本（产物过期铁律）。

只支持无旋转 cube（原版 JSON 只能表达 ±22.5/±45 单轴旋转）；遇旋转直接报错，不静默丢。
"""

import argparse
import base64
import json
import struct
import sys
import zlib
from pathlib import Path

MODID = "snowleopard"
BLOCK_ID = "shackled_core"
CENTER = 8.0          # bbmodel 与 java_block JSON 共用的方块中心
SCALE = 16.0          # 1 模型单位 = 1 格 = 16 JSON 单位


# ---------- bbmodel → 方块模型 ----------

def load_bbmodel(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def check_no_rotation(model: dict) -> None:
    for i, el in enumerate(model.get("elements", [])):
        rot = el.get("rotation") or [0, 0, 0]
        if any(abs(x) > 1e-9 for x in rot):
            sys.exit(f"[错误] cube #{i} 含旋转 {rot}——原版 JSON 无法表达，先在 Blockbench 里摆正")
    for g in model.get("groups", []) or []:
        rot = (g.get("rotation") if isinstance(g, dict) else None) or [0, 0, 0]
        if any(abs(x) > 1e-9 for x in rot):
            sys.exit(f"[错误] 分组 {g.get('name')} 含旋转 {rot}——先在 Blockbench 里摆正")


def scale_point(p):
    return [round((float(v) - CENTER) * SCALE + CENTER, 4) for v in p]


def convert_elements(model: dict) -> list:
    out = []
    for el in model.get("elements", []):
        faces = {}
        for direction, face in (el.get("faces") or {}).items():
            entry = {"uv": [float(v) for v in face["uv"]], "texture": "#0"}
            uv_rot = int(face.get("rotation") or 0)
            if uv_rot:
                entry["rotation"] = uv_rot
            faces[direction] = entry
        out.append({
            "from": scale_point(el["from"]),
            "to": scale_point(el["to"]),
            "faces": faces,
        })
    return out


def extract_texture(model: dict, dest: Path) -> None:
    textures = model.get("textures") or []
    if len(textures) != 1:
        sys.exit(f"[错误] 贴图数 = {len(textures)}（预期 1）——按需扩展本脚本")
    source = textures[0].get("source") or ""
    marker = "base64,"
    if marker not in source:
        sys.exit("[错误] 贴图 source 不是 data URL（缺 base64,）")
    raw = base64.b64decode(source.split(marker, 1)[1])
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        sys.exit("[错误] 贴图不是 PNG")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(raw)


def build_model_json(model: dict) -> dict:
    texture_ref = f"{MODID}:block/{BLOCK_ID}"
    return {
        "ambientocclusion": bool(model.get("ambientocclusion", False)),
        "textures": {"0": texture_ref, "particle": texture_ref},
        "elements": convert_elements(model),
    }


# ---------- 纯 Python PNG 读写（预览用，无 PIL） ----------

def decode_png(data: bytes):
    """返回 (width, height, rows)，rows[y] = RGBA 字节串。仅支持 8bit 灰度/RGB/调色板/RGBA。"""
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos, idat, plte, trns, meta = 8, b"", b"", b"", None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            meta = struct.unpack(">IIBBBBB", chunk)  # w h depth color compress filter interlace
        elif ctype == b"PLTE":
            plte = chunk
        elif ctype == b"tRNS":
            trns = chunk
        elif ctype == b"IDAT":
            idat += chunk
        elif ctype == b"IEND":
            break
        pos += 12 + length
    w, h, depth, color, _compress, _filter, interlace = meta
    if depth != 8 or interlace:
        sys.exit(f"[错误] 预览不支持的 PNG（depth={depth} interlace={interlace}）")
    bpp = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev = [], bytearray(stride)
    p = 0
    for _ in range(h):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                line[i] = (line[i] + a) & 0xFF
            elif f == 2:
                line[i] = (line[i] + b) & 0xFF
            elif f == 3:
                line[i] = (line[i] + (a + b) // 2) & 0xFF
            elif f == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        prev = line
        out = bytearray()
        for x in range(w):
            px = line[x * bpp:(x + 1) * bpp]
            if color == 6:
                out += px
            elif color == 2:
                out += px + b"\xff"
            elif color == 3:
                idx = px[0]
                out += plte[idx * 3:idx * 3 + 3] + (bytes([trns[idx]]) if idx < len(trns) else b"\xff")
            elif color == 0:
                out += bytes([px[0], px[0], px[0], 0xFF])
            else:  # 4
                out += bytes([px[0], px[0], px[0], px[1]])
        rows.append(bytes(out))
    return w, h, rows


def encode_png(w, h, pixels) -> bytes:
    """pixels = list of rows，每行 RGBA 字节串。"""
    def chunk(ctype, body):
        c = ctype + body
        return struct.pack(">I", len(body)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + row for row in pixels)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


# ---------- 等轴预览（外观肉眼收口的低成本替身） ----------

def render_preview(model: dict, tex_rows, tex_w, tex_h, out_path: Path) -> None:
    import math
    W = H = 560
    SS = 110.0                     # 1 格 = 110 px
    yaw, pitch = math.radians(45.0), math.radians(30.0)
    cyw, syw, cp, sp = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch)

    def raw_project(p):
        x, y, z = p                       # bbmodel 空间：y 向上，1 单位 = 1 格
        xr = x * cyw - z * syw
        zr = x * syw + z * cyw
        yr = y * cp - zr * sp
        depth = y * sp + zr * cp          # 越大越靠相机
        return (xr * SS, -yr * SS, depth)

    # 以模型中心（= 方块中心）重定画面中心，避免顶点跑出画布
    ox, oy, _ = raw_project((CENTER, CENTER, CENTER))

    def project(p):
        x, y, d = raw_project(p)
        return (x - ox + W / 2, y - oy + H / 2, d)

    # 六面法线在 Blockbench 空间（y 向上）
    normals = {
        "up": (0, 1, 0), "down": (0, -1, 0),
        "north": (0, 0, -1), "south": (0, 0, 1),
        "east": (1, 0, 0), "west": (-1, 0, 0),
    }
    light = (0.4, 0.8, 0.45)              # 归一化后的主光方向（右上前）
    ln = math.sqrt(sum(c * c for c in light))
    light = tuple(c / ln for c in light)

    faces = []
    for el in model.get("elements", []):
        fx, fy, fz = (float(v) for v in el["from"])
        tx, ty, tz = (float(v) for v in el["to"])
        corners = {
            "up": [(fx, ty, fz), (tx, ty, fz), (tx, ty, tz), (fx, ty, tz)],
            "down": [(fx, fy, fz), (tx, fy, fz), (tx, fy, tz), (fx, fy, tz)],
            "north": [(fx, fy, fz), (tx, fy, fz), (tx, ty, fz), (fx, ty, fz)],
            "south": [(fx, fy, tz), (tx, fy, tz), (tx, ty, tz), (fx, ty, tz)],
            "east": [(tx, fy, fz), (tx, fy, tz), (tx, ty, tz), (tx, ty, fz)],
            "west": [(fx, fy, fz), (fx, fy, tz), (fx, ty, tz), (fx, ty, fz)],
        }
        for direction, face in (el.get("faces") or {}).items():
            n = normals[direction]
            shade = 0.42 + 0.58 * max(0.0, sum(n[i] * light[i] for i in range(3)))
            u1, v1, u2, v2 = (float(v) for v in face["uv"])
            if u2 < u1:
                u1, u2 = u2, u1
            if v2 < v1:
                v1, v2 = v2, v1
            nx = max(1, int(round(u2 - u1)))
            ny = max(1, int(round(v2 - v1)))
            q = corners[direction]

            def bl(sx, sy, q=q):
                top = [q[0][k] + (q[1][k] - q[0][k]) * sx for k in range(3)]
                bot = [q[3][k] + (q[2][k] - q[3][k]) * sx for k in range(3)]
                return [top[k] + (bot[k] - top[k]) * sy for k in range(3)]

            for j in range(ny):
                for i in range(nx):
                    px = min(tex_w - 1, int(u1) + i)
                    py = min(tex_h - 1, int(v1) + j)
                    t = tex_rows[py][px * 4:px * 4 + 4]
                    if t[3] == 0:
                        continue          # 透明 texel 不铺（贴图大半透明）
                    quad = [bl(i / nx, j / ny), bl((i + 1) / nx, j / ny),
                            bl((i + 1) / nx, (j + 1) / ny), bl(i / nx, (j + 1) / ny)]
                    pts = [project(p) for p in quad]
                    depth = sum(p[2] for p in pts) / 4.0
                    faces.append((depth, pts,
                                  (int(t[0] * shade), int(t[1] * shade), int(t[2] * shade), 255)))

    img = [[(246, 243, 236, 255) for _ in range(W)] for _ in range(H)]   # 暖白底

    def fill_quad(pts, color):
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        y0 = max(0, int(min(ys)))
        y1 = min(H - 1, int(max(ys)) + 1)
        for y in range(y0, y1 + 1):
            inter = []
            for i in range(4):
                x1, y1p = pts[i][0], pts[i][1]
                x2, y2p = pts[(i + 1) % 4][0], pts[(i + 1) % 4][1]
                if (y1p <= y < y2p) or (y2p <= y < y1p):
                    inter.append(x1 + (y - y1p) * (x2 - x1) / (y2p - y1p))
            inter.sort()
            for i in range(0, len(inter) - 1, 2):
                xa = max(0, int(inter[i]))
                xb = min(W - 1, int(inter[i + 1]))
                for x in range(xa, xb + 1):
                    img[y][x] = color

    def draw_line(p1, p2, color):
        x1, y1 = int(p1[0]), int(p1[1])
        x2, y2 = int(p2[0]), int(p2[1])
        dx, dy = abs(x2 - x1), abs(y2 - y1)
        sx, sy = (1 if x1 < x2 else -1), (1 if y1 < y2 else -1)
        err = dx - dy
        while True:
            if 0 <= x1 < W and 0 <= y1 < H:
                img[y1][x1] = color
            if x1 == x2 and y1 == y2:
                break
            e2 = 2 * err
            if e2 > -dy:
                err -= dy
                x1 += sx
            if e2 < dx:
                err += dx
                y1 += sy

    for depth, pts, color in sorted(faces, key=lambda f: f[0]):      # 远 → 近
        fill_quad(pts, color)

    # 1 格参照框（放置后占的那格）：深蓝线框，画在最上层
    e = 7.5
    s = 8.5
    v = [(x, y, z) for x in (e, s) for y in (e, s) for z in (e, s)]
    edges = [(0, 1), (0, 2), (0, 4), (1, 3), (1, 5), (2, 3), (2, 6), (3, 7), (4, 5), (4, 6), (5, 7), (6, 7)]
    wire = (40, 80, 190, 255)
    for i, j in edges:
        draw_line(project(v[i]), project(v[j]), wire)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(encode_png(W, H, [bytes(b"".join(bytes(px) for px in row)) for row in img]))


# ---------- main ----------

def main() -> None:
    ap = argparse.ArgumentParser(description="受限核心 bbmodel → 方块模型 JSON + 贴图")
    ap.add_argument("bbmodel", type=Path, help="受限核心.bbmodel")
    ap.add_argument("resources", type=Path, help="shell/src/main/resources 目录")
    ap.add_argument("--preview", type=Path, default=None, help="可选：输出等轴预览 PNG")
    args = ap.parse_args()

    model = load_bbmodel(args.bbmodel)
    check_no_rotation(model)

    texture_path = args.resources / f"assets/{MODID}/textures/block/{BLOCK_ID}.png"
    model_path = args.resources / f"assets/{MODID}/models/block/{BLOCK_ID}.json"
    extract_texture(model, texture_path)
    model_path.parent.mkdir(parents=True, exist_ok=True)
    with open(model_path, "w", encoding="utf-8") as f:
        json.dump(build_model_json(model), f, ensure_ascii=False, indent=2)
        f.write("\n")

    els = model["elements"]
    lo = min(min(el["from"]) for el in els)
    hi = max(max(el["to"]) for el in els)
    print(f"[生成] {model_path}")
    print(f"[生成] {texture_path}")
    print(f"[口径] bbmodel {lo}..{hi}（≈{hi - lo} 格）→ JSON 跨度 "
          f"{round((lo - CENTER) * SCALE + CENTER, 2)}..{round((hi - CENTER) * SCALE + CENTER, 2)} 单位")
    print(f"[元素] {len(els)} 个 cube（无旋转）")

    if args.preview:
        raw = texture_path.read_bytes()
        tex_w, tex_h, tex_rows = decode_png(raw)
        render_preview(model, tex_rows, tex_w, tex_h, args.preview)
        print(f"[预览] {args.preview}")


if __name__ == "__main__":
    main()
