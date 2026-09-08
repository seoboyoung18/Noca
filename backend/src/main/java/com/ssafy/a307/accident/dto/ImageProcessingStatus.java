package com.ssafy.a307.accident.dto;

/**
 * 완료 통보 결과의 이미지별 처리 상태. <b>부분 실패를 허용</b>하므로 요청 전체가 아니라
 * 이미지마다 이 값이 붙는다 — 20장 중 3장이 실패해도 나머지 17장은 남는다.
 */
public enum ImageProcessingStatus {

    /** 전처리·저장·품질 판정까지 끝났다. asset 3행(ORIGINAL·RESIZED·THUMBNAIL)이 있다. */
    COMPLETED,

    /** 이미 완료된 이미지를 다시 통보했다. 재처리하지 않았다. */
    ALREADY_COMPLETED,

    /**
     * 이 이미지만 실패했다. asset 은 하나도 저장되지 않았고(원자적 저장) 올라간 오브젝트는 정리했다.
     * 같은 imageId 로 다시 업로드·통보할 수 있다.
     */
    FAILED
}
