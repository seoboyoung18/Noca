package com.ssafy.a307.accident.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이미지 API 4종이 <b>인증 없이는 통과하지 않는다</b>는 것을 시큐리티 필터를 켠 채로 확인한다.
 * <p>
 * {@code ChecklistPublicAccessTest} 와 같은 방식이다 — {@code addFilters = false} 를 쓰지 않는다.
 * 필터를 빼면 "인증이 걸려 있다" 를 증명할 수 없다. Redis 세션 자동설정을 제외하는 이유도 같다:
 * 401 을 만드는 과정에서 시큐리티가 요청을 저장하려고 세션을 만들고, Redis 가 없으면 그때 죽는다.
 * <p>
 * 업로드 URL 발급은 남의 사고에 이미지를 붙일 수 있는 입구다. 여기가 열리면 소유자 검사 전체가
 * 무의미해지므로 네 엔드포인트를 모두 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("사고 이미지 API 인증 (시큐리티 필터 켬)")
class AccidentImageSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST .../images/upload-urls — 비로그인 요청은 401 이다")
    void issueUploadUrlsRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/accidents/1/images/upload-urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "files": [ { "originalFilename": "front.jpg",
                                  "contentType": "image/jpeg", "size": 1024 } ] }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST .../images — 비로그인 요청은 401 이다")
    void completeRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/accidents/1/images")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "images": [ { "imageId": 1 } ] }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET .../images — 비로그인 요청은 401 이다")
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/accidents/1/images"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE .../images/{imageId} — 비로그인 요청은 401 이다")
    void deleteRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/accidents/1/images/1"))
                .andExpect(status().isUnauthorized());
    }
}
