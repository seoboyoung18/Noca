package com.ssafy.a307.member.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로필 이미지 형식 판정.
 * <p>
 * 키에 확장자가 없고 presigned PUT 은 서버를 거치지 않아, 시그니처가 형식을 아는 유일한
 * 수단이다. 선언한 {@code Content-Type} 을 믿으면 아무 바이트나 이미지로 저장된다.
 */
@DisplayName("프로필 이미지 형식")
class ProfileImageFormatTest {

    @Test
    @DisplayName("JPEG 시그니처를 알아본다")
    void detectsJpeg() {
        byte[] jpeg = withPadding(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});

        assertThat(ProfileImageFormat.detect(jpeg)).contains(ProfileImageFormat.JPEG);
    }

    @Test
    @DisplayName("PNG 시그니처를 알아본다")
    void detectsPng() {
        byte[] png = withPadding(new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        assertThat(ProfileImageFormat.detect(png)).contains(ProfileImageFormat.PNG);
    }

    /** 아이폰 기본 포맷이다. 앞 4바이트는 박스 길이라 아무 값이나 올 수 있다. */
    @Test
    @DisplayName("HEIC 는 ftyp 박스와 브랜드로 알아본다")
    void detectsHeic() {
        byte[] heic = heicHeader("heic");

        assertThat(ProfileImageFormat.detect(heic)).contains(ProfileImageFormat.HEIC);
    }

    @Test
    @DisplayName("ftyp 이지만 HEIC 브랜드가 아니면 거절한다 — MP4 같은 것들")
    void rejectsNonHeicBmff() {
        byte[] mp4 = heicHeader("isom");

        assertThat(ProfileImageFormat.detect(mp4)).isEmpty();
    }

    @Test
    @DisplayName("이미지가 아닌 바이트는 형식이 없다")
    void rejectsNonImage() {
        byte[] html = "<html><script>".getBytes(StandardCharsets.UTF_8);

        assertThat(ProfileImageFormat.detect(html)).isEmpty();
    }

    @Test
    @DisplayName("시그니처 길이보다 짧으면 판정하지 않는다")
    void rejectsTooShortContent() {
        assertThat(ProfileImageFormat.detect(new byte[]{(byte) 0xFF, (byte) 0xD8})).isEmpty();
        assertThat(ProfileImageFormat.detect(new byte[0])).isEmpty();
        assertThat(ProfileImageFormat.detect(null)).isEmpty();
    }

    @Test
    @DisplayName("Content-Type 으로도 형식을 찾는다 — 대소문자·공백은 무시한다")
    void findsByContentType() {
        assertThat(ProfileImageFormat.ofContentType("image/png")).contains(ProfileImageFormat.PNG);
        assertThat(ProfileImageFormat.ofContentType("  IMAGE/JPEG ")).contains(ProfileImageFormat.JPEG);
        assertThat(ProfileImageFormat.ofContentType("image/gif")).isEmpty();
        assertThat(ProfileImageFormat.ofContentType(null)).isEmpty();
    }

    private static byte[] heicHeader(String brand) {
        byte[] head = new byte[16];
        head[3] = 0x18;
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, head, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, head, 8, 4);
        return head;
    }

    private static byte[] withPadding(byte[] signature) {
        byte[] padded = new byte[Math.max(signature.length, ProfileImageFormat.SIGNATURE_LENGTH)];
        System.arraycopy(signature, 0, padded, 0, signature.length);
        return padded;
    }
}
