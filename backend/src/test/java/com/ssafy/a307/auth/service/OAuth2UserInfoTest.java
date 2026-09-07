package com.ssafy.a307.auth.service;

import com.ssafy.a307.member.entity.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 소셜 응답 파싱. 네트워크를 타지 않고 응답 모양만 검증한다.
 */
@DisplayName("소셜 응답 파싱")
class OAuth2UserInfoTest {

    @Nested
    @DisplayName("카카오")
    class Kakao {

        @Test
        @DisplayName("회원번호와 닉네임을 뽑는다")
        void extractsIdAndNickname() {
            OAuth2UserInfo info = OAuth2UserInfo.of("kakao", kakaoResponse(3_812_345_678L, "홍길동"));

            assertThat(info.provider()).isEqualTo(Provider.KAKAO);
            assertThat(info.providerUserId()).isEqualTo("3812345678");
            assertThat(info.nickname()).isEqualTo("홍길동");
        }

        /**
         * 카카오 회원번호는 JSON 숫자라 값 크기에 따라 Integer 로도 Long 으로도 역직렬화된다.
         * Long 으로 캐스팅하면 작은 값에서 ClassCastException 이 나므로 문자열로 받는다.
         */
        @Test
        @DisplayName("회원번호가 Integer 로 와도 Long 으로 와도 같은 문자열이 된다")
        void handlesBothIntegerAndLongId() {
            OAuth2UserInfo asInteger = OAuth2UserInfo.of("kakao", kakaoResponse(12345, "홍길동"));
            OAuth2UserInfo asLong = OAuth2UserInfo.of("kakao", kakaoResponse(12345L, "홍길동"));

            assertThat(asInteger.providerUserId()).isEqualTo("12345");
            assertThat(asLong.providerUserId()).isEqualTo("12345");
        }

        @Test
        @DisplayName("닉네임이 없으면 파싱 지점에서 막는다 — NOT NULL 위반으로 늦게 터지지 않게")
        void rejectsMissingNickname() {
            Map<String, Object> withoutNickname = Map.of(
                    "id", 3_812_345_678L,
                    "kakao_account", Map.of("profile", Map.of()));

            assertThatThrownBy(() -> OAuth2UserInfo.of("kakao", withoutNickname))
                    .isInstanceOf(OAuth2AuthenticationException.class)
                    .hasMessageContaining("nickname");
        }

        @Test
        @DisplayName("kakao_account 자체가 없으면 거절한다")
        void rejectsMissingAccount() {
            assertThatThrownBy(() -> OAuth2UserInfo.of("kakao", Map.of("id", 1L)))
                    .isInstanceOf(OAuth2AuthenticationException.class)
                    .hasMessageContaining("kakao_account");
        }

        private Map<String, Object> kakaoResponse(Object id, String nickname) {
            return Map.of(
                    "id", id,
                    "kakao_account", Map.of("profile", Map.of("nickname", nickname)));
        }
    }

    @Nested
    @DisplayName("구글")
    class Google {

        @Test
        @DisplayName("sub 와 name 을 뽑는다 — sub 는 이미 문자열이다")
        void extractsSubAndName() {
            OAuth2UserInfo info = OAuth2UserInfo.of("google",
                    Map.of("sub", "104social", "name", "홍길동"));

            assertThat(info.provider()).isEqualTo(Provider.GOOGLE);
            assertThat(info.providerUserId()).isEqualTo("104social");
            assertThat(info.nickname()).isEqualTo("홍길동");
        }
    }
}
