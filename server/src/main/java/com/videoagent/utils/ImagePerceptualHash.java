package com.videoagent.utils;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 感知哈希（Average Hash）：8×8 灰度均值哈希，用于关键帧去重。
 * 相邻画面哈希距离小 → 视为重复帧，跳过重复 OCR（方案 §6.1 设计要点）。
 */
public final class ImagePerceptualHash {

    private static final int SIZE = 8;

    private ImagePerceptualHash() {
    }

    /**
     * 计算图片的平均哈希（64 位）。
     *
     * @throws IOException 图片无法解码时抛出
     */
    public static long averageHash(Path image) throws IOException {
        BufferedImage img;
        try (var in = Files.newInputStream(image)) {
            img = ImageIO.read(in);
        }
        if (img == null) {
            throw new IOException("无法解码图片: " + image.getFileName());
        }

        BufferedImage gray = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(img, 0, 0, SIZE, SIZE, null);
        g.dispose();

        long[] pixels = new long[SIZE * SIZE];
        long sum = 0;
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                int rgb = gray.getRGB(j, i);
                int v = (rgb >> 16) & 0xFF; // TYPE_BYTE_GRAY 下 RGB 通道相同
                pixels[i * SIZE + j] = v;
                sum += v;
            }
        }
        long avg = sum / pixels.length;

        long hash = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] >= avg) {
                hash |= (1L << i);
            }
        }
        return hash;
    }

    /** 汉明距离：两个哈希的差异位数。 */
    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }
}
