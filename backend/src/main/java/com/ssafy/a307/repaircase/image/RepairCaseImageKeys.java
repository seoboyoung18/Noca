package com.ssafy.a307.repaircase.image;

import com.ssafy.a307.accident.entity.ImageVariant;

/**
 * AI-Hub 검색 사례 이미지의 S3 key 규칙.
 *
 * <pre>
 * repair-cases/{caseId}/images/{caseImageId}/{variant}.{ext}
 * 예) repair-cases/1205/images/88421/original.jpg
 * </pre>
 *
 * <p>실사용자 사고 이미지가 검색 사례로 색인되는 경우에는 이 규칙으로 복사하지
 * 않고 기존 {@code AccidentImageKeys} key를 참조한다.</p>
 */
public final class RepairCaseImageKeys {

    public static final String REPAIR_CASE_PREFIX = "repair-cases";
    public static final String IMAGE_SEGMENT = "images";
    public static final int MAX_KEY_LENGTH = 500;

    private RepairCaseImageKeys() {
    }

    public static String key(long caseId, long caseImageId, ImageVariant variant, String extension) {
        if (caseId <= 0) throw new IllegalArgumentException("caseId must be positive");
        if (caseImageId <= 0) throw new IllegalArgumentException("caseImageId must be positive");
        if (variant == null) throw new IllegalArgumentException("variant is required");
        if (extension == null || extension.isBlank()) {
            throw new IllegalArgumentException("extension is required");
        }
        if (extension.contains("/") || extension.contains("\\") || extension.contains(".")) {
            throw new IllegalArgumentException("extension must not contain path separators or dots");
        }
        String key = REPAIR_CASE_PREFIX + "/" + caseId + "/" + IMAGE_SEGMENT + "/" + caseImageId
                + "/" + variant.objectName() + "." + extension;
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("s3 key must not exceed " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }

    public static String casePrefix(long caseId) {
        if (caseId <= 0) throw new IllegalArgumentException("caseId must be positive");
        return REPAIR_CASE_PREFIX + "/" + caseId + "/" + IMAGE_SEGMENT + "/";
    }

    public static String imagePrefix(long caseId, long caseImageId) {
        if (caseImageId <= 0) throw new IllegalArgumentException("caseImageId must be positive");
        return casePrefix(caseId) + caseImageId + "/";
    }
}
