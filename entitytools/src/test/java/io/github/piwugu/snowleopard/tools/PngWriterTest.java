package io.github.piwugu.snowleopard.tools;

import java.io.ByteArrayOutputStream;
import java.util.zip.Inflater;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手写 PNG 编码器自检（2026-10-27 06:05 (UTC+8)）。
 *
 * <p>为什么值得单独测：这段代码**没有真机断言覆盖**（真机只验文字模式），
 * 而它一旦坏，表现是"图打不开"——模型看不到、人也看不到，但工具仍然返回 Ok，
 * 属于典型的**假成功**。所以在这里把 PNG 结构逐字节验一遍。
 */
class PngWriterTest {

    @Test
    void writesValidRgbPng() throws Exception {
        int w = 3;
        int h = 2;
        int[] px = {
                0xFF0000, 0x00FF00, 0x0000FF,
                0xFFFFFF, 0x000000, 0x808080,
        };
        byte[] png = PngWriter.rgb(w, h, px);

        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        assertArrayEquals(sig, java.util.Arrays.copyOf(png, 8), "PNG 签名");

        // IHDR 紧跟在签名后面：长度(4) + "IHDR"(4) + w(4) + h(4) + ...
        assertEquals("IHDR", new String(png, 12, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(w, readInt(png, 16), "宽");
        assertEquals(h, readInt(png, 20), "高");
        assertEquals(8, png[24], "位深");
        assertEquals(2, png[25], "颜色类型（2=RGB）");

        // 解压 IDAT：每行 1 字节 filter + w*3 字节像素
        byte[] raw = inflateIdat(png);
        assertEquals((w * 3 + 1) * h, raw.length, "解压后应为 (w*3+1)*h");
        assertEquals(0, raw[0], "行首 filter 必须是 0（None）");
        assertArrayEquals(new byte[]{(byte) 0xFF, 0, 0}, slice(raw, 1, 3), "第一个像素应为纯红");
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                slice(raw, 1 + w * 3 + 1, 3), "第二行第一个像素应为纯白");

        assertTrue(png.length > 8 + 25 + 12, "至少要有 IHDR/IDAT/IEND 三个块");
    }

    @Test
    void rejectsMismatchedSize() {
        try {
            PngWriter.rgb(4, 4, new int[3]);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("尺寸与像素数不匹配时必须抛 IllegalArgumentException");
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static byte[] slice(byte[] b, int off, int len) {
        return java.util.Arrays.copyOfRange(b, off, off + len);
    }

    /** 顺序扫块，拼接所有 IDAT 后解压。 */
    private static byte[] inflateIdat(byte[] png) throws Exception {
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int pos = 8;
        while (pos + 8 <= png.length) {
            int len = readInt(png, pos);
            String type = new String(png, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("IDAT".equals(type)) {
                idat.write(png, pos + 8, len);
            }
            pos += 12 + len;   // len + type(4) + data + crc(4)
        }
        Inflater inf = new Inflater();
        inf.setInput(idat.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        while (!inf.finished()) {
            int n = inf.inflate(tmp);
            if (n == 0 && inf.needsInput()) {
                break;
            }
            out.write(tmp, 0, n);
        }
        inf.end();
        return out.toByteArray();
    }
}
