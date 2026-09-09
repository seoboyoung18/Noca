package com.ssafy.a307.member.image;

import java.util.UUID;

/**
 * 프로필 이미지의 S3 키. 팀 S3 안내의 {@code profile/{memberId}} 규칙을 따른다.
 * <p>
 * <b>확장자가 없다.</b> 그래서 같은 회원의 이미지는 언제나 같은 키가 되고, 다시 올리면
 * 덮어써진다 — 등록·변경이 {@code PUT} 하나로 끝나고 이전 파일이 남지 않는 이유다.
 * 대신 형식은 키에서 알 수 없어 {@code Content-Type} 을 객체 메타데이터로 저장한다.
 *
 * <p>업로드 격리용 staging 키는 회원별 폴더 아래 UUID 를 붙인다. 같은 회원이 업로드를
 * 두 번 시작해도 서로를 덮어쓰지 않게 하기 위해서다. staging 버킷은 7일 뒤 자동 삭제된다.
 */
public final class ProfileImageKeys {

    public static final String PREFIX = "profile";

    private ProfileImageKeys() {
    }

    /** 최종 저장 위치. 회원당 하나뿐이다. */
    public static String serviceKey(long memberId) {
        requirePositive(memberId);
        return PREFIX + "/" + memberId;
    }

    /** 브라우저가 직접 PUT 할 위치. 매번 새로 만든다. */
    public static String newStagingKey(long memberId) {
        requirePositive(memberId);
        return stagingPrefix(memberId) + UUID.randomUUID();
    }

    /**
     * 업로드 완료 통보로 받은 키가 이 회원의 것인지 확인한다.
     * <p>
     * <b>이 검사가 없으면 남의 staging 키를 그대로 보내 자기 프로필로 만들 수 있다.</b>
     * 키는 클라이언트가 돌려주는 값이라 신뢰할 수 없다.
     */
    public static boolean isOwnStagingKey(String stagingKey, long memberId) {
        requirePositive(memberId);
        if (stagingKey == null) {
            return false;
        }
        String expectedPrefix = stagingPrefix(memberId);
        return stagingKey.startsWith(expectedPrefix)
                // 접두사만 보면 profile/1/../2 같은 값이 통과한다
                && !stagingKey.substring(expectedPrefix.length()).contains("/")
                && !stagingKey.contains("..");
    }

    private static String stagingPrefix(long memberId) {
        return PREFIX + "/" + memberId + "/";
    }

    private static void requirePositive(long memberId) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
    }
}
