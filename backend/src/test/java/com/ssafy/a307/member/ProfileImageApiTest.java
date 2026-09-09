package com.ssafy.a307.member;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.member.image.ProfileImageKeys;
import com.ssafy.a307.member.image.ProfileImageNotUploadedException;
import com.ssafy.a307.member.image.ProfileImageStoragePort;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로필 이미지 등록·변경·삭제.
 * <p>
 * S3 를 부르지 않는다. 저장소를 인메모리 가짜로 바꿔 끼워 <b>서버가 책임지는 부분</b>만 본다 —
 * 업로드 키 소유 검사, 실제 바이트의 형식 판정, 용량 상한, 삭제 멱등성. presigned URL 서명
 * 자체는 AWS SDK 의 몫이라 여기서 검증할 것이 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(ProfileImageApiTest.FakeStorageConfig.class)
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("프로필 이미지")
class ProfileImageApiTest {

    private static final String KAKAO_ID = "3877665544";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private ProfileImageStoragePort storagePort;

    private FakeProfileImageStorage storage;

    @BeforeEach
    void resetStorage() {
        storage = (FakeProfileImageStorage) storagePort;
        storage.clear();
    }

    @Test
    @DisplayName("업로드 URL 을 발급하면 키와 필수 헤더가 함께 온다")
    void issuesUploadUrl() throws Exception {
        MockHttpSession session = signedInSession();
        long memberId = currentMemberId();

        mockMvc.perform(post("/api/members/me/profile-image/upload-url")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"image/jpeg","size":102400}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadKey").value(startsWith("profile/" + memberId + "/")))
                .andExpect(jsonPath("$.data.url").exists())
                // 서명에 포함된 값이라 브라우저가 그대로 실어야 한다
                .andExpect(jsonPath("$.data.requiredHeaders['Content-Type']").value("image/jpeg"));
    }

