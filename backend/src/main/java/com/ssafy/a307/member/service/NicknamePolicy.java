package com.ssafy.a307.member.service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 가입 화면에 미리 채워 줄 닉네임 초기값을 만든다.
 * <p>
 * 최종 저장되는 닉네임은 사용자가 입력한 값이다. 여기서 만드는 것은 어디까지나 초기값이라
 * 사용자가 지우고 다시 쓸 수 있다.
 *
 * <p>{@code member.nickname} 이 {@code VARCHAR(12)} 라 그 길이를 넘기지 않는다.
 * 넘는 값을 그대로 내려보내면 프론트가 채운 값을 그대로 제출했을 때 검증에 걸린다.
 */
public final class NicknamePolicy {

    /** {@code member.nickname} 컬럼 길이와 같다. */
    public static final int MAX_LENGTH = 12;

    private static final String GENERATED_PREFIX = "사용자";
    private static final int GENERATED_SUFFIX_LENGTH = 4;
    private static final String SUFFIX_ALPHABET = "0123456789abcdef";

    private NicknamePolicy() {
    }

    /**
     * 소셜이 준 닉네임을 초기값으로 다듬는다.
     *
     * @param socialNickname 소셜 제공자가 준 값. 없거나 공백뿐이면 자동 생성한다
     */
    public static String initialFrom(String socialNickname) {
        if (socialNickname == null || socialNickname.isBlank()) {
            return generate();
        }
        String trimmed = socialNickname.strip();
        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }

    /**
     * {@code 사용자7f3a} 형태. 회원 PK 를 쓰지 않는 이유는 두 가지다 —
     * 아직 회원이 만들어지기 전이라 PK 가 없고, PK 를 닉네임에 노출할 이유도 없다.
     * <p>
     * 닉네임에는 유니크 제약이 없으므로 값이 겹쳐도 문제가 되지 않는다.
     */
    private static String generate() {
        StringBuilder suffix = new StringBuilder(GENERATED_SUFFIX_LENGTH);
        for (int i = 0; i < GENERATED_SUFFIX_LENGTH; i++) {
            suffix.append(SUFFIX_ALPHABET.charAt(
                    ThreadLocalRandom.current().nextInt(SUFFIX_ALPHABET.length())));
        }
        return GENERATED_PREFIX + suffix;
    }
}
