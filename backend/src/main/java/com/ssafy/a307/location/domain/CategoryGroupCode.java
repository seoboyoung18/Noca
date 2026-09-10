package com.ssafy.a307.location.domain;

import com.ssafy.a307.common.kakao.KakaoLocalPort;

/**
 * 카카오가 정의한 카테고리 그룹 코드 <b>18종.</b> 이 목록이 전부다.
 *
 * <h2>왜 enum 인가</h2>
 * 문자열을 그대로 카카오에 넘기면 오타가 <b>카카오의 {@code -2} 응답</b>으로 돌아온다.
 * 그것은 쿼터를 태우고, FE 에는 "위치 정보 조회 요청이 올바르지 않습니다" 라는 뭉뚱그린
 * 메시지만 남는다. enum 으로 받으면 스프링이 바인딩 단계에서 400 을 내고 어떤 값이 잘못됐는지
 * 알려 준다. 카카오를 부르기 전에 끝난다.
 *
 * <h2>⚠️ 정비소·카센터 코드가 없다</h2>
 * 18종을 훑어보면 자동차 정비와 관련된 그룹이 <b>하나도 없다.</b> 이 서비스가 "주변 정비소" 를
 * 찾아야 한다면 카테고리 검색이 아니라 <b>키워드 검색</b>({@code /api/locations/places?query=정비소})
 * 을 써야 한다. 없는 코드를 만들어 넣으면 카카오가 {@code -2} 로 거절한다.
 *
 * <p>{@code AG2}(중개업소)나 {@code OL7}(주유소·충전소)를 정비소 대용으로 쓰지 않는다 —
 * 다른 업종이고, 결과가 섞이면 사용자가 잘못된 곳으로 차를 가져간다.
 */
public enum CategoryGroupCode implements KakaoLocalPort.CategoryGroup {

    MT1("대형마트"),
    CS2("편의점"),
    PS3("어린이집·유치원"),
    SC4("학교"),
    AC5("학원"),
    PK6("주차장"),
    OL7("주유소·충전소"),
    SW8("지하철역"),
    BK9("은행"),
    CT1("문화시설"),
    AG2("중개업소"),
    PO3("공공기관"),
    AT4("관광명소"),
    AD5("숙박"),
    FD6("음식점"),
    CE7("카페"),
    HP8("병원"),
    PM9("약국");

    private final String displayName;

    CategoryGroupCode(String displayName) {
        this.displayName = displayName;
    }

    /** 카카오에 보내는 코드. enum 이름이 곧 코드다. */
    @Override
    public String code() {
        return name();
    }

    /** 화면에 띄울 한글 이름. FE 가 코드별 문구를 따로 관리하지 않게 서버가 함께 준다. */
    public String displayName() {
        return displayName;
    }
}
