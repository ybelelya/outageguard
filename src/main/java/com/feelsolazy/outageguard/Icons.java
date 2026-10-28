package com.feelsolazy.outageguard;

import lombok.experimental.UtilityClass;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The lightning-bolt icon, drawn in code so the tray, toasts and the jpackage launcher all share it.
 * {@link #main} is run by the Gradle {@code generateIcons} task to produce icon.ico / icon.png.
 */
@UtilityClass
class Icons {

    private static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 128, 256};

    static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 32.0, size / 32.0);
        Polygon bolt = new Polygon(new int[]{19, 6, 15, 12, 26, 17}, new int[]{1, 18, 18, 31, 13, 13}, 6);
        g.setColor(new Color(0xFFC107));
        g.fill(bolt);
        g.setColor(new Color(0x5D4037));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(bolt);
        g.dispose();
        return img;
    }

    static void writePng(Path file, int size) throws IOException {
        ImageIO.write(draw(size), "png", file.toFile());
    }

    /** Multi-size .ico with PNG-compressed entries (supported since Windows Vista). */
    static void writeIco(Path file) throws IOException {
        List<byte[]> images = new ArrayList<>();
        for (int size : ICO_SIZES) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(draw(size), "png", png);
            images.add(png.toByteArray());
        }
        int headerSize = 6 + 16 * ICO_SIZES.length;
        int total = headerSize + images.stream().mapToInt(b -> b.length).sum();
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) ICO_SIZES.length);
        int offset = headerSize;
        for (int i = 0; i < ICO_SIZES.length; i++) {
            int size = ICO_SIZES[i];
            buf.put((byte) (size >= 256 ? 0 : size)).put((byte) (size >= 256 ? 0 : size))
                    .put((byte) 0).put((byte) 0)
                    .putShort((short) 1).putShort((short) 32)
                    .putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        images.forEach(buf::put);
        Files.write(file, buf.array());
    }

    /** Usage: Icons &lt;output dir&gt;. Writes icon.ico and icon.png (256 px). */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        writeIco(dir.resolve("icon.ico"));
        writePng(dir.resolve("icon.png"), 256);
    }
}
