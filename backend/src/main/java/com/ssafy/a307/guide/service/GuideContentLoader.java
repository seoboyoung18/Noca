package com.ssafy.a307.guide.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * 가이드 문안 JSON 을 클래스패스에서 읽는다.
 *
 * <p>근거 테이블이 {@code —} 라 DB 를 쓰지 않는다. 문안은 기획이 고칠 수 있어야 하므로
 * Java 상수가 아니라 {@code resources/} 의 JSON 파일에 둔다 ({@code prompt11.md} 3장 (가)).
 *
 * <p>읽기는 <b>기동 시점 한 번</b>이다. 문안이 깨져 있으면 요청 때 500 을 내는 대신
 * 애플리케이션이 뜨지 않게 한다 — 정적 문안이라 요청마다 다시 읽을 이유가 없고,
 * 잘못된 사고 대응 안내가 조용히 나가는 것보다 기동 실패가 낫다.
 */
@Component
@RequiredArgsConstructor
public class GuideContentLoader {

    private final JsonMapper jsonMapper;

    public <T> T load(String classpathLocation, Class<T> type) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            return jsonMapper.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "가이드 문안을 읽지 못했다: " + classpathLocation, e);
        }
    }
}
