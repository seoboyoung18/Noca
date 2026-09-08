package com.ssafy.a307.accident.image;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 테스트용 이미지 바이트 생성기. 실제 사진 파일을 저장소에 커밋하지 않기 위해 만든다 —
 * 바이너리 픽스처는 무엇이 들어 있는지 리뷰에서 보이지 않고, EXIF 를 바꿔 가며 8가지 방향을
 * 시험하려면 코드로 만드는 편이 정확하다.
 */
public final class TestImages {

    private TestImages() {
    }

    /** 픽셀마다 다른 색을 넣은 이미지. 회전·반전 결과를 좌표로 확인할 수 있다. */
    public static BufferedImage distinctPixels(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color(x, y));
            }
        }
        return image;
    }

    /** {@link #distinctPixels} 와 같은 규칙의 색. 좌표 → 색 대응을 단언에 쓴다. */
    public static int color(int x, int y) {
        return new Color(20 + x * 20, 40 + y * 40, 60).getRGB();
    }

    public static byte[] jpeg(int width, int height) {
        return encode(gradient(width, height), "jpg");
    }

    public static byte[] png(int width, int height) {
        return encode(gradient(width, height), "png");
    }

    /** 알파가 있는 PNG. JPEG 변환 시 투명 영역 처리를 확인한다. */
    public static byte[] pngWithAlpha(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0x00000000);
            }
        }
        return encode(image, "png");
    }

    /**
     * EXIF Orientation 태그를 가진 JPEG. {@code ImageIO} 로 만든 JPEG 의 SOI 뒤에 APP1 세그먼트를
     * 끼워 넣는다 — {@code ImageIO} 의 JPEG 라이터는 EXIF 를 쓰지 않으므로 직접 만들어야 한다.
     */
    public static byte[] jpegWithOrientation(int width, int height, int orientation) {
        byte[] jpeg = jpeg(width, height);
        byte[] app1 = app1ExifOrientation(orientation);
        byte[] result = new byte[jpeg.length + app1.length];
        result[0] = jpeg[0];
        result[1] = jpeg[1];
        System.arraycopy(app1, 0, result, 2, app1.length);
        System.arraycopy(jpeg, 2, result, 2 + app1.length, jpeg.length - 2);
        return result;
    }

    /**
     * <pre>
     * FF E1 [len:2] "Exif\0\0" "II" 2A 00 [ifdOffset:4=8]
     * [entryCount:2=1] [tag:2=0x0112][type:2=3][count:4=1][value:4=orientation] [nextIfd:4=0]
     * </pre>
     */
    public static byte[] app1ExifOrientation(int orientation) {
        byte[] exifHeader = "Exif".getBytes(StandardCharsets.US_ASCII);
        int tiffLength = 8 + 2 + 12 + 4;
        int segmentLength = 2 + 6 + tiffLength;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xff);
        out.write(0xe1);
        out.write((segmentLength >> 8) & 0xff);
        out.write(segmentLength & 0xff);
        out.write(exifHeader, 0, exifHeader.length);
        out.write(0);
        out.write(0);

        // TIFF 헤더 — 리틀엔디언
        out.write('I');
        out.write('I');
        writeShortLe(out, 42);
        writeIntLe(out, 8);

        // IFD0 — 엔트리 1개
        writeShortLe(out, 1);
        writeShortLe(out, 0x0112);  // Orientation
        writeShortLe(out, 3);       // SHORT
        writeIntLe(out, 1);
        writeShortLe(out, orientation);
        writeShortLe(out, 0);       // value 필드의 남은 2바이트
        writeIntLe(out, 0);         // 다음 IFD 없음

        return out.toByteArray();
    }

    private static BufferedImage gradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (int) (255.0 * x / Math.max(1, width - 1));
                int g = (int) (255.0 * y / Math.max(1, height - 1));
                image.setRGB(x, y, new Color(r, g, 128).getRGB());
            }
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("no writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void writeShortLe(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private static void writeIntLe(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }
}
