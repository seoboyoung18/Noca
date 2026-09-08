package com.ssafy.a307.accident.dto;

/**
 * 파일별 업로드 상태(Task 143). <b>두 값뿐이다</b> — 요구사항 22행의 대기·진행·완료·실패
 * 네 상태를 그대로 담지 않는다.
 *
 * <p>이유는 저장 위치가 없기 때문이다(answer25 D2). 정본 스키마에 {@code upload_status} 류 컬럼이
 * 없고, Story 141 은 "추가 기능·중요도 중" 이라 MVP 필수가 아닌 이유로 스키마를 건드리지 않았다.
 * 그래서 <b>asset 존재로 상태를 유도</b>한다.
 *
 * <p>진행률과 진행·실패 구분은 BE 범위가 아니다. presigned 직접 업로드에서 바이트는
 * 브라우저 → S3 로 흐르므로 서버는 진행 중인 전송을 볼 수 없다. 진행률은 FE 가
 * {@code XMLHttpRequest.upload.onprogress} 로 계산하고, 실패도 그 PUT 의 결과로 FE 가 안다.
 * BE 가 답할 수 있는 것은 <b>어떤 파일이 아직 완료 통보를 받지 못했는지</b> 까지다.
 */
public enum ImageUploadState {

    /** URL 은 발급됐지만 완료 통보가 아직 없다. FE 관점의 대기·진행·실패가 모두 여기로 뭉친다. */
    PENDING,

    /** 완료 통보까지 끝나 asset 이 저장되어 있다. */
    COMPLETED
}
