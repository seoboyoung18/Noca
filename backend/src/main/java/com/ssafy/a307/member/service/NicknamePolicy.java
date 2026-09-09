package com.ssafy.a307.member.service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 닉네임 규칙을 한곳에 모은다 — 초기값 생성과 길이 검증 둘 다 여기가 기준이다.
 * <p>
 * 최종 저장되는 닉네임은 사용자가 입력한 값이다. {@link #initialFrom} 이 만드는 것은
 * 어디까지나 초기값이라 사용자가 지우고 다시 쓸 수 있다.
 *
 * <p>{@code member.nickname} 이 {@code VARCHAR(12)} 라 그 길이를 넘기지 않는다.
 * 넘는 값을 그대로 내려보내면 프론트가 채운 값을 그대로 제출했을 때 검증에 걸린다.
 *
 * <p><b>가입과 수정이 같은 규칙을 쓴다.</b> 두 곳에 길이를 따로 적으면 한쪽만 고쳐져
 * 가입은 되는데 수정은 안 되는 닉네임이 생긴다.
 */
public final class NicknamePolicy {

    /** API 명세의 "2~12자" 중 하한. 한 글자짜리는 사람이 알아볼 이름 구실을 못 한다. */
    public static final int MIN_LENGTH = 2;

    /** {@code member.nickname} 컬럼 길이와 같다. */
    public static final int MAX_LENGTH = 12;

    private static final String GENERATED_PREFIX = "사용자";
    private static final int GENERATED_SUFFIX_LENGTH = 4;
    private static final String SUFFIX_ALPHABET = "0123456789abcdef";

    private NicknamePolicy() {
    }

    /**
     * 소셜이 준 닉네임을 초기값으로 다듬는다. 반환값은 항상 {@link #isValid} 를 만족한다 —
     * 프론트가 이 값을 그대로 제출해도 검증에 걸리지 않아야 하기 때문이다.
     *
     * @param socialNickname 소셜 제공자가 준 값. 없거나 공백뿐이거나 너무 짧으면 자동 생성한다
     */
    public static String initialFrom(String socialNickname) {
        if (socialNickname == null || socialNickname.isBlank()) {
            return generate();
        }
        String trimmed = socialNickname.strip();
        // 한 글자 소셜 닉네임이 실제로 온다. 그대로 내려보내면 하한에 걸려
        // 사용자가 이유도 모른 채 가입 버튼에서 막힌다.
        if (trimmed.length() < MIN_LENGTH) {
            return generate();
        }
        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }

    /**
     * 사용자가 입력한 닉네임을 저장 가능한 형태로 다듬는다. 앞뒤 공백은 지운다 —
     * {@code "보영  "} 과 {@code "보영"} 이 다른 닉네임으로 남으면 곤란하다.
     *
     * @throws IllegalArgumentException 다듬은 뒤 길이가 규칙을 벗어나면
     */
    public static String normalize(String nickname) {
        String trimmed = nickname == null ? "" : nickname.strip();
        if (!isValid(trimmed)) {
            throw new IllegalArgumentException(
                    "닉네임은 " + MIN_LENGTH + "~" + MAX_LENGTH + "자여야 합니다.");
        }
        return trimmed;
    }

    /** 이미 다듬어진 값에 대한 길이 규칙. 공백 제거는 {@link #normalize} 가 한다. */
    public static boolean isValid(String nickname) {
        return nickname != null
                && nickname.length() >= MIN_LENGTH
                && nickname.length() <= MAX_LENGTH;
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
