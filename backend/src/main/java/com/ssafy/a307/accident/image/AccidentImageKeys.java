package com.ssafy.a307.accident.image;

import com.ssafy.a307.accident.entity.ImageVariant;

/**
 * S3 키 규칙. <b>키는 서버만 만든다</b> — 요청으로 받지 않는다.
 *
 * <pre>
 * accidents/{accidentId}/images/{imageId}/{variant}.{ext}
 * 예) accidents/12/images/340/original.jpg
 *     accidents/12/images/340/resized.jpg
 *     accidents/12/images/340/thumbnail.jpg
 * </pre>
 *
 * 규칙 세 가지가 의도적이다.
 * <ul>
 *   <li><b>원본 파일명을 키에 넣지 않는다.</b> 사용자 입력이 스토리지 경로에 들어가면 경로 조작과
 *       인코딩 문제가 생긴다. 원본 파일명은 {@code accident_image.original_filename} 에만 둔다</li>
 *   <li>variant 는 키에서 소문자, DB {@code variant} 컬럼에는 대문자 상수명 그대로</li>
 *   <li>사고·이미지 ID 로 접두어가 갈리므로 사고 단위 일괄 삭제와 접근 제어를 접두어로 걸 수 있다</li>
 * </ul>
 *
 * 키 길이는 {@code accident_image_asset.s3_key VARCHAR(500)} 에 들어가야 한다. 위 형태는
 * ID 가 19자리까지 커져도 100자를 넘지 않으므로 여유가 충분하다.
 */
public final class AccidentImageKeys {

    public static final String ACCIDENT_PREFIX = "accidents";
    public static final String IMAGE_SEGMENT = "images";
    public static final int MAX_KEY_LENGTH = 500;

    private AccidentImageKeys() {
    }

    public static String key(long accidentId, long imageId, ImageVariant variant, String extension) {
        if (accidentId <= 0) throw new IllegalArgumentException("accidentId must be positive");
        if (imageId <= 0) throw new IllegalArgumentException("imageId must be positive");
        if (variant == null) throw new IllegalArgumentException("variant is required");
        if (extension == null || extension.isBlank()) {
            throw new IllegalArgumentException("extension is required");
        }
        if (extension.contains("/") || extension.contains("\\") || extension.contains(".")) {
            throw new IllegalArgumentException("extension must not contain path separators or dots");
        }
        String key = ACCIDENT_PREFIX + "/" + accidentId + "/" + IMAGE_SEGMENT + "/" + imageId
                + "/" + variant.objectName() + "." + extension;
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("s3 key must not exceed " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }

    /** 사고 1건의 이미지 전체 접두어. 사고 삭제 시 일괄 정리에 쓴다. */
    public static String accidentPrefix(long accidentId) {
        return ACCIDENT_PREFIX + "/" + accidentId + "/" + IMAGE_SEGMENT + "/";
    }

    /** 이미지 1장의 접두어. */
    public static String imagePrefix(long accidentId, long imageId) {
        return accidentPrefix(accidentId) + imageId + "/";
    }
}