    @Test
    @DisplayName("지원하지 않는 형식은 URL 을 내주지 않는다 — 다 올린 뒤 거절하면 낭비다")
    void rejectsUnsupportedContentType() throws Exception {
        mockMvc.perform(post("/api/members/me/profile-image/upload-url")
                        .session(signedInSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"image/gif","size":1024}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("선언한 크기가 상한을 넘으면 URL 을 내주지 않는다")
    void rejectsTooLargeDeclaredSize() throws Exception {
        mockMvc.perform(post("/api/members/me/profile-image/upload-url")
                        .session(signedInSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"image/jpeg","size":5242881}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("업로드를 마치면 프로필에 이미지 URL 이 채워진다")
    void completesUpload() throws Exception {
        MockHttpSession session = signedInSession();
        long memberId = currentMemberId();
        String uploadKey = ProfileImageKeys.newStagingKey(memberId);
        storage.putStaging(uploadKey, jpegBytes());

        mockMvc.perform(put("/api/members/me/profile-image")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody(uploadKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").exists());

        assertThat(storage.service).containsKey("profile/" + memberId);
        // 옮긴 뒤 staging 은 비운다 — 같은 파일이 두 버킷에 남을 이유가 없다
        assertThat(storage.staging).doesNotContainKey(uploadKey);
        assertThat(memberRepository.findById(memberId)).get()
                .extracting(m -> m.getProfileImageKey())
                .isEqualTo("profile/" + memberId);
    }

    /** 이 검사가 뚫리면 남의 업로드를 자기 프로필로 만들 수 있다. */
    @Test
    @DisplayName("남의 업로드 키를 보내면 거절한다")
    void rejectsOtherMembersUploadKey() throws Exception {
        MockHttpSession session = signedInSession();
        String othersKey = ProfileImageKeys.newStagingKey(currentMemberId() + 1);
        storage.putStaging(othersKey, jpegBytes());

        mockMvc.perform(put("/api/members/me/profile-image")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody(othersKey)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(storage.service).isEmpty();
    }

    /** presigned PUT 은 서버를 거치지 않으므로 선언한 Content-Type 을 믿을 수 없다. */
    @Test
    @DisplayName("이미지가 아닌 바이트가 올라와 있으면 거절한다")
    void rejectsNonImageBytes() throws Exception {
        MockHttpSession session = signedInSession();
        String uploadKey = ProfileImageKeys.newStagingKey(currentMemberId());
        storage.putStaging(uploadKey, "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(put("/api/members/me/profile-image")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody(uploadKey)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(storage.service).isEmpty();
    }

    @Test
    @DisplayName("실제로 올리지 않고 완료만 통보하면 400 이다")
    void rejectsCompleteWithoutUpload() throws Exception {
        MockHttpSession session = signedInSession();
        String uploadKey = ProfileImageKeys.newStagingKey(currentMemberId());

        mockMvc.perform(put("/api/members/me/profile-image")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody(uploadKey)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("다시 올리면 같은 키를 덮어쓴다 — 이전 파일이 남지 않는다")
    void replacesInPlace() throws Exception {
        MockHttpSession session = signedInSession();
        long memberId = currentMemberId();

        uploadProfileImage(session, memberId, jpegBytes());
        uploadProfileImage(session, memberId, pngBytes());

        assertThat(storage.service).hasSize(1);
        assertThat(storage.serviceContentTypes.get("profile/" + memberId)).isEqualTo("image/png");
    }

    @Test
    @DisplayName("삭제하면 이미지 URL 이 사라지고, 두 번 눌러도 성공한다")
    void deleteIsIdempotent() throws Exception {
        MockHttpSession session = signedInSession();
        long memberId = currentMemberId();
        uploadProfileImage(session, memberId, jpegBytes());

        mockMvc.perform(delete("/api/members/me/profile-image").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()));

        // 프론트가 버튼을 두 번 눌러도 안전해야 한다
        mockMvc.perform(delete("/api/members/me/profile-image").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()));

        assertThat(storage.service).isEmpty();
    }

    /**
     * DB 컬럼을 비우는 것만으로는 S3 에 파일이 남는다. 탈퇴는 개인정보를 즉시 지우는
     * 처리라, 파일까지 지워야 한다(S15P21A307-97).
     */
    @Test
    @DisplayName("탈퇴하면 S3 의 프로필 이미지 파일까지 지운다")
    void withdrawDeletesStoredImage() throws Exception {
        MockHttpSession session = signedInSession();
        long memberId = currentMemberId();
        uploadProfileImage(session, memberId, jpegBytes());
        assertThat(storage.service).containsKey("profile/" + memberId);

        mockMvc.perform(delete("/api/members/me").session(session))
                .andExpect(status().isNoContent());

        assertThat(storage.service).doesNotContainKey("profile/" + memberId);
        assertThat(memberRepository.findById(memberId)).get()
                .extracting(m -> m.getProfileImageKey())
                .isNull();
    }

    /**
     * 파일 삭제는 트랜잭션 밖 best-effort 다. 저장소가 터져도 탈퇴는 이미 커밋됐으므로
     * 실패로 되돌리면 안 된다 — 사용자 입장에서 탈퇴가 안 된 것처럼 보인다.
     */
    @Test
    @DisplayName("S3 삭제가 실패해도 탈퇴는 완료된다")
    void withdrawSucceedsWhenStorageFails() throws Exception {
        MockHttpSession session = signedInSession();
        long memberId = currentMemberId();
        uploadProfileImage(session, memberId, jpegBytes());
        storage.failDeletes = true;

        mockMvc.perform(delete("/api/members/me").session(session))
                .andExpect(status().isNoContent());

        assertThat(memberRepository.findById(memberId)).get()
                .extracting(m -> m.getStatus().name())
                .isEqualTo("WITHDRAWN");
    }

    @Test
    @DisplayName("이미지가 없으면 조회 응답의 URL 은 null 이다")
    void nullUrlWithoutImage() throws Exception {
        mockMvc.perform(get("/api/members/me").session(signedInSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()));
    }

    private void uploadProfileImage(MockHttpSession session, long memberId, byte[] content)
            throws Exception {
        String uploadKey = ProfileImageKeys.newStagingKey(memberId);
        storage.putStaging(uploadKey, content);

        mockMvc.perform(put("/api/members/me/profile-image")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody(uploadKey)))
                .andExpect(status().isOk());
    }

    private String completeBody(String uploadKey) {
        return """
                {"uploadKey":"%s"}
                """.formatted(uploadKey);
    }

    private static byte[] jpegBytes() {
        byte[] content = new byte[64];
        content[0] = (byte) 0xFF;
        content[1] = (byte) 0xD8;
        content[2] = (byte) 0xFF;
        return content;
    }

    private static byte[] pngBytes() {
        byte[] content = new byte[64];
        System.arraycopy(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A},
                0, content, 0, 8);
        return content;
    }

    private MockHttpSession signedInSession() throws Exception {
        UserPrincipal principal =
                UserPrincipal.ofPendingSignup(Provider.KAKAO, KAKAO_ID, "카카오닉네임");
        Authentication authentication = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), Provider.KAKAO.registrationId());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        mockMvc.perform(post("/api/auth/signup")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"보영","agreedTerms":["SERVICE","PRIVACY"]}
                                """))
                .andExpect(status().isCreated());

        return session;
    }

    private long currentMemberId() {
        return memberRepository.findByProviderAndProviderUserId(Provider.KAKAO, KAKAO_ID)
                .orElseThrow()
                .getMemberId();
    }

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        ProfileImageStoragePort fakeProfileImageStorage() {
            return new FakeProfileImageStorage();
        }
    }

    /**
     * S3 대신 맵에 담는다. 실제 어댑터가 지키는 계약 중 서비스 로직이 기대는 것만 흉내낸다 —
     * 없는 키를 읽으면 {@link ProfileImageNotUploadedException}, 같은 키에 쓰면 덮어쓰기.
     */
    static class FakeProfileImageStorage implements ProfileImageStoragePort {

        final Map<String, byte[]> staging = new HashMap<>();
        final Map<String, byte[]> service = new HashMap<>();
        final Map<String, String> serviceContentTypes = new HashMap<>();

        /** 삭제 실패를 흉내내기 위한 스위치. best-effort 경로를 보려면 필요하다. */
        boolean failDeletes;

        void clear() {
            staging.clear();
            service.clear();
            serviceContentTypes.clear();
            failDeletes = false;
        }

        void putStaging(String key, byte[] content) {
            staging.put(key, content);
        }

        @Override
        public PresignedUpload createStagingUploadUrl(String stagingKey, String contentType,
                                                      Duration validity) {
            return new PresignedUpload(
                    URI.create("https://fake-staging.example/" + stagingKey),
                    Instant.now().plus(validity),
                    Map.of("Content-Type", contentType));
        }

        @Override
        public StagedObject readStaging(String stagingKey) {
            byte[] content = staging.get(stagingKey);
            if (content == null) {
                throw new ProfileImageNotUploadedException(stagingKey, null);
            }
            return new StagedObject(content, "application/octet-stream");
        }

        @Override
        public void putService(String serviceKey, byte[] content, String contentType) {
            service.put(serviceKey, content);
            serviceContentTypes.put(serviceKey, contentType);
        }

        @Override
        public void deleteStaging(String stagingKey) {
            staging.remove(stagingKey);
        }

        @Override
        public void deleteService(String serviceKey) {
            if (failDeletes) {
                throw new IllegalStateException("저장소 삭제 실패 (테스트)");
            }
            service.remove(serviceKey);
            serviceContentTypes.remove(serviceKey);
        }

        @Override
        public URI createDownloadUrl(String serviceKey, Duration validity) {
            return URI.create("https://fake-service.example/" + serviceKey + "?sig=test");
        }
    }
}
