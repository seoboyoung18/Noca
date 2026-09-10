package com.ssafy.a307.accident.image;

import com.ssafy.a307.accident.config.AccidentImageProperties;
import com.ssafy.a307.accident.image.AccidentImageValidationException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 업로드 제약 검증(Task 140). 발급 시점(1단)과 완료 통보 시점(3단)을 따로 본다.
 * <p>
 * 스프링 컨텍스트를 띄우지 않는다 — 이 클래스는 프로퍼티와 디코더만 있으면 되고,
 * 20장·20MB 경계를 여러 번 두드리는 테스트가 컨텍스트 기동 시간을 기다릴 이유가 없다.
 */
@DisplayName("사고 이미지 제약 검증")
class AccidentImageValidatorTest {

    private static final int MAX_COUNT = 20;
    private static final int MAX_SIZE = 20 * 1024 * 1024;

    private final AccidentImageProperties properties =
            new AccidentImageProperties(MAX_COUNT, MAX_SIZE, 1600, 320, 10, 10);
    private final AccidentImageValidator validator =
            new AccidentImageValidator(properties, new ImageIoImageDecoder());

    /** HEIC 서버 변환이 붙은 미래를 흉내내는 디코더. D3 판정이 코드 구조에 갇히지 않았음을 보인다. */
    private final AccidentImageValidator heicCapableValidator = new AccidentImageValidator(
            properties,
            new ImageDecoderPort() {
                @Override
                public boolean supports(ImageFormat format) {
                    return true;
                }

                @Override
                public BufferedImage decode(byte[] content, ImageFormat format) {
                    return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
                }
            });

    @Nested
    @DisplayName("장수 상한")
    class Count {

        @Test
        @DisplayName("20장 요청은 통과하고 21장은 걸린다")
        void boundary() {
            validator.validateCount(0, MAX_COUNT);

            assertThat(reasonOf(() -> validator.validateCount(0, MAX_COUNT + 1)))
                    .isEqualTo(Reason.TOO_MANY_IMAGES);
        }

        @Test
        @DisplayName("이미 15장 등록된 사고에 6장을 더 요청하면 누적으로 걸린다")
        void accumulates() {
            validator.validateCount(15, 5);

            AccidentImageValidationException e = catchThrowableOfType(
                    AccidentImageValidationException.class, () -> validator.validateCount(15, 6));
            assertThat(e.reason()).isEqualTo(Reason.TOO_MANY_IMAGES);
            assertThat(e).hasMessageContaining("등록 15장").hasMessageContaining("요청 6장");
        }

        @Test
        @DisplayName("빈 요청은 파일 없음이다")
        void empty() {
            assertThat(reasonOf(() -> validator.validateCount(0, 0))).isEqualTo(Reason.MISSING_FILE);
        }

        @Test
        @DisplayName("남은 장수는 0 아래로 내려가지 않는다")
        void remainingSlots() {
            assertThat(validator.remainingSlots(0)).isEqualTo(MAX_COUNT);
            assertThat(validator.remainingSlots(MAX_COUNT)).isZero();
            assertThat(validator.remainingSlots(MAX_COUNT + 5)).isZero();
        }
    }

    @Nested
    @DisplayName("1단 — 발급 시점 신고값")
    class Declared {

        @Test
        @DisplayName("20MB 정확히는 통과하고 1바이트 넘으면 걸린다")
        void sizeBoundary() {
            assertThat(validator.validateDeclared("a.jpg", "image/jpeg", MAX_SIZE))
                    .isEqualTo(ImageFormat.JPEG);

            assertThat(reasonOf(() -> validator.validateDeclared("a.jpg", "image/jpeg", MAX_SIZE + 1)))
                    .isEqualTo(Reason.FILE_TOO_LARGE);
        }

        @Test
        @DisplayName("0바이트는 파일 없음이다")
        void zeroSize() {
            assertThat(reasonOf(() -> validator.validateDeclared("a.jpg", "image/jpeg", 0)))
                    .isEqualTo(Reason.MISSING_FILE);
        }

        @ParameterizedTest(name = "{0} 은 받는다")
        @ValueSource(strings = {"a.jpg", "a.jpeg", "b.PNG", "c.png"})
        @DisplayName("jpg·jpeg·png 확장자를 대소문자 구분 없이 받는다")
        void supportedExtensions(String filename) {
            String contentType = filename.toLowerCase().endsWith("png") ? "image/png" : "image/jpeg";
            assertThat(validator.validateDeclared(filename, contentType, 1024)).isNotNull();
        }

        @Test
        @DisplayName("HEIC 은 지원 목록에 있지만 서버 변환이 없어 거절된다")
        void heicIsRejectedWithConversionGuidance() {
            AccidentImageValidationException e = catchThrowableOfType(
                    AccidentImageValidationException.class,
                    () -> validator.validateDeclared("iphone.heic", "image/heic", 1024));

            assertThat(e.reason()).isEqualTo(Reason.SERVER_CONVERSION_UNSUPPORTED);
            assertThat(e).hasMessageContaining("JPG");
            // 확장자·Content-Type 자체는 인정된다 — 목록에서 지운 것이 아니다
            assertThat(ImageFormat.ofExtension("heic")).contains(ImageFormat.HEIC);
        }

