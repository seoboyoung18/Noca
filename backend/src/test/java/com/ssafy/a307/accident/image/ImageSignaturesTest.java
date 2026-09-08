package com.ssafy.a307.accident.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("이미지 매직바이트 판별")
class ImageSignaturesTest {

    @Test
    @DisplayName("JPEG 은 FF D8 FF 로 판별한다")
    void jpeg() {
        assertThat(ImageSignatures.detect(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}))
                .contains(ImageFormat.JPEG);
    }

    @Test
    @DisplayName("PNG 은 8바이트 시그니처로 판별한다")
    void png() {
        assertThat(ImageSignatures.detect(pngBytes())).contains(ImageFormat.PNG);
    }

    @ParameterizedTest(name = "ftyp 브랜드 {0} 은 HEIC 다")
    @ValueSource(strings = {"heic", "heix", "mif1", "msf1"})
    @DisplayName("HEIC 은 offset 4 의 ftyp 와 브랜드로 판별한다")
    void heicBrands(String brand) {
        assertThat(ImageSignatures.detect(heicBytes(brand))).contains(ImageFormat.HEIC);
    }

    @ParameterizedTest(name = "브랜드 {0} 은 이미지가 아니다")
    @ValueSource(strings = {"mp41", "isom", "qt  "})
    @DisplayName("같은 ISO-BMFF 컨테이너라도 동영상 브랜드는 받지 않는다")
    void nonImageBrands(String brand) {
        assertThat(ImageSignatures.detect(heicBytes(brand))).isEmpty();
    }

    @Test
    @DisplayName("ftyp 가 아니면 HEIC 가 아니다")
    void withoutFtypBox() {
        byte[] bytes = heicBytes("heic");
        bytes[4] = 'x';
        assertThat(ImageSignatures.isHeic(bytes)).isFalse();
    }

    @Test
    @DisplayName("확장자가 가리키는 형식과 시그니처가 다르면 matches 가 거짓이다")
    void extensionAndSignatureMismatch() {
        assertThat(ImageSignatures.matches(pngBytes(), ImageFormat.JPEG)).isFalse();
        assertThat(ImageSignatures.matches(pngBytes(), ImageFormat.PNG)).isTrue();
    }

    @Test
    @DisplayName("짧거나 빈 바이트에서 예외를 던지지 않는다")
    void shortInput() {
        assertThat(ImageSignatures.detect(new byte[0])).isEmpty();
        assertThat(ImageSignatures.detect(new byte[]{1, 2})).isEmpty();
        assertThat(ImageSignatures.detect(null)).isEmpty();
    }

    static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00};
    }

    static byte[] heicBytes(String brand) {
        byte[] bytes = new byte[16];
        bytes[3] = 0x18; // 박스 크기 — 값은 판별에 쓰이지 않는다
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, bytes, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }
}
