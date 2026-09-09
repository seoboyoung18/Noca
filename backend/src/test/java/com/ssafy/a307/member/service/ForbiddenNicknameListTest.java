package com.ssafy.a307.member.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>실제 운영 목록</b>({@code forbidden-nicknames.txt})으로 판정한다.
 * 규칙 자체는 {@link ForbiddenNicknamePolicyTest} 가 보고, 여기서는 목록의 내용을 본다.
 *
 * <p>목록에 단어를 추가할 때 정상 닉네임이 함께 막히는 걸 잡기 위한 테스트다.
 * 부분 문자열로 걸러내므로 두 글자짜리 단어는 우연히 겹치기 쉽다.
 */
@DisplayName("금칙어 운영 목록")
class ForbiddenNicknameListTest {

    private final ForbiddenNicknamePolicy policy =
            new ForbiddenNicknamePolicy("forbidden-nicknames.txt");

    @ParameterizedTest
    @ValueSource(strings = {"시발", "씨발놈", "병신같은", "지랄맞네", "미친놈아"})
    @DisplayName("욕설은 막는다")
    void blocksProfanity(String nickname) {
        assertThat(policy.isForbidden(nickname)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"관리자", "운영자김", "admin", "바른견적팀", "고객센터"})
    @DisplayName("사칭 방지어는 막는다")
    void blocksImpersonation(String nickname) {
        assertThat(policy.isForbidden(nickname)).isTrue();
    }

    /**
     * 넷 다 예전에 목록에 있다가 뺀 단어에 걸리던 값들이다.
     * 목록에 {@code 자지}·{@code 보지}·{@code 새끼}·{@code 개새} 를 다시 넣으면 여기서 깨진다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"나혼자지내", "정보지킴이", "새끼고양이", "무지개새벽"})
    @DisplayName("정상 닉네임을 막지 않는다 — 뺀 단어를 되돌리면 여기서 깨진다")
    void doesNotBlockFalsePositives(String nickname) {
        assertThat(policy.isForbidden(nickname))
                .as("'%s' 는 정상 닉네임이다", nickname)
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"서보영", "홍길동", "자동차왕", "김정비", "차량관리왕"})
    @DisplayName("평범한 닉네임을 막지 않는다")
    void allowsOrdinaryNicknames(String nickname) {
        assertThat(policy.isForbidden(nickname)).isFalse();
    }
}
