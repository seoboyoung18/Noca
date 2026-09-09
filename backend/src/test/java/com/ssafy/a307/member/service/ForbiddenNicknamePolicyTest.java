package com.ssafy.a307.member.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 금칙어 판정.
 * <p>
 * 목록은 설정에서 오므로 여기서는 <b>판정 규칙</b>만 본다 — 정규화가 우회를 얼마나 막는지,
 * 그리고 정상 닉네임을 잘못 막지 않는지. 실제 운영 목록은 클래스패스의
 * {@code forbidden-nicknames.txt} 다.
 */
@DisplayName("닉네임 금칙어")
class ForbiddenNicknamePolicyTest {

    private final ForbiddenNicknamePolicy policy = new ForbiddenNicknamePolicy(
            List.of("시발", "병신", "admin", "관리자", "바른견적"));

    @Test
    @DisplayName("금칙어가 그대로 들어 있으면 막는다")
    void blocksExactWord() {
        assertThat(policy.isForbidden("시발")).isTrue();
    }

    @Test
    @DisplayName("금칙어가 일부로 섞여 있어도 막는다 — 완전 일치만 보면 뚫린다")
    void blocksSubstring() {
        assertThat(policy.isForbidden("시발123")).isTrue();
        assertThat(policy.isForbidden("나는병신아님")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"시 발", "시.발", "시_발", "시-발", "시  발"})
    @DisplayName("공백·특수문자로 끊어도 막는다")
    void blocksSeparatedWord(String nickname) {
        assertThat(policy.isForbidden(nickname)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "Admin", "a d m i n", "A.D.M.I.N"})
    @DisplayName("영문은 대소문자·구분자를 무시하고 막는다")
    void blocksCaseInsensitive(String nickname) {
        assertThat(policy.isForbidden(nickname)).isTrue();
    }

    /**
     * 사칭은 불쾌함을 넘어 실제 피해가 된다 — 견적 서비스에서 "바른견적 운영팀"을
     * 사칭하면 사용자가 속을 수 있다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"바른견적운영팀", "관리자김철수", "바른견적 고객지원"})
    @DisplayName("사칭 방지어도 막는다")
    void blocksImpersonation(String nickname) {
        assertThat(policy.isForbidden(nickname)).isTrue();
    }

    /**
     * <b>초성 매칭을 넣지 않은 이유가 이것이다.</b> {@code ㅅㅂ} 를 막으면
     * "수박·소방·신발·서비스"를 쓰려던 사람이 이유도 모르고 막힌다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ㅅㅂ", "수박", "소방관", "신발장수", "서비스"})
    @DisplayName("초성 우회는 잡지 않는다 — 정상 단어와 구별할 수 없다")
    void doesNotBlockInitialConsonants(String nickname) {
        assertThat(policy.isForbidden(nickname)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"서보영", "홍길동", "자동차왕", "사용자7f3a", "김정비"})
    @DisplayName("평범한 닉네임은 막지 않는다")
    void allowsOrdinaryNicknames(String nickname) {
        assertThat(policy.isForbidden(nickname)).isFalse();
    }

    @Test
    @DisplayName("목록이 비어 있으면 아무것도 막지 않는다")
    void allowsEverythingWhenListEmpty() {
        ForbiddenNicknamePolicy empty = new ForbiddenNicknamePolicy(List.<String>of());

        assertThat(empty.isForbidden("시발")).isFalse();
    }

    @Test
    @DisplayName("이모지만 있는 값은 정규화 후 비어 판정 대상이 아니다")
    void ignoresEmojiOnly() {
        assertThat(policy.isForbidden("🚗🚙")).isFalse();
    }
}
