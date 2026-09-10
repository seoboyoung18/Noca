package com.ssafy.a307.accident.image;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 사고 이미지가 <b>알아보는</b> 형식. 요구사항 21행의 JPG·PNG·HEIC 세 가지다.
 *
 * <p><b>"알아본다" 와 "올릴 수 있다" 는 다르다.</b>
 * <pre>
 * 지금 업로드 가능 : JPG · PNG
 * HEIC / HEIF     : 서버 변환 미지원 — presigned URL 발급 전에 400 으로 거절한다
 * </pre>
 * HEIC 를 목록에서 지우지 않는 이유는 <b>거절 사유를 정확히 말하기 위해서</b>다. 목록에서
 * 빼면 {@code UNSUPPORTED_EXTENSION}("JPG, PNG 만 됩니다") 이 나가는데, 실제로는 형식은 아는데
 * 서버가 디코딩을 못 하는 것이라 {@code SERVER_CONVERSION_UNSUPPORTED}("JPG 로 변환해 주세요")
 * 가 사용자에게 훨씬 쓸모 있다. 판정은 이 enum 이 아니라 {@link ImageDecoderPort} 가 한다 —
 * 나중에 libheif 어댑터가 붙으면 이 파일은 그대로 두고 디코더만 갈아 끼운다(answer25 D3).
 *
 * <p>{@code EstimateFileValidator} 의 관례를 그대로 가져왔다 — 확장자에 Content-Type 을
 * 대응시키고, 매직바이트까지 3중으로 일치해야 통과다. 확장자만 바꿔 올리는 우회를 막는다.
 *
 * <p><b>{@code heif} 도 HEIC 로 접는다 — 추가.</b> 갤럭시·아이폰이 같은 컨테이너를 두 확장자로
 * 내보내는데, {@code heif} 를 모르면 "확장자 미지원" 이라는 다른 사유가 나가 안내 문구가
 * 엇갈린다. 컨테이너가 같으므로({@code ImageSignatures} 의 {@code ftyp} 브랜드 판별) 한 형식으로 묶는다.
 */
public enum ImageFormat {

    JPEG("image/jpeg", Set.of("image/jpeg"), "jpg", "jpg", "jpeg"),
    PNG("image/png", Set.of("image/png"), "png", "png"),
    HEIC("image/heic", Set.of("image/heic", "image/heif"), "heic", "heic", "heif");

    private final String contentType;
    private final Set<String> contentTypes;
    private final String canonicalExtension;
    private final Set<String> extensions;

    ImageFormat(String contentType, Set<String> contentTypes,
                String canonicalExtension, String... extensions) {
        this.contentType = contentType;
        this.contentTypes = Set.copyOf(contentTypes);
        this.canonicalExtension = canonicalExtension;
        this.extensions = new LinkedHashSet<>(Arrays.asList(extensions));
    }

    /** 대표 Content-Type. 오류 메시지가 "무엇이어야 하는지" 를 말할 때 쓴다. */
    public String contentType() {
        return contentType;
    }

    /**
     * 이 형식으로 인정하는 Content-Type 전부. HEIC 만 둘이다({@code image/heic}·{@code image/heif}) —
     * 같은 컨테이너를 기기마다 다르게 적어 보낸다.
     */
    public boolean acceptsContentType(String normalized) {
        return normalized != null && contentTypes.contains(normalized);
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

    /**
     * 사람이 읽는 오류 메시지용 — <b>실제로 올릴 수 있는 것만</b> 적는다.
     *
     * <p>예전에는 {@code "JPG, PNG, HEIC"} 를 돌려줬는데, HEIC 는 알아보기만 하고 업로드는
     * 거절하므로 사용자에게 거짓말이었다. 이 문구는 확장자 자체를 모를 때 나가고, HEIC 는
     * 그 전에 {@code SERVER_CONVERSION_UNSUPPORTED} 로 갈라진다.
     */
    public static String uploadableLabel() {
        return "JPG, PNG";
    }
}
