package com.ssafy.a307.member.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가입 화면 닉네임 초기값. 최종 닉네임은 사용자가 입력하므로 여기서 만드는 값은 제안일 뿐이다.
 */
@DisplayName("닉네임 초기값 정책")
class NicknamePolicyTest {

    @Test
    @DisplayName("소셜 닉네임이 12자 이하면 그대로 쓴다")
    void keepsShortSocialNickname() {
        assertThat(NicknamePolicy.initialFrom("홍길동")).isEqualTo("홍길동");
    }

    /**
     * {@code member.nickname} 이 VARCHAR(12) 다. 넘는 값을 그대로 내려보내면 프론트가
     * 채운 값을 그대로 제출했을 때 검증에 걸린다.
     */
    @Test
    @DisplayName("12자를 넘으면 잘라서 바로 제출 가능한 값으로 만든다")
    void truncatesToColumnLength() {
        String tooLong = "가나다라마바사아자차카타파하";

        String initial = NicknamePolicy.initialFrom(tooLong);

        assertThat(initial).hasSize(NicknamePolicy.MAX_LENGTH);
        assertThat(tooLong).startsWith(initial);
    }

    @Test
    @DisplayName("앞뒤 공백은 제거한다")
    void stripsSurroundingWhitespace() {
        assertThat(NicknamePolicy.initialFrom("  홍길동  ")).isEqualTo("홍길동");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("닉네임이 없으면 자동 생성한다 — 사용자XXXX 형태")
    void generatesWhenMissing(String missing) {
        String generated = NicknamePolicy.initialFrom(missing);

        assertThat(generated).matches("사용자[0-9a-f]{4}");
        assertThat(generated.length()).isLessThanOrEqualTo(NicknamePolicy.MAX_LENGTH);
    }

    /** 닉네임에는 유니크 제약이 없어 겹쳐도 되지만, 매번 같은 값이 나오면 초기값 구실을 못 한다. */
    @Test
    @DisplayName("자동 생성값은 매번 같지 않다")
    void generatedValuesVary() {
        long distinct = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> NicknamePolicy.initialFrom(null))
                .distinct()
                .count();

        assertThat(distinct).isGreaterThan(1);
    }
}
