package com.ssafy.a307.estimatevalidation.file.pdf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * PDF 에 임베딩할 한글 폰트를 준비한다.
 *
 * <p><b>함정 1 — Spring Boot 실행 JAR 안에서는 {@code File} 참조가 안 된다.</b>
 * 클래스패스 리소스가 중첩 JAR 경로({@code jar:file:...!/BOOT-INF/classes!/...})로 풀리므로
 * {@code new File(...)} 을 만들 수 없다. Open HTML to PDF 의 {@code useFont} 는 파일이나
 * {@code FSSupplier<InputStream>} 을 받는데, <b>스트림 공급자를 넘기면 매 렌더링마다
 * 6MB 를 다시 읽는다.</b> 그래서 <b>기동 시 1회</b> 임시 파일로 풀어 두고 그 파일을 재사용한다.
 *
 * <p><b>함정 2 — 폰트는 기본적으로 subset 된다.</b> 본문에 쓰인 글자만 임베딩되므로 파일이
 * 작아진다. 이 PDF 에는 폼 컨트롤이 없으므로 subset 을 유지한다 — 한글 폰트 전체를 넣으면
 * 문서마다 6MB 가 붙는다. 폼을 넣게 되면 {@code -fs-font-subset: complete-font} 가 필요하다.
 *
 * <p><b>폰트 파일이 없으면 예외를 던진다.</b> 조용히 넘어가면 한글이 전부 빈 네모로 나오는
 * PDF 가 사용자에게 간다 — 그건 생성 실패보다 나쁘다.
 */
@Slf4j
public final class KoreanPdfFont {

    /**
     * 임베딩할 폰트. <b>Noto Sans KR Regular</b> (SIL Open Font License 1.1).
     * OFL 은 임베딩과 재배포를 허용한다.
     */
    public static final String CLASSPATH_LOCATION = "fonts/NotoSansKR-Regular.ttf";

    /**
     * CSS 의 {@code font-family} 와 맞춰야 하는 이름.
     * 템플릿이 {@code font-family: 'korean', serif} 로 한글을 <b>앞에</b> 둔다 —
     * 폰트는 앞에서부터 찾으므로 영문 폰트를 먼저 두면 한글이 없을 때만 뒤로 넘어간다.
     */
    public static final String FAMILY = "korean";

    private KoreanPdfFont() {
    }

    /**
     * 클래스패스의 폰트를 임시 파일로 풀어 그 경로를 돌려준다. <b>기동 시 1회만 부른다.</b>
     *
     * @throws IllegalStateException 폰트가 클래스패스에 없을 때
     */
    public static Path extractToTempFile() {
        ClassPathResource resource = new ClassPathResource(CLASSPATH_LOCATION);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "한글 폰트를 찾지 못했다: " + CLASSPATH_LOCATION
                            + " — 폰트 없이 PDF 를 만들면 한글이 전부 빈 네모로 나온다");
        }
        try (InputStream in = resource.getInputStream()) {
            Path temp = Files.createTempFile("a307-korean-font-", ".ttf");
            temp.toFile().deleteOnExit();
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            log.info("한글 폰트를 임시 파일로 풀었다: {} ({} bytes)", temp, Files.size(temp));
            return temp;
        } catch (IOException e) {
            throw new UncheckedIOException("한글 폰트를 임시 파일로 풀지 못했다", e);
        }
    }
}
