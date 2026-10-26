package io.github.piwugu.snowleopard.tools;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 最小 PNG 编码器（RGB8，零第三方依赖）——给服务端射线小图用（视觉 B 档）。
 *
 * <p>为什么手写：框架红线是零依赖，而 {@code javax.imageio} 在服务端存在但会把整条
 * AWT 类路径拉起来（专用服务器上没有字体/图形环境，历史上有过 {@code HeadlessException} 事故）。
 * PNG 的 zlib 与 CRC32 都是 JDK 自带，40 行就够。
 */
public final class PngWriter {

    private PngWriter() {
    }

    /**
     * @param w   宽（像素，≥1）
     * @param h   高（像素，≥1）
     * @param rgb 长度 = w*h，每个元素 {@code 0xRRGGBB}（无 alpha，图像不透明）
     * @return 完整 PNG 字节
     */
    public static byte[] rgb(int w, int h, int[] rgb) {
        if (w <= 0 || h <= 0 || rgb.length < w * h) {
            throw new IllegalArgumentException("PNG 尺寸/像素数组不匹配: " + w + "x" + h + " len=" + rgb.length);
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream((w * 3 + 1) * h);
        for (int y = 0; y < h; y++) {
            raw.write(0);                       // 每行 filter=0（None）
            for (int x = 0; x < w; x++) {
                int c = rgb[y * w + x];
                raw.write((c >> 16) & 0xFF);
                raw.write((c >> 8) & 0xFF);
                raw.write(c & 0xFF);
            }
        }
        byte[] idat = deflate(raw.toByteArray());

        ByteArrayOutputStream out = new ByteArrayOutputStream(idat.length + 64);
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream(13);
        writeInt(ihdr, w);
        writeInt(ihdr, h);
        ihdr.write(8);      // bit depth
        ihdr.write(2);      // color type = truecolor RGB
        ihdr.write(0);      // compression
        ihdr.write(0);      // filter
        ihdr.write(0);      // interlace
        chunk(out, "IHDR", ihdr.toByteArray());
        chunk(out, "IDAT", idat);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.max(64, data.length / 3));
        byte[] tmp = new byte[8192];
        while (!d.finished()) {
            int n = d.deflate(tmp);
            buf.write(tmp, 0, n);
        }
        d.end();
        return buf.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        writeInt(out, data.length);
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}
