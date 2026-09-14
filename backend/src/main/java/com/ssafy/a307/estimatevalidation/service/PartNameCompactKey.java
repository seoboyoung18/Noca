package com.ssafy.a307.estimatevalidation.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <b>seed·파이프라인 비교 키.</b> {@code pipeline/jobs/estimates/map_estimate_labels.py} 의
 * {@code compact()} 와 <b>같은 결과를 내야 한다.</b>
 *
 * <pre>
 * def compact(value: str) -&gt; str:
 *     value = unicodedata.normalize("NFKC", value).lower()
 *     return re.sub(r"[\s_\-－·.,/]+", "", value)
 * </pre>
 *
 * <h2>{@link PartNameNormalizer} 와 무엇이 다른가</h2>
 *
 * <b>둘은 같은 규칙이 아니다.</b> 매핑 시드 15,308건에 돌리면 <b>79.4%(12,162건)가 서로 다른
 * 결과</b>를 낸다. 버그가 아니라 <b>책임이 다르기</b> 때문이다.
 *
 * <table border="1">
 *   <caption>두 키의 역할</caption>
 *   <tr><th>　</th><th>PartNameCompactKey</th><th>PartNameNormalizer</th></tr>
 *   <tr><td>쓰는 곳</td>
 *       <td>seed 생성·파이프라인 매핑 판정·<b>관리자 중복 검사</b></td>
 *       <td>런타임 사전 조회</td></tr>
 *   <tr><td>묻는 것</td>
 *       <td>"이 원문이 이미 등록돼 있는가" 를 파이프라인과 <b>같은 눈</b>으로 판단</td>
 *       <td>정비소가 실제로 적어 보낸 표기를 최대한 넓게 흡수</td></tr>
 *   <tr><td>괄호</td><td>남긴다</td><td>제거</td></tr>
 *   <tr><td>작업 접미사(교환·탈착·판금·도장·오버홀·수리)</td><td>남긴다</td><td>제거</td></tr>
 *   <tr><td>왼쪽→좌 / 오른쪽→우</td><td>치환하지 않는다</td><td>치환</td></tr>
 *   <tr><td>구분자 <code>_ - － · . , /</code></td><td>제거</td><td>남긴다</td></tr>
 * </table>
 *
 * <p><b>세 번째 정규화 규칙은 만들지 않았다.</b> 관리자 중복 검사에 런타임 키를 쓰면,
 * 파이프라인이 "서로 다른 원문" 으로 본 두 건을 관리자 화면은 "같다" 며 막게 된다.
 * 그러면 seed 재생성 결과와 관리자 입력이 허용하는 집합이 갈라진다.
 *
 * <p>Python 과의 동등성은 두 테스트가
 * {@code pipeline/standardization/fixtures/part_name_compact_fixture.tsv} 를 함께 읽어
 * 고정한다 — Java 쪽 {@code PartNameCompactKeyFixtureTest}, Python 쪽
 * {@code pipeline/standardization/test_part_name_compact.py}.
 */
@Component
public class PartNameCompactKey {

    /**
     * Python 의 <code>[\s_\-－·.,/]+</code> 와 같은 집합.
     *
     * <p>Java 의 <code>\s</code> 는 <code>[ \t\n\x0B\f\r]</code> 뿐이라 Python <code>\s</code>
     * (= <code>str.isspace()</code>) 보다 좁다. 차이를 메우려고 두 조각을 더 넣었다.
     * <ul>
     *   <li><code>\p{IsWhite_Space}</code> — U+0085, NBSP, U+2000~200A, U+3000 등</li>
     *   <li><code>\x1c-\x1f</code> — <code>\p{IsWhite_Space}</code> 에 없지만
     *       Python 이 공백으로 세는 구분 문자 4개</li>
     * </ul>
     * 이 둘을 합치면 Python 쪽 집합과 정확히 같아진다. NFKC 를 먼저 적용하므로
     * 전각 공백은 대부분 보통 공백으로 접혀 들어오지만, 접히지 않는 입력에서도 어긋나지 않게
     * 명시해 둔다.
     */
    private static final Pattern SEPARATORS =
            Pattern.compile("[\\s\\p{IsWhite_Space}\\x1c-\\x1f_\\-－·.,/]+");

    /** {@code null} 은 빈 키다 — 호출부마다 null 검사를 되풀이하지 않게 한다. */
    public String compact(String rawName) {
        if (rawName == null) return "";
        String value = Normalizer.normalize(rawName, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return SEPARATORS.matcher(value).replaceAll("");
    }
}
