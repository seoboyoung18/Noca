package com.ssafy.a307.member.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * 닉네임 금칙어 판정. 욕설뿐 아니라 <b>사칭 방지어</b>({@code 관리자}·{@code 바른견적} 등)도
 * 함께 막는다 — 정비소 견적 서비스라 운영자를 사칭하면 불쾌함을 넘어 실제 피해가 난다.
 *
 * <p><b>정규화 후 부분 문자열로 본다.</b> 완전 일치는 {@code 시발123} 한 줄로 뚫리고,
 * 정규화를 안 하면 {@code 시 발} 이나 {@code 시.발} 로 뚫린다. 그래서 비교 전에
 * 문자·숫자가 아닌 것을 모두 지우고 소문자로 맞춘다.
 *
 * <p><b>초성 우회({@code ㅅㅂ})는 일부러 잡지 않는다.</b> {@code ㅅㅂ} 는 "수박·소방·신발·
 * 서비스"의 초성이기도 하다. 닉네임이 2~12자로 짧아 우연히 겹칠 확률이 높고, 우회를 100%
 * 막는 건 애초에 불가능하다. <b>정상 사용자를 막는 비용이 우회를 허용하는 비용보다 크다</b> —
 * 남은 몫은 신고로 사후 대응한다.
 *
 * <p><b>목록이 {@code application.properties} 가 아니라 별도 파일에 있는 이유</b> —
 * Spring Boot 는 {@code .properties} 를 ISO-8859-1 로 읽어({@code java.util.Properties}
 * 규약) 한글 값이 깨진다. 이 클래스가 UTF-8 로 직접 읽는다.
 */
@Component
public class ForbiddenNicknamePolicy {

    private final List<String> normalizedWords;

    /**
     * {@code @Autowired} 가 필요하다 — 생성자가 둘이라 이게 없으면 스프링이 어느 쪽을
     * 쓸지 못 정하고 기본 생성자를 찾다가 기동에 실패한다.
     */
    @Autowired
    public ForbiddenNicknamePolicy(
            @Value("${app.nickname.forbidden-words-file:forbidden-nicknames.txt}") String location) {
        this(readWords(location));
    }

    /** 테스트용. 목록을 직접 주입해 판정 규칙만 본다. */
    ForbiddenNicknamePolicy(List<String> words) {
        this.normalizedWords = words.stream()
                .map(ForbiddenNicknamePolicy::normalize)
                .filter(word -> !word.isEmpty())
                .distinct()
                .toList();
    }

    public boolean isForbidden(String nickname) {
        String normalized = normalize(nickname);
        if (normalized.isEmpty()) {
            return false;
        }
        return normalizedWords.stream().anyMatch(normalized::contains);
    }

    /**
     * 공백·특수문자·이모지를 지우고 소문자로 맞춘다.
     * <p>
     * {@code NFC} 를 먼저 거는 이유 — macOS 가 올려 보내는 한글은 자모가 분해된
     * {@code NFD} 형태라, 정규화하지 않으면 같은 글자가 다른 문자열로 보인다.
     */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /**
     * 목록 파일이 없으면 기동을 막는다. 조용히 빈 목록으로 뜨면 금칙어가 통째로
     * 꺼진 채 운영되고, 그 사실을 아무도 모른다.
     */
    private static List<String> readWords(String location) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(location).getInputStream(), StandardCharsets.UTF_8))) {

            return reader.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "닉네임 금칙어 목록을 읽지 못했습니다: " + location, e);
        }
    }
}
