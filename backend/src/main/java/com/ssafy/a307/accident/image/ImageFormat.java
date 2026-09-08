package com.ssafy.a307.accident.image;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 사고 이미지가 받아들이는 형식. 요구사항 21행이 정한 <b>JPG·PNG·HEIC</b> 세 가지다.
 * <p>
 * {@code EstimateFileValidator} 의 관례를 그대로 가져왔다 — 확장자 하나에 Content-Type 하나를
 * 정확히 대응시키고, 매직바이트까지 3중으로 일치해야 통과다. 확장자만 바꿔 올리는 우회를 막는다.
 * <p>
 * <b>HEIC 를 목록에서 지우지 않는다.</b> 요구사항이 HEIC 지원을 명시하므로 형식으로는 알고 있어야
 * 하고, 서버 변환만 미구현이다. 디코딩 가능 여부는 이 enum 이 아니라 {@link ImageDecoderPort} 가
 * 답한다 — 나중에 libheif 어댑터가 붙으면 이 파일은 그대로 두고 디코더만 갈아 끼운다(answer25 D3).
 */
public enum ImageFormat {

    JPEG("image/jpeg", "jpg", "jpg", "jpeg"),
    PNG("image/png", "png", "png"),
    HEIC("image/heic", "heic", "heic");

    private final String contentType;
    private final String canonicalExtension;
    private final Set<String> extensions;

    ImageFormat(String contentType, String canonicalExtension, String... extensions) {
        this.contentType = contentType;
        this.canonicalExtension = canonicalExtension;
        this.extensions = new LinkedHashSet<>(Arrays.asList(extensions));
    }

    public String contentType() {
        return contentType;
    }

    /** S3 키에 쓰는 확장자. {@code jpeg} 로 올려도 키는 {@code jpg} 로 통일한다. */
    public String canonicalExtension() {
        return canonicalExtension;
    }

    public Set<String> extensions() {
        return Set.copyOf(extensions);
    }

    public static Optional<ImageFormat> ofExtension(String extension) {
        if (extension == null) return Optional.empty();
        String normalized = extension.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(f -> f.extensions.contains(normalized)).findFirst();
    }

    /** 사람이 읽는 오류 메시지용 — "JPG, PNG, HEIC". */
    public static String supportedLabel() {
        return "JPG, PNG, HEIC";
    }
}
