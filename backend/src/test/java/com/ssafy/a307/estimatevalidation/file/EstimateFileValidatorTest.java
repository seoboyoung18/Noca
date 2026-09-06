package com.ssafy.a307.estimatevalidation.file;

import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.ssafy.a307.estimatevalidation.file.EstimateFileValidationException.Reason.FILE_TOO_LARGE;
import static com.ssafy.a307.estimatevalidation.file.EstimateFileValidationException.Reason.INVALID_FILE_NAME;
import static com.ssafy.a307.estimatevalidation.file.EstimateFileValidationException.Reason.MISSING_FILE;
import static com.ssafy.a307.estimatevalidation.file.EstimateFileValidationException.Reason.SIGNATURE_MISMATCH;
import static com.ssafy.a307.estimatevalidation.file.EstimateFileValidationException.Reason.UNSUPPORTED_CONTENT_TYPE;
import static com.ssafy.a307.estimatevalidation.file.EstimateFileValidationException.Reason.UNSUPPORTED_EXTENSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EstimateFileValidatorTest {

    private final EstimateFileValidator validator = new EstimateFileValidator();

    @ParameterizedTest
    @MethodSource("validFiles")
    void acceptsSupportedFilesAndDetectsType(
            String filename,
            String contentType,
            byte[] content,
            String expectedExtension,
            EstimateFileType expectedType
    ) {
        ValidatedEstimateFile result = validator.validate(filename, contentType, content);

        assertThat(result.originalFilename()).isEqualTo(filename);
        assertThat(result.extension()).isEqualTo(expectedExtension);
        assertThat(result.contentType()).isEqualTo(contentType.toLowerCase());
        assertThat(result.fileType()).isEqualTo(expectedType);
        assertThat(result.size()).isEqualTo(content.length);
    }

    @Test
    void acceptsMaximumSizeAndContentTypeParameters() {
        ValidatedEstimateFile result = validator.validate(
                "estimate.PDF",
                "Application/Pdf; charset=binary",
                EstimateFileValidator.MAX_FILE_SIZE,
                pdf()
        );

        assertThat(result.fileType()).isEqualTo(EstimateFileType.PDF);
        assertThat(result.contentType()).isEqualTo("application/pdf");
        assertThat(result.size()).isEqualTo(EstimateFileValidator.MAX_FILE_SIZE);
    }

    @Test
    void rejectsMissingFile() {
        assertReason(MISSING_FILE, () -> validator.validate("estimate.jpg", "image/jpeg", null));
        assertReason(MISSING_FILE, () -> validator.validate("estimate.jpg", "image/jpeg", new byte[0]));
    }

    @Test
    void rejectsOversizeFile() {
        assertReason(FILE_TOO_LARGE, () -> validator.validate(
                "estimate.png",
                "image/png",
                EstimateFileValidator.MAX_FILE_SIZE + 1,
                png()
        ));
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void rejectsInvalidFilename(String filename) {
        assertReason(INVALID_FILE_NAME, () -> validator.validate(filename, "image/jpeg", jpeg()));
    }

    @ParameterizedTest
    @MethodSource("unsupportedNames")
    void rejectsMissingOrUnsupportedExtension(String filename) {
        assertReason(UNSUPPORTED_EXTENSION, () -> validator.validate(filename, "image/jpeg", jpeg()));
    }

    @Test
    void rejectsSpoofedContentType() {
        assertReason(UNSUPPORTED_CONTENT_TYPE,
                () -> validator.validate("estimate.jpg", "image/png", jpeg()));
        assertReason(UNSUPPORTED_CONTENT_TYPE,
                () -> validator.validate("estimate.jpg", null, jpeg()));
    }

    @Test
    void rejectsSpoofedSignature() {
        assertReason(SIGNATURE_MISMATCH,
                () -> validator.validate("estimate.jpg", "image/jpeg", png()));
        assertReason(SIGNATURE_MISMATCH,
                () -> validator.validate("estimate.pdf", "application/pdf", new byte[]{'P', 'D', 'F'}));
    }

    private void assertReason(EstimateFileValidationException.Reason expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(EstimateFileValidationException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(expected));
    }

    private static Stream<Arguments> validFiles() {
        return Stream.of(
                Arguments.of("estimate.jpg", "image/jpeg", jpeg(), "jpg", EstimateFileType.IMAGE),
                Arguments.of("estimate.JPEG", "image/jpeg", jpeg(), "jpeg", EstimateFileType.IMAGE),
                Arguments.of("estimate.png", "image/png", png(), "png", EstimateFileType.IMAGE),
                Arguments.of("estimate.pdf", "application/pdf", pdf(), "pdf", EstimateFileType.PDF)
        );
    }

    private static Stream<String> invalidNames() {
        return Stream.of(null, "", "   ", "../estimate.jpg", "folder\\estimate.jpg", "estimate\n.jpg");
    }

    private static Stream<String> unsupportedNames() {
        return Stream.of("estimate", ".jpg", "estimate.", "estimate.gif", "estimate.heic");
    }

    private static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    }

    private static byte[] pdf() {
        return new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37};
    }
}
