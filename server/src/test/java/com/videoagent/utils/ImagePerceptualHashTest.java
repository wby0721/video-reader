package com.videoagent.utils;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ImagePerceptualHashTest {

    private Path render(int width, int height, java.util.function.Consumer<Graphics2D> painter) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        painter.accept(g);
        g.dispose();
        Path p = Files.createTempFile("phash-", ".png");
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    @Test
    void identicalImage_sameHash() throws IOException {
        Path a = render(640, 360, g -> {
            g.setColor(Color.BLACK);
            g.fillRect(50, 50, 200, 100);
        });
        Path b = render(640, 360, g -> {
            g.setColor(Color.BLACK);
            g.fillRect(50, 50, 200, 100);
        });

        long ha = ImagePerceptualHash.averageHash(a);
        long hb = ImagePerceptualHash.averageHash(b);

        assertThat(ImagePerceptualHash.hammingDistance(ha, hb)).isZero();
    }

    @Test
    void nearDuplicate_smallDistance() throws IOException {
        Path a = render(640, 360, g -> {
            g.setColor(Color.BLACK);
            g.fillRect(50, 50, 200, 100);
        });
        // 几乎相同的帧：仅轻微偏移（感知哈希应判定为重复）
        Path b = render(640, 360, g -> {
            g.setColor(Color.BLACK);
            g.fillRect(52, 52, 200, 100);
        });

        long ha = ImagePerceptualHash.averageHash(a);
        long hb = ImagePerceptualHash.averageHash(b);

        assertThat(ImagePerceptualHash.hammingDistance(ha, hb)).isLessThan(8);
    }

    @Test
    void differentImage_largeDistance() throws IOException {
        // 左黑右白 vs 左白右黑：平均哈希应呈互补（距离 ≈ 64）
        Path a = render(640, 360, g -> {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, 320, 360);
        });
        Path b = render(640, 360, g -> {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 320, 360);
        });

        long ha = ImagePerceptualHash.averageHash(a);
        long hb = ImagePerceptualHash.averageHash(b);

        assertThat(ImagePerceptualHash.hammingDistance(ha, hb)).isGreaterThan(8);
    }
}
