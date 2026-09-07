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

        /**
         * 닉네임이 없다고 로그인을 막지 않는다. 최종 닉네임은 가입 화면에서 사용자가 입력하고,
         * 소셜 값은 초기값일 뿐이다. 없으면 {@code NicknamePolicy} 가 자동 생성값을 채운다.
         */
        @Test
        @DisplayName("닉네임이 없어도 로그인을 막지 않는다 — nickname 만 null 이다")
        void missingNicknameIsTolerated() {
            Map<String, Object> withoutNickname = Map.of(
                    "id", 3_812_345_678L,
                    "kakao_account", Map.of("profile", Map.of()));

            OAuth2UserInfo info = OAuth2UserInfo.of("kakao", withoutNickname);

            assertThat(info.providerUserId()).isEqualTo("3812345678");
            assertThat(info.nickname()).isNull();
        }

        /** 동의 항목에서 닉네임이 빠지면 kakao_account 나 profile 자체가 오지 않는다. */
        @Test
        @DisplayName("kakao_account 나 profile 이 통째로 없어도 회원번호만 있으면 된다")
        void missingAccountOrProfileIsTolerated() {
            assertThat(OAuth2UserInfo.of("kakao", Map.of("id", 1L)).nickname()).isNull();
            assertThat(OAuth2UserInfo.of("kakao", Map.of("id", 1L, "kakao_account", Map.of()))
                    .nickname()).isNull();
        }

        @Test
        @DisplayName("회원번호가 없으면 거절한다 — 이것 없이는 회원을 특정할 수 없다")
        void rejectsMissingId() {
            assertThatThrownBy(() -> OAuth2UserInfo.of("kakao",
                    Map.of("kakao_account", Map.of("profile", Map.of("nickname", "홍길동")))))
                    .isInstanceOf(OAuth2AuthenticationException.class)
                    .hasMessageContaining("id");
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

        /** scope 에서 {@code profile} 이 빠지면 {@code name} 이 오지 않는다. 카카오와 같게 처리한다. */
        @Test
        @DisplayName("name 이 없어도 로그인을 막지 않는다")
        void missingNameIsTolerated() {
            OAuth2UserInfo info = OAuth2UserInfo.of("google", Map.of("sub", "104social"));

            assertThat(info.providerUserId()).isEqualTo("104social");
            assertThat(info.nickname()).isNull();
        }

        @Test
        @DisplayName("sub 이 없으면 거절한다")
        void rejectsMissingSub() {
            assertThatThrownBy(() -> OAuth2UserInfo.of("google", Map.of("name", "홍길동")))
                    .isInstanceOf(OAuth2AuthenticationException.class)
                    .hasMessageContaining("sub");
        }

        /** 구글 {@code sub} 는 21자리 숫자 문자열이다. VARCHAR(255) 에 들어간다. */
        @Test
        @DisplayName("실제 형식의 sub 를 그대로 보존한다")
        void keepsRealisticSubAsIs() {
            OAuth2UserInfo info = OAuth2UserInfo.of("google",
                    Map.of("sub", "104729384756102938475", "name", "홍길동"));

            assertThat(info.providerUserId()).isEqualTo("104729384756102938475");
        }
    }
}
