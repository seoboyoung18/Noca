package com.ssafy.a307.accident.image;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * 매직바이트로 실제 형식을 판별한다. {@code EstimateFileValidator} 의 시그니처 검사 방식을
 * 그대로 재사용하고, 그 코드에 없던 <b>HEIC 판별만 추가</b>했다.
 *
 * <pre>
 * JPEG  FF D8 FF
 * PNG   89 50 4E 47 0D 0A 1A 0A
 * HEIC  ....ftyp{brand}   — offset 4 의 'ftyp' 박스, offset 8 의 브랜드로 판별
 * </pre>
 *
 * HEIC 는 ISO-BMFF 컨테이너라 파일 앞 4바이트가 박스 크기(가변)다. 그래서 고정 시그니처가 없고
 * offset 4 의 {@code ftyp} 와 브랜드를 봐야 한다. 브랜드 목록은 요구사항의 HEIC(아이폰 기본 포맷)
 * 범위에 맞춰 {@code heic · heix · mif1 · msf1} 네 개만 인정한다 — {@code mp41} 같은 동영상 브랜드는
 * 같은 컨테이너를 쓰지만 이미지가 아니다.
 */
public final class ImageSignatures {

    /** HEIF 계열 중 정지 이미지로 인정하는 브랜드. */
    public static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "mif1", "msf1");

    private static final int[] PNG = {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final int HEIC_FTYP_OFFSET = 4;
    private static final int HEIC_BRAND_OFFSET = 8;

    private ImageSignatures() {
    }

    /** 바이트가 어떤 형식인지. 판별되지 않으면 비어 있다. */
    public static Optional<ImageFormat> detect(byte[] content) {
        if (isJpeg(content)) return Optional.of(ImageFormat.JPEG);
        if (isPng(content)) return Optional.of(ImageFormat.PNG);
        if (isHeic(content)) return Optional.of(ImageFormat.HEIC);
        return Optional.empty();
    }

    public static boolean matches(byte[] content, ImageFormat format) {
        return detect(content).filter(detected -> detected == format).isPresent();
    }

    public static boolean isJpeg(byte[] bytes) {
        return bytes != null && bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
    }

    public static boolean isPng(byte[] bytes) {
        if (bytes == null || bytes.length < PNG.length) return false;
        for (int i = 0; i < PNG.length; i++) {
            if ((bytes[i] & 0xff) != PNG[i]) return false;
        }
        return true;
    }

    public static boolean isHeic(byte[] bytes) {
        if (bytes == null || bytes.length < HEIC_BRAND_OFFSET + 4) return false;
        String ftyp = new String(bytes, HEIC_FTYP_OFFSET, 4, StandardCharsets.US_ASCII);
        if (!"ftyp".equals(ftyp)) return false;
        String brand = new String(bytes, HEIC_BRAND_OFFSET, 4, StandardCharsets.US_ASCII);
        return HEIC_BRANDS.contains(brand);
    }
}
