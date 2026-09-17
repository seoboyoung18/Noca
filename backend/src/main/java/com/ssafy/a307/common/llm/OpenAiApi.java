package com.ssafy.a307.common.llm;

/** GMS OpenAI 프록시에 사용할 API 형식. */
public enum OpenAiApi {

    /** 파일(PDF 포함) 입력을 지원하는 기존 Responses API. */
    RESPONSES,

    /** GMS gpt-5.4 문서가 안내하는 Chat Completions API. */
    CHAT
}
