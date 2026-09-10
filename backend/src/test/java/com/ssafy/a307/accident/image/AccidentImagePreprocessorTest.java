package com.ssafy.a307.accident.image;

import com.ssafy.a307.accident.config.AccidentImageProperties;
import com.ssafy.a307.accident.entity.ImageVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전처리 규격(Task 138) — EXIF 보정, 종횡비 유지 축소, 확대 금지, 메타 제거.
 * <p>
 * 리사이즈 목표값은 요구사항에 없어 프로퍼티로 뺐다. 그래서 이 테스트도 <b>숫자를 박지 않고</b>
 * 프로퍼티 값을 기준으로 단언한다 — 잠정값이 바뀌어도 테스트를 고칠 필요가 없다.
 */
@DisplayName("사고 이미지 전처리")
class AccidentImagePreprocessorTest {

    private static final int RESIZED_MAX = 200;
    private static final int THUMBNAIL_MAX = 50;

    private final AccidentImageProperties properties =
            new AccidentImageProperties(20, 20_971_520, RESIZED_MAX, THUMBNAIL_MAX, 10, 10);
    private final AccidentImagePreprocessor preprocessor =
            new AccidentImagePreprocessor(new ImageIoImageDecoder(), properties);

    @Test
    @DisplayName("긴 변을 기준으로 종횡비를 유지하며 축소한다")
    void scalesByLongEdgeKeepingAspectRatio() {
        AccidentImagePreprocessor.Preprocessed result =
                preprocessor.preprocess(TestImages.jpeg(400, 300), ImageFormat.JPEG);

        assertThat(result.width()).isEqualTo(400);
        assertThat(result.height()).isEqualTo(300);

        // 짧은 변은 비율대로 계산하고 반올림한다 — 내림으로 깎으면 종횡비가 미세하게 틀어진다
        assertThat(result.resized().width()).isEqualTo(RESIZED_MAX);
        assertThat(result.resized().height()).isEqualTo(scaled(300, RESIZED_MAX, 400));
        assertThat(result.thumbnail().width()).isEqualTo(THUMBNAIL_MAX);
        assertThat(result.thumbnail().height()).isEqualTo(scaled(300, THUMBNAIL_MAX, 400));
    }

    @Test
    @DisplayName("세로가 긴 이미지는 높이를 기준으로 축소한다")
    void scalesPortraitByHeight() {
        AccidentImagePreprocessor.Preprocessed result =
                preprocessor.preprocess(TestImages.jpeg(300, 400), ImageFormat.JPEG);

        assertThat(result.resized().height()).isEqualTo(RESIZED_MAX);
        assertThat(result.resized().width()).isEqualTo(scaled(300, RESIZED_MAX, 400));
    }

    /** 긴 변을 {@code maxEdge} 로 줄일 때 짧은 변의 기대값. 반올림 규칙을 테스트에도 그대로 적용한다. */
    private static int scaled(int shortEdge, int maxEdge, int longEdge) {
        return (int) Math.round(shortEdge * ((double) maxEdge / longEdge));
    }

    @Test
    @DisplayName("원본보다 큰 리사이즈본을 만들지 않는다 — 작은 이미지는 원본 크기 그대로다")
    void neverUpscales() {
        int width = 40;
        int height = 30;
        AccidentImagePreprocessor.Preprocessed result =
                preprocessor.preprocess(TestImages.jpeg(width, height), ImageFormat.JPEG);

        assertThat(result.resized().width()).isEqualTo(width);
        assertThat(result.resized().height()).isEqualTo(height);
        // 썸네일 상한(50)보다 긴 변(40)이 작으므로 썸네일도 원본 크기다
        assertThat(result.thumbnail().width()).isEqualTo(width);
        assertThat(result.thumbnail().height()).isEqualTo(height);
    }

    @Test
    @DisplayName("변형본에서 EXIF 가 사라진다 — GPS·기기 정보가 남지 않는다")
    void stripsExifFromVariants() {
        byte[] original = TestImages.jpegWithOrientation(400, 300, 6);
        assertThat(ExifOrientation.readJpegOrientation(original)).isEqualTo(6);

        AccidentImagePreprocessor.Preprocessed result =
                preprocessor.preprocess(original, ImageFormat.JPEG);

        assertThat(hasApp1Segment(result.resized().content()))
                .as("리사이즈본에 APP1(EXIF) 세그먼트가 없어야 한다").isFalse();
        assertThat(hasApp1Segment(result.thumbnail().content()))
                .as("썸네일에 APP1(EXIF) 세그먼트가 없어야 한다").isFalse();
        assertThat(ExifOrientation.readJpegOrientation(result.resized().content()))
                .as("보정 후 회전 지시가 남으면 뷰어가 두 번 돌린다")
                .isEqualTo(ExifOrientation.NORMAL);
    }

