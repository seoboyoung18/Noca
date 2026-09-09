package com.ssafy.a307.member.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로필 이미지 S3 키.
 * <p>
 * {@link ProfileImageKeys#isOwnStagingKey} 는 보안 경계다 — 업로드 완료 통보의 키는
 * 클라이언트가 보내는 값이라, 이 검사가 뚫리면 남의 업로드를 자기 프로필로 만들 수 있다.
 */
@DisplayName("프로필 이미지 키 규칙")
class ProfileImageKeysTest {

    @Test
    @DisplayName("service 키는 profile/{memberId} 이고 확장자가 없다")
    void serviceKeyHasNoExtension() {
        assertThat(ProfileImageKeys.serviceKey(42)).isEqualTo("profile/42");
    }

    @Test
    @DisplayName("같은 회원의 service 키는 언제나 같다 — 다시 올리면 덮어쓴다")
    void serviceKeyIsStable() {
        assertThat(ProfileImageKeys.serviceKey(42)).isEqualTo(ProfileImageKeys.serviceKey(42));
    }

    @Test
    @DisplayName("staging 키는 회원 폴더 아래 매번 다른 값이다")
    void stagingKeyIsUniquePerUpload() {
        String first = ProfileImageKeys.newStagingKey(42);
        String second = ProfileImageKeys.newStagingKey(42);

        assertThat(first).startsWith("profile/42/");
        assertThat(second).startsWith("profile/42/");
        // 같은 회원이 업로드를 두 번 시작해도 서로를 덮어쓰면 안 된다
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("자기 staging 키는 통과한다")
    void acceptsOwnStagingKey() {
        String key = ProfileImageKeys.newStagingKey(42);

        assertThat(ProfileImageKeys.isOwnStagingKey(key, 42)).isTrue();
    }

    @Test
    @DisplayName("남의 staging 키는 거절한다")
    void rejectsOtherMembersKey() {
        String key = ProfileImageKeys.newStagingKey(7);

        assertThat(ProfileImageKeys.isOwnStagingKey(key, 42)).isFalse();
    }

    /**
     * {@code profile/4} 로 시작한다는 이유로 {@code profile/42/...} 가 통과하면 안 된다.
     * 접두사 비교만 하면 회원 4 가 회원 42 의 업로드를 가져갈 수 있다.
     */
    @Test
    @DisplayName("회원 번호가 접두사로 겹쳐도 거절한다")
    void rejectsPrefixCollision() {
        String key = ProfileImageKeys.newStagingKey(42);

        assertThat(ProfileImageKeys.isOwnStagingKey(key, 4)).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "profile/42",                    // staging 이 아니라 service 키다
            "profile/42/../7/abc",           // 상위 경로 탈출
            "profile/42/sub/abc",            // 폴더를 더 판 키
            "profile/7/abc",                 // 남의 것
            "accidents/1/images/1/original.jpg"
    })
    @DisplayName("규칙에서 벗어난 키는 모두 거절한다")
    void rejectsMalformedKeys(String key) {
        assertThat(ProfileImageKeys.isOwnStagingKey(key, 42)).isFalse();
    }
}
