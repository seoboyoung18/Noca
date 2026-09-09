package com.ssafy.a307.member.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 닉네임 정책 — 가입 화면 초기값 생성과, 가입·수정이 공유하는 길이 판정.
 * <p>
 * 초기값은 제안일 뿐이라 사용자가 지우고 다시 쓸 수 있지만, 제안한 값이 곧바로
 * 검증에 걸리면 안 되므로 초기값도 판정 규칙을 만족해야 한다.
 */
@DisplayName("닉네임 정책")
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

    /**
     * 한 글자 소셜 닉네임은 실제로 온다. 그대로 내려보내면 프론트가 채운 값을 그대로
     * 제출했을 때 하한에 걸려, 사용자가 이유도 모른 채 가입 버튼에서 막힌다.
     */
    @Test
    @DisplayName("소셜 닉네임이 2자 미만이면 자동 생성값으로 대체한다")
    void generatesWhenSocialNicknameTooShort() {
        assertThat(NicknamePolicy.initialFrom("영")).matches("사용자[0-9a-f]{4}");
    }

    @Test
    @DisplayName("초기값은 언제나 저장 가능한 값이다")
    void initialValueIsAlwaysValid() {
        for (String social : new String[]{null, "", "  ", "영", "홍길동", "가나다라마바사아자차카타파하"}) {
            assertThat(NicknamePolicy.isValid(NicknamePolicy.initialFrom(social)))
                    .as("초기값 '%s'", social)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("normalize 는 공백을 지운 뒤 길이를 판정한다")
    void normalizeStripsThenValidates() {
        assertThat(NicknamePolicy.normalize("  서보영  ")).isEqualTo("서보영");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "영", "  영  ", "가나다라마바사아자차카타파하"})
    @DisplayName("normalize 는 규칙을 벗어난 값을 거부한다")
    void normalizeRejectsOutOfRange(String invalid) {
        assertThatThrownBy(() -> NicknamePolicy.normalize(invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
