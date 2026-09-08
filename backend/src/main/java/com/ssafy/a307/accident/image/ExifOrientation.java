package com.ssafy.a307.accident.image;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * JPEG 의 EXIF Orientation(태그 {@code 0x0112}) 을 읽어 픽셀을 실제로 회전·반전시킨다.
 * <p>
 * <b>새 의존성 없이 APP1 세그먼트를 직접 읽는다.</b> metadata-extractor 같은 라이브러리를 쓰면
 * 두 줄로 끝나지만 이 저장소는 새 의존성을 금지한다. 읽는 범위는 IFD0 의 orientation 태그 하나뿐이라
 * 파서가 작다.
 *
 * <pre>
 * FF D8                     SOI
 * FF E1 [len] "Exif\0\0"    APP1
 *   II*\0 또는 MM\0*        TIFF 헤더 (바이트 순서)
 *   [IFD0 offset]
 *   IFD0: [entry count][12바이트 엔트리 × n]
 *         엔트리 = [tag 2][type 2][count 4][value 4]
 *         tag 0x0112 의 value 가 1~8
 * </pre>
 *
 * <p><b>보정 후에는 orientation 을 남기지 않는다.</b> 이 클래스가 만든 이미지를 다시 인코딩할 때
 * ({@link AccidentImagePreprocessor}) EXIF 자체가 사라지므로 값이 1인 것과 같아진다. 픽셀을 돌려놓고
 * 메타에도 회전 지시가 남아 있으면 뷰어가 <b>두 번</b> 돌린다 — 그것이 이 처리의 핵심 실패 모드다.
 */
public final class ExifOrientation {

    /** 회전 없음. EXIF 가 없거나 읽을 수 없을 때의 값이다. */
    public static final int NORMAL = 1;

    private static final int TAG_ORIENTATION = 0x0112;
    private static final int MARKER_APP1 = 0xE1;
    private static final int MARKER_SOS = 0xDA;

    private ExifOrientation() {
    }

    /**
     * @return 1~8. EXIF 가 없거나 값이 범위를 벗어나면 {@link #NORMAL}
     */
    public static int readJpegOrientation(byte[] jpeg) {
        if (!ImageSignatures.isJpeg(jpeg)) return NORMAL;
        int offset = 2;
        while (offset + 4 <= jpeg.length) {
            if ((jpeg[offset] & 0xff) != 0xff) return NORMAL;
            int marker = jpeg[offset + 1] & 0xff;
            if (marker == MARKER_SOS) return NORMAL;
            int length = ((jpeg[offset + 2] & 0xff) << 8) | (jpeg[offset + 3] & 0xff);
            if (length < 2) return NORMAL;
            int segmentStart = offset + 4;
            int segmentLength = length - 2;
            if (segmentStart + segmentLength > jpeg.length) return NORMAL;
            if (marker == MARKER_APP1 && isExifHeader(jpeg, segmentStart, segmentLength)) {
                return readFromTiff(jpeg, segmentStart + 6, segmentLength - 6);
            }
            offset = segmentStart + segmentLength;
        }
        return NORMAL;
    }

