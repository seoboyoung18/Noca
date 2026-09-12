package com.ssafy.a307.analysis.entity;

/**
 * 분석 단계. {@code ck_as_stage CHECK (stage IN ('PREPROCESS','DETECT','MATCH','ESTIMATE'))} 와
 * 같은 네 값이며, 상수명이 DB 값과 같아 컨버터가 필요 없다.
 *
 * <p><b>선언 순서가 곧 진행 순서다.</b> {@code analysis_stage} 에는 순서 컬럼이 없고
 * 정본 DDL 이 네 값을 이 순서로 적어 두었다. 단계 목록을 정렬하고 "3/4" 의 분모를 세는 것이
 * 모두 이 순서에 기댄다 — 값을 중간에 끼워 넣으면 화면의 단계 번호가 조용히 밀린다.
 *
 * <p><b>이 enum 이 단계 종류의 정본이다.</b> {@code analysis_stage} 행 수가 아니다. 파이프라인이
 * 아직 세 단계만 기록했더라도 화면이 보여야 할 분모는 4다 — 행이 없는 단계는 아직 시작하지
 * 않은 것이지 존재하지 않는 것이 아니다.
 *
 * <p>화면 문구("부품을 연결하고 있어요")를 여기 두지 않는다. 서버가 한글 라벨을 내려보내면
 * 문구를 바꿀 때마다 배포가 필요해진다 — {@code AccidentHistoryStatus} 와 같은 판단이다.
 */
public enum AnalysisStageType {

    /** 업로드 이미지 전처리. */
    PREPROCESS,

    /** 파손 검출. */
    DETECT,

    /** 검출 결과를 부품 코드·유사 사례에 연결. 화면의 4단계 중 3번째다. */
    MATCH,

    /** 비용 산정. */
    ESTIMATE;

    /** 단계 종류 수. 화면이 쓰는 "N/4" 의 분모다. */
    public static final int TOTAL = values().length;
}