        @ParameterizedTest(name = "{0} 은 서버 변환 미지원으로 거절한다")
        @CsvSource({
                "iphone.heic, image/heic",
                "iphone.heif, image/heif",
                "iphone.HEIC, image/heic",
                "galaxy.heic, image/heif",
                "galaxy.heif, image/heic"})
        @DisplayName("HEIC·HEIF 는 확장자·Content-Type 조합과 무관하게 같은 사유로 거절된다")
        void heicAndHeifAreRejectedConsistently(String filename, String contentType) {
            AccidentImageValidationException e = catchThrowableOfType(
                    AccidentImageValidationException.class,
                    () -> validator.validateDeclared(filename, contentType, 1024));

            // 사유가 UNSUPPORTED_EXTENSION 으로 갈리면 안내 문구가 엇갈린다 —
            // "지원하지 않는 확장자" 가 아니라 "JPG 로 변환해 주세요" 여야 한다.
            assertThat(e.reason()).isEqualTo(Reason.SERVER_CONVERSION_UNSUPPORTED);
            assertThat(e).hasMessageContaining("JPG");
        }

        @Test
        @DisplayName("heif 확장자도 HEIC 형식으로 인식한다 — 같은 컨테이너다")
        void heifMapsToHeicFormat() {
            assertThat(ImageFormat.ofExtension("heif")).contains(ImageFormat.HEIC);
            assertThat(ImageFormat.HEIC.acceptsContentType("image/heif")).isTrue();
            assertThat(ImageFormat.HEIC.acceptsContentType("image/heic")).isTrue();
        }

        @Test
        @DisplayName("오류 문구는 실제로 올릴 수 있는 형식만 말한다 — HEIC 을 적지 않는다")
        void uploadableLabelDoesNotPromiseHeic() {
            AccidentImageValidationException e = catchThrowableOfType(
                    AccidentImageValidationException.class,
                    () -> validator.validateDeclared("a.bmp", "image/bmp", 1024));

            assertThat(e).hasMessageContaining("JPG").hasMessageContaining("PNG");
            assertThat(e.getMessage())
                    .as("알아보기만 하고 거절하는 형식을 지원한다고 적으면 거짓말이다")
                    .doesNotContain("HEIC");
        }

        @Test
        @DisplayName("디코더가 HEIC 을 지원하면 그대로 통과한다 — 판정이 코드 구조에 박혀 있지 않다")
        void heicPassesWhenDecoderSupportsIt() {
            assertThat(heicCapableValidator.validateDeclared("iphone.heic", "image/heic", 1024))
                    .isEqualTo(ImageFormat.HEIC);
        }

        @ParameterizedTest(name = "{0} 은 거절한다")
        @ValueSource(strings = {"a.bmp", "a.gif", "a.pdf", "a.webp"})
        @DisplayName("지원하지 않는 확장자는 거절한다")
        void unsupportedExtensions(String filename) {
            assertThat(reasonOf(() -> validator.validateDeclared(filename, "image/jpeg", 1024)))
                    .isEqualTo(Reason.UNSUPPORTED_EXTENSION);
        }

        @Test
        @DisplayName("확장자가 없으면 거절한다")
        void withoutExtension() {
            assertThat(reasonOf(() -> validator.validateDeclared("photo", "image/jpeg", 1024)))
                    .isEqualTo(Reason.UNSUPPORTED_EXTENSION);
            assertThat(reasonOf(() -> validator.validateDeclared("photo.", "image/jpeg", 1024)))
                    .isEqualTo(Reason.UNSUPPORTED_EXTENSION);
        }

        @Test
        @DisplayName("Content-Type 과 확장자가 어긋나면 거절한다")
        void contentTypeMismatch() {
            assertThat(reasonOf(() -> validator.validateDeclared("a.jpg", "image/png", 1024)))
                    .isEqualTo(Reason.UNSUPPORTED_CONTENT_TYPE);
        }

        @Test
        @DisplayName("Content-Type 의 파라미터와 대소문자는 무시한다")
        void contentTypeNormalization() {
            assertThat(validator.validateDeclared("a.jpg", "IMAGE/JPEG; charset=binary", 1024))
                    .isEqualTo(ImageFormat.JPEG);
        }

        @Test
        @DisplayName("파일명 255자 초과·경로 구분자·제어문자는 거절한다")
        void filenameRules() {
            String tooLong = "a".repeat(252) + ".jpg";
            assertThat(tooLong.length()).isEqualTo(256);

            assertThat(reasonOf(() -> validator.validateDeclared(tooLong, "image/jpeg", 1024)))
                    .isEqualTo(Reason.INVALID_FILE_NAME);
            assertThat(reasonOf(() -> validator.validateDeclared("../../etc/a.jpg", "image/jpeg", 1024)))
                    .isEqualTo(Reason.INVALID_FILE_NAME);
            assertThat(reasonOf(() -> validator.validateDeclared("dir\\a.jpg", "image/jpeg", 1024)))
                    .isEqualTo(Reason.INVALID_FILE_NAME);
            assertThat(reasonOf(() -> validator.validateDeclared("a\u0007.jpg", "image/jpeg", 1024)))
                    .isEqualTo(Reason.INVALID_FILE_NAME);
            assertThat(reasonOf(() -> validator.validateDeclared(null, "image/jpeg", 1024)))
                    .isEqualTo(Reason.INVALID_FILE_NAME);
        }