    /** orientation 값대로 픽셀을 옮긴 새 이미지. 1이면 원본을 그대로 돌려준다. */
    public static BufferedImage apply(BufferedImage source, int orientation) {
        if (source == null) throw new IllegalArgumentException("source is required");
        if (orientation <= NORMAL || orientation > 8) return source;

        int width = source.getWidth();
        int height = source.getHeight();
        boolean swapped = orientation >= 5;
        int targetWidth = swapped ? height : width;
        int targetHeight = swapped ? width : height;

        BufferedImage target = new BufferedImage(targetWidth, targetHeight, imageType(source));
        Graphics2D g = target.createGraphics();
        try {
            g.setTransform(transform(orientation, width, height));
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /**
     * EXIF orientation 1~8 의 변환. 값의 의미는 "저장된 픽셀을 화면에 맞추려면 어떻게 옮겨야 하는가" 다.
     * <ul>
     *   <li>1 그대로 · 2 좌우반전 · 3 180도 · 4 상하반전</li>
     *   <li>5 전치 · 6 시계 90도 · 7 역전치 · 8 반시계 90도</li>
     * </ul>
     */
    private static AffineTransform transform(int orientation, int width, int height) {
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> {
                t.translate(width, 0);
                t.scale(-1, 1);
            }
            case 3 -> {
                t.translate(width, height);
                t.rotate(Math.PI);
            }
            case 4 -> {
                t.translate(0, height);
                t.scale(1, -1);
            }
            case 5 -> {
                // 전치 — (x,y) → (y,x)
                t.rotate(Math.PI / 2);
                t.scale(1, -1);
            }
            case 6 -> {
                t.translate(height, 0);
                t.rotate(Math.PI / 2);
            }
            case 7 -> {
                // 역전치 — (x,y) → (height-y, width-x)
                t.translate(height, width);
                t.scale(-1, 1);
                t.rotate(-Math.PI / 2);
            }
            case 8 -> {
                t.translate(0, width);
                t.rotate(-Math.PI / 2);
            }
            default -> {
                // 1 — 변환 없음
            }
        }
        return t;
    }

    private static int imageType(BufferedImage source) {
        return source.getTransparency() == BufferedImage.OPAQUE
                ? BufferedImage.TYPE_INT_RGB
                : BufferedImage.TYPE_INT_ARGB;
    }

    private static boolean isExifHeader(byte[] bytes, int start, int length) {
        if (length < 6 || start + 6 > bytes.length) return false;
        return bytes[start] == 'E' && bytes[start + 1] == 'x' && bytes[start + 2] == 'i'
                && bytes[start + 3] == 'f' && bytes[start + 4] == 0 && bytes[start + 5] == 0;
    }

    private static int readFromTiff(byte[] bytes, int tiffStart, int tiffLength) {
        if (tiffLength < 8 || tiffStart + 8 > bytes.length) return NORMAL;
        boolean bigEndian;
        if (bytes[tiffStart] == 'I' && bytes[tiffStart + 1] == 'I') {
            bigEndian = false;
        } else if (bytes[tiffStart] == 'M' && bytes[tiffStart + 1] == 'M') {
            bigEndian = true;
        } else {
            return NORMAL;
        }
        long ifdOffset = readUnsignedInt(bytes, tiffStart + 4, bigEndian);
        int ifdStart = (int) (tiffStart + ifdOffset);
        if (ifdStart < tiffStart || ifdStart + 2 > bytes.length) return NORMAL;
        int entryCount = readUnsignedShort(bytes, ifdStart, bigEndian);
        for (int i = 0; i < entryCount; i++) {
            int entry = ifdStart + 2 + i * 12;
            if (entry + 12 > bytes.length) return NORMAL;
            if (readUnsignedShort(bytes, entry, bigEndian) != TAG_ORIENTATION) continue;
            int value = readUnsignedShort(bytes, entry + 8, bigEndian);
            return value >= 1 && value <= 8 ? value : NORMAL;
        }
        return NORMAL;
    }

    private static int readUnsignedShort(byte[] bytes, int offset, boolean bigEndian) {
        int first = bytes[offset] & 0xff;
        int second = bytes[offset + 1] & 0xff;
        return bigEndian ? (first << 8) | second : (second << 8) | first;
    }

    private static long readUnsignedInt(byte[] bytes, int offset, boolean bigEndian) {
        long b0 = bytes[offset] & 0xffL;
        long b1 = bytes[offset + 1] & 0xffL;
        long b2 = bytes[offset + 2] & 0xffL;
        long b3 = bytes[offset + 3] & 0xffL;
        return bigEndian
                ? (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
                : (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
    }
}
