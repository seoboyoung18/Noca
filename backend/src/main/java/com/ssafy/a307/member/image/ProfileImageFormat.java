package com.ssafy.a307.member.image;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 프로필 이미지가 받는 형식과 그 시그니처.
 * <p>
 * <b>{@code accident.image.ImageFormat}·{@code ImageSignatures} 와 규칙이 겹친다.</b>
 * 그쪽을 공용 위치로 올리는 편이 맞지만 담당 밖 파일이라 협의 전까지는 여기 최소한만 둔다.
 * 지원 형식을 바꿀 일이 생기면 <b>두 곳을 같이 봐야 한다</b>.
 *
 * <p>키({@code profile/{memberId}})에 확장자가 없어 형식은 저장된 바이트로만 판정할 수 있다.
 * 그래서 클라이언트가 보낸 {@code Content-Type} 을 믿지 않고 시그니처로 다시 확인한다 —
 * presigned PUT 은 브라우저가 직접 올리므로 서버가 본 적 없는 바이트가 들어온다.
 */
public enum ProfileImageFormat {

    JPEG("image/jpeg") {
        @Override
        boolean matches(byte[] head) {
            return startsWith(head, 0xFF, 0xD8, 0xFF);
        }
    },
    PNG("image/png") {
        @Override
        boolean matches(byte[] head) {
            return startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
        }
    },
    /**
     * ISO-BMFF 컨테이너라 앞 4바이트가 박스 길이고 그다음이 {@code ftyp} 다.
     * 브랜드까지 봐야 HEIC 인지 다른 BMFF(예: MP4) 인지 갈린다.
     */
    HEIC("image/heic") {
        @Override
        boolean matches(byte[] head) {
            if (head.length < 12 || !startsWithAt(head, 4, 'f', 't', 'y', 'p')) {
                return false;
            }
            String brand = new String(head, 8, 4).toLowerCase(Locale.ROOT);
            return HEIC_BRANDS.contains(brand);
        }
    };

    /** 아이폰이 실제로 내보내는 브랜드들. {@code mif1}·{@code msf1} 은 HEIF 정지영상이다. */
    private static final List<String> HEIC_BRANDS =
            List.of("heic", "heix", "hevc", "heim", "heis", "hevm", "hevs", "mif1", "msf1");

    /** 시그니처 판정에 필요한 최소 바이트. HEIC 가 12바이트로 가장 길다. */
    public static final int SIGNATURE_LENGTH = 12;

    private final String contentType;

    ProfileImageFormat(String contentType) {
        this.contentType = contentType;
    }

    abstract boolean matches(byte[] head);

    public String contentType() {
        return contentType;
    }

    /** 클라이언트가 업로드 URL 을 받을 때 선언한 {@code Content-Type} 을 형식으로 바꾼다. */
    public static Optional<ProfileImageFormat> ofContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String normalized = contentType.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(format -> format.contentType.equals(normalized))
                .findFirst();
    }

    /** 실제 바이트에서 형식을 알아낸다. 선언값과 다르면 호출부가 거절한다. */
    public static Optional<ProfileImageFormat> detect(byte[] content) {
        if (content == null || content.length < SIGNATURE_LENGTH) {
            return Optional.empty();
        }
        byte[] head = Arrays.copyOf(content, SIGNATURE_LENGTH);
        return Arrays.stream(values())
                .filter(format -> format.matches(head))
                .findFirst();
    }

    public static String supportedLabel() {
        return "JPG, PNG, HEIC";
    }

    private static boolean startsWith(byte[] head, int... expected) {
        return startsWithAt(head, 0, toChars(expected));
    }

    private static boolean startsWithAt(byte[] head, int offset, char... expected) {
        if (head.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((head[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static char[] toChars(int[] values) {
        char[] chars = new char[values.length];
        for (int i = 0; i < values.length; i++) {
            chars[i] = (char) values[i];
        }
        return chars;
    }
}