    @ParameterizedTest(name = "orientation {0}")
    @ValueSource(ints = {5, 6, 7, 8})
    @DisplayName("회전이 필요한 orientation 은 표시 크기의 가로세로가 뒤바뀐다")
    void appliesOrientation(int orientation) {
        AccidentImagePreprocessor.Preprocessed result = preprocessor.preprocess(
                TestImages.jpegWithOrientation(400, 300, orientation), ImageFormat.JPEG);

        assertThat(result.appliedOrientation()).isEqualTo(orientation);
        assertThat(result.width()).isEqualTo(300);
        assertThat(result.height()).isEqualTo(400);
        assertThat(result.resized().height()).isEqualTo(RESIZED_MAX);
        assertThat(result.shortEdge()).isEqualTo(300);
    }

    @Test
    @DisplayName("orientation 이 없으면 보정하지 않는다")
    void withoutOrientation() {
        AccidentImagePreprocessor.Preprocessed result =
                preprocessor.preprocess(TestImages.jpeg(400, 300), ImageFormat.JPEG);

        assertThat(result.appliedOrientation()).isEqualTo(ExifOrientation.NORMAL);
        assertThat(result.width()).isEqualTo(400);
    }

    @Test
    @DisplayName("PNG 도 처리하며 변형본은 JPEG 이다")
    void pngBecomesJpegVariants() throws IOException {
        AccidentImagePreprocessor.Preprocessed result =
                preprocessor.preprocess(TestImages.png(400, 300), ImageFormat.PNG);

        assertThat(result.resized().contentType()).isEqualTo("image/jpeg");
        assertThat(result.resized().extension()).isEqualTo("jpg");
        assertThat(result.resized().variant()).isEqualTo(ImageVariant.RESIZED);
        assertThat(result.thumbnail().variant()).isEqualTo(ImageVariant.THUMBNAIL);
        assertThat(ImageSignatures.detect(result.resized().content())).contains(ImageFormat.JPEG);
        assertThat(read(result.resized().content())).isNotNull();
    }

    @Test
    @DisplayName("투명 PNG 는 흰 배경에 합성한다 — 알파를 버리면 투명 영역이 검게 나온다")
    void transparentPngGetsWhiteBackground() throws IOException {
        AccidentImagePreprocessor.Preprocessed result =
                preprocessor.preprocess(TestImages.pngWithAlpha(80, 60), ImageFormat.PNG);

        BufferedImage thumbnail = read(result.thumbnail().content());
        int center = thumbnail.getRGB(thumbnail.getWidth() / 2, thumbnail.getHeight() / 2);
        assertThat(center & 0xffffff).isEqualTo(0xffffff);
    }

    @Test
    @DisplayName("변형본 바이트는 방어적으로 복사된다")
    void variantBytesAreCopied() {
        AccidentImagePreprocessor.RenderedVariant variant =
                new AccidentImagePreprocessor.RenderedVariant(ImageVariant.RESIZED, new byte[]{1, 2, 3}, 1, 1);

        byte[] returned = variant.content();
        returned[0] = 9;

        assertThat(variant.content()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("HEIC 은 디코더가 거절한다 — 전처리까지 내려오지 않는다")
    void heicIsRejectedByDecoder() {
        byte[] heic = ImageSignaturesTest.heicBytes("heic");

        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                AccidentImageValidationException.class,
                () -> preprocessor.preprocess(heic, ImageFormat.HEIC)).reason())
                .isEqualTo(AccidentImageValidationException.Reason.SERVER_CONVERSION_UNSUPPORTED);
    }

    private static BufferedImage read(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    /** JPEG 안에 APP1(FF E1) 세그먼트가 있는지. EXIF 는 이 세그먼트에 담긴다. */
    private static boolean hasApp1Segment(byte[] jpeg) {
        int offset = 2;
        while (offset + 4 <= jpeg.length) {
            if ((jpeg[offset] & 0xff) != 0xff) return false;
            int marker = jpeg[offset + 1] & 0xff;
            if (marker == 0xda) return false;
            if (marker == 0xe1) return true;
            int length = ((jpeg[offset + 2] & 0xff) << 8) | (jpeg[offset + 3] & 0xff);
            if (length < 2) return false;
            offset += 2 + length;
        }
        return false;
    }
}
