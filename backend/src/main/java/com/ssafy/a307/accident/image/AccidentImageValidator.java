package com.ssafy.a307.accident.image;

import com.ssafy.a307.accident.config.AccidentImageProperties;
import com.ssafy.a307.accident.image.AccidentImageValidationException.Reason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 업로드 제약 검증(Task 140). {@code EstimateFileValidator} 의 house pattern —
 * 파일명 · 확장자 · Content-Type · <b>매직바이트</b> 3중 검증 — 을 그대로 따르고,
 * presigned 직접 업로드 때문에 <b>검증 시점이 둘로 나뉜다</b>.
 *
 * <pre>
 * 1단 · 발급 시점  validateDeclared(...)   신고값만 있다. 파일명·확장자·Content-Type·크기
 * 2단 · S3 조건    AccidentImageStoragePort.UploadUrlRequest 가 크기·Content-Type 을 presigned 에 박는다
 * 3단 · 완료 통보  validateStored(...)     실제 오브젝트의 크기와 매직바이트를 다시 본다
 * </pre>
 *
 * 3단이 있는 이유는 1단의 값이 전부 <b>클라이언트 신고값</b>이기 때문이다. 서버는 원본 바이트를
 * 받지 않으므로 신고값을 신뢰하면 검증이 없는 것과 같다. 어댑터가 없어 3단을 실제로 실행할 수
 * 없더라도 이 클래스와 단위 테스트는 완성해 둔다 — 검증을 건너뛰는 코드를 남기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AccidentImageValidator {

    public static final int MAX_FILENAME_LENGTH = 255;

    private final AccidentImageProperties properties;
    private final ImageDecoderPort decoder;

    /**
     * 1단 — 발급 시점. 신고된 파일명·Content-Type·크기를 본다.
     *
     * @return 판별된 형식. 키의 확장자를 여기서 정한다
     * @throws AccidentImageValidationException 제약 위반
     */
    public ImageFormat validateDeclared(String originalFilename, String contentType, long declaredSize) {
        validateFilename(originalFilename);
        if (declaredSize < 1) {
            throw new AccidentImageValidationException(Reason.MISSING_FILE, "이미지 파일이 필요합니다.");
        }
        if (declaredSize > properties.maxFileSizeBytes()) {
            throw new AccidentImageValidationException(
                    Reason.FILE_TOO_LARGE,
                    "이미지는 장당 %dMB 이하여야 합니다. (신고 크기 %d바이트)"
                            .formatted(properties.maxFileSizeBytes() / (1024 * 1024), declaredSize));
        }
        ImageFormat format = ImageFormat.ofExtension(extension(originalFilename))
                .orElseThrow(() -> new AccidentImageValidationException(
                        Reason.UNSUPPORTED_EXTENSION,
                        ImageFormat.supportedLabel() + " 파일만 업로드할 수 있습니다."));
        if (!format.contentType().equals(normalizeContentType(contentType))) {
            throw new AccidentImageValidationException(
                    Reason.UNSUPPORTED_CONTENT_TYPE,
                    "파일 Content-Type 과 확장자가 일치하지 않습니다. (확장자 기준 %s)"
                            .formatted(format.contentType()));
        }
        requireDecodable(format);
        return format;
    }

    /**
     * 3단 — 완료 통보 시점. 저장소에서 읽은 실제 바이트를 본다.
     *
     * @param declaredSize 발급 때 신고한 크기. 모르면 null — 그때는 상한만 본다
     * @throws AccidentImageValidationException 크기 불일치·상한 초과·시그니처 불일치
     */
    public ImageFormat validateStored(String originalFilename, long actualSize, byte[] content, Long declaredSize) {
        ImageFormat format = ImageFormat.ofExtension(extension(originalFilename))
                .orElseThrow(() -> new AccidentImageValidationException(
                        Reason.UNSUPPORTED_EXTENSION,
                        ImageFormat.supportedLabel() + " 파일만 업로드할 수 있습니다."));
        if (content == null || content.length == 0 || actualSize < 1) {
            throw new AccidentImageValidationException(
                    Reason.MISSING_FILE, "저장소에 업로드된 이미지가 없습니다.");
        }
        if (actualSize > properties.maxFileSizeBytes()) {
            throw new AccidentImageValidationException(
                    Reason.FILE_TOO_LARGE,
                    "이미지는 장당 %dMB 이하여야 합니다. (실제 크기 %d바이트)"
                            .formatted(properties.maxFileSizeBytes() / (1024 * 1024), actualSize));
        }
        if (declaredSize != null && declaredSize != actualSize) {
            throw new AccidentImageValidationException(
                    Reason.SIZE_MISMATCH,
                    "신고한 크기와 실제 업로드 크기가 다릅니다. (신고 %d바이트, 실제 %d바이트)"
                            .formatted(declaredSize, actualSize));
        }
        if (!ImageSignatures.matches(content, format)) {
            throw new AccidentImageValidationException(
                    Reason.SIGNATURE_MISMATCH,
                    "파일 시그니처와 확장자가 일치하지 않습니다.");
        }
        requireDecodable(format);
        return format;
    }

    /**
     * 누적 장수 제약. 이미 등록된 수 + 이번 요청 수로 판정한다 — 요청 하나만 보고 20장 이하라고
     * 통과시키면 여러 번 나눠 보내 상한을 넘길 수 있다.
     */
    public void validateCount(long alreadyRegistered, int requested) {
        if (requested < 1) {
            throw new AccidentImageValidationException(Reason.MISSING_FILE, "업로드할 파일 정보가 필요합니다.");
        }
        long total = alreadyRegistered + requested;
        if (total > properties.maxCountPerAccident()) {
            throw new AccidentImageValidationException(
                    Reason.TOO_MANY_IMAGES,
                    "사고 1건에는 이미지를 최대 %d장까지 등록할 수 있습니다. (등록 %d장 + 요청 %d장)"
                            .formatted(properties.maxCountPerAccident(), alreadyRegistered, requested));
        }
    }

    /** 남은 등록 가능 장수. 화면이 "몇 장 더 올릴 수 있는지" 를 보여줄 수 있게 응답에 담는다. */
    public int remainingSlots(long alreadyRegistered) {
        return (int) Math.max(0, properties.maxCountPerAccident() - alreadyRegistered);
    }

    private void requireDecodable(ImageFormat format) {
        if (!decoder.supports(format)) {
            throw new AccidentImageValidationException(
                    Reason.SERVER_CONVERSION_UNSUPPORTED,
                    "%s 형식은 서버 변환을 지원하지 않습니다. 업로드 전 JPG 로 변환해 주세요."
                            .formatted(format.name()));
        }
    }

    /** {@code EstimateFileValidator.validateFilename} 과 같은 규칙이다. */
    private void validateFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > MAX_FILENAME_LENGTH
                || filename.contains("/") || filename.contains("\\")
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw new AccidentImageValidationException(
                    Reason.INVALID_FILE_NAME, "원본 파일명이 올바르지 않습니다.");
        }
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            throw new AccidentImageValidationException(
                    Reason.UNSUPPORTED_EXTENSION, "파일 확장자가 필요합니다.");
        }
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null) return "";
        return contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }
}