        @Test
        @DisplayName("255자 파일명은 통과한다 — 경계는 초과부터다")
        void filenameBoundary() {
            String exact = "a".repeat(251) + ".jpg";
            assertThat(exact.length()).isEqualTo(255);
            assertThat(validator.validateDeclared(exact, "image/jpeg", 1024)).isEqualTo(ImageFormat.JPEG);
        }
    }

    @Nested
    @DisplayName("3단 — 완료 통보 시점 실제 오브젝트")
    class Stored {

        private final byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x11, 0x22};

        @Test
        @DisplayName("신고 크기와 실제 크기가 다르면 걸린다")
        void sizeMismatch() {
            AccidentImageValidationException e = catchThrowableOfType(
                    AccidentImageValidationException.class,
                    () -> validator.validateStored("a.jpg", jpeg.length, jpeg, 999L));

            assertThat(e.reason()).isEqualTo(Reason.SIZE_MISMATCH);
            assertThat(e).hasMessageContaining("999").hasMessageContaining(String.valueOf(jpeg.length));
        }

        @Test
        @DisplayName("신고 크기를 모르면 상한과 시그니처만 본다")
        void withoutDeclaredSize() {
            assertThat(validator.validateStored("a.jpg", jpeg.length, jpeg, null))
                    .isEqualTo(ImageFormat.JPEG);
        }

        @Test
        @DisplayName("실제 크기가 상한을 넘으면 걸린다 — 신고값이 작아도 소용없다")
        void actualSizeOverLimit() {
            assertThat(reasonOf(() -> validator.validateStored(
                    "a.jpg", MAX_SIZE + 1L, jpeg, MAX_SIZE + 1L)))
                    .isEqualTo(Reason.FILE_TOO_LARGE);
        }

        @Test
        @DisplayName("PNG 시그니처를 .jpg 이름으로 올리면 걸린다")
        void signatureMismatch() {
            byte[] png = ImageSignaturesTest.pngBytes();
            assertThat(reasonOf(() -> validator.validateStored("a.jpg", png.length, png, null)))
                    .isEqualTo(Reason.SIGNATURE_MISMATCH);
        }

        @Test
        @DisplayName("확장자를 .jpg 로 바꾼 HEIC 은 매직바이트에서 걸린다")
        void heicRenamedToJpgIsCaughtBySignature() {
            byte[] heic = ImageSignaturesTest.heicBytes("heic");

            // 1단은 통과한다 — 확장자·Content-Type 만 보면 JPEG 이다.
            assertThat(validator.validateDeclared("disguised.jpg", "image/jpeg", heic.length))
                    .isEqualTo(ImageFormat.JPEG);
            // 3단이 실제 바이트를 보고 잡는다. 서버는 원본을 받지 않으므로 이 단계가 없으면 못 잡는다.
            assertThat(reasonOf(() -> validator.validateStored(
                    "disguised.jpg", heic.length, heic, (long) heic.length)))
                    .isEqualTo(Reason.SIGNATURE_MISMATCH);
        }

        @Test
        @DisplayName("빈 오브젝트는 파일 없음이다")
        void emptyObject() {
            assertThat(reasonOf(() -> validator.validateStored("a.jpg", 0, new byte[0], null)))
                    .isEqualTo(Reason.MISSING_FILE);
        }

        @Test
        @DisplayName("HEIC 시그니처는 판별되지만 서버 변환이 없어 거절된다")
        void heicStored() {
            byte[] heic = ImageSignaturesTest.heicBytes("heic");
            assertThat(reasonOf(() -> validator.validateStored("a.heic", heic.length, heic, null)))
                    .isEqualTo(Reason.SERVER_CONVERSION_UNSUPPORTED);

            assertThat(heicCapableValidator.validateStored("a.heic", heic.length, heic, null))
                    .isEqualTo(ImageFormat.HEIC);
        }
    }

    @Test
    @DisplayName("검증 실패는 BusinessException 이 아니라 도메인 예외다 — 부분 실패 처리에서 분류가 필요하다")
    void throwsDomainException() {
        assertThatThrownBy(() -> validator.validateDeclared("a.gif", "image/gif", 10))
                .isInstanceOf(AccidentImageValidationException.class)
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Reason reasonOf(Runnable call) {
        AccidentImageValidationException e =
                catchThrowableOfType(AccidentImageValidationException.class, call::run);
        assertThat(e).as("검증 예외가 던져져야 한다").isNotNull();
        return e.reason();
    }
}
