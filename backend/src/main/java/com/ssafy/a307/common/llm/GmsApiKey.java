package com.ssafy.a307.common.llm;

/**
 * GMS Key 를 담는 유일한 타입.
 *
 * <p><b>왜 {@link GmsProperties} 에 넣지 않았는가</b> — record 의 기본 {@code toString} 은 모든
 * 구성요소를 찍는다. 설정 객체는 바인딩 실패 메시지·기동 로그·예외 스택에 통째로 실리기 쉬운
 * 물건이라, 키를 그 안에 두면 <b>로그 한 줄로 새어 나간다.</b> GMS 공식 문서도 키를 클라이언트
 * 코드에 하드코딩하지 말고 서버 환경변수나 KMS 에서 불러 쓰라고 못 박는다.
 *
 * <p>그래서 이 클래스는 세 가지를 지킨다.
 * <ul>
 *   <li>{@link #toString()} 이 값을 내지 않는다. {@code GmsApiKey[present=true]} 만 남는다</li>
 *   <li>{@link #equals}·{@link #hashCode} 를 만들지 않는다 — 값 비교가 필요할 일이 없고,
 *       비교 실패 메시지에 값이 실리는 경로를 애초에 만들지 않는다</li>
 *   <li>값을 꺼내는 통로가 {@link #value()} 하나뿐이고, 그것을 부르는 곳은 인증 헤더를 만드는
 *       지점 <b>하나</b>여야 한다</li>
 * </ul>
 *
 * <p><b>키가 없어도 이 객체는 만들어진다.</b> 애플리케이션은 키 없이 정상 기동해야 하고
 * (차량·사고 API 까지 같이 죽으면 안 된다), 키가 없을 때 LLM 계층 빈이 뜨지 않게 막는 것은
 * {@link GmsKeyPresentCondition} 의 몫이다.
 */
public final class GmsApiKey {

    private final String value;

    public GmsApiKey(String value) {
        this.value = value == null ? "" : value.strip();
    }

    public boolean isPresent() {
        return !value.isEmpty();
    }

    /**
     * 인증 헤더를 만드는 지점에서만 부른다.
     *
     * @throws IllegalStateException 키가 없을 때. 빈 문자열을 헤더에 실어 401 을 받느니
     *                               부르는 쪽 실수를 즉시 드러내는 편이 낫다
     */
    public String value() {
        if (!isPresent()) {
            throw new IllegalStateException("GMS 키가 설정되지 않았다");
        }
        return value;
    }

    /** <b>값을 절대 내지 않는다.</b> 이 클래스가 존재하는 이유의 절반이 이 메서드다. */
    @Override
    public String toString() {
        return "GmsApiKey[present=" + isPresent() + "]";
    }
}
