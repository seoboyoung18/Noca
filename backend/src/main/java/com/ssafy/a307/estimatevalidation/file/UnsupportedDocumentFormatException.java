package com.ssafy.a307.estimatevalidation.file;

/**
 * 고른 판독 공급자가 이 파일 형식을 받지 못한다.
 *
 * <p>별도 예외 타입인 이유는 <b>분류를 메시지나 에러 코드로 추측하지 않기 위해서다.</b>
 * 저장소 오류·판독 실패와 안내 문구가 달라야 하는데, {@code BusinessException} 하나로 묶으면
 * 워커가 문자열을 비교해 갈라야 한다. 타입으로 나누면 그럴 일이 없다.
 *
 * <p>지금 이 예외가 나는 경우는 하나다 — {@code provider=openai} 인데 PDF 가 들어왔을 때.
 * OpenAI Chat Completions 는 PDF 를 인라인으로 받지 못하고, 페이지를 이미지로 렌더하려면
 * PDF 라이브러리를 새로 넣어야 해서 이 작업의 범위 밖이다.
 */
public class UnsupportedDocumentFormatException extends RuntimeException {

    public UnsupportedDocumentFormatException(String message) {
        super(message);
    }
}
