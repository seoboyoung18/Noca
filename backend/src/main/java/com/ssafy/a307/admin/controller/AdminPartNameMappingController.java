package com.ssafy.a307.admin.controller;

import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.PartNameMappingAdminResponse;
import com.ssafy.a307.admin.dto.PartNameMappingCreateRequest;
import com.ssafy.a307.admin.dto.PartNameMappingUpdateRequest;
import com.ssafy.a307.admin.service.AdminPartNameMappingService;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 한글 원문 ↔ 부품 코드 별칭 관리. 마스터 코드가 아니라 별칭이라 삭제할 수 있다.
 *
 * <p><b>{@code /api/admin/**} 은 {@code SecurityConfig} 가 {@code hasRole("ADMIN")} 으로 막는다.</b>
 * 비로그인은 401, 일반 {@code USER} 는 공통 오류 봉투의 403 이다.
 *
 * <p><b>관리자 ID 를 요청으로 받지 않는다.</b> 어떤 DTO 에도 actor 필드가 없고, 감사 로그의
 * 행위자는 {@code CurrentMemberProvider} 가 세션에서 꺼낸다.
 *
 * <h2>⚠️ {@code rawName} 은 경로 변수가 아니라 쿼리 파라미터다 — CSV 명세와 다르다</h2>
 * 기존 명세는 {@code PATCH /api/admin/part-name-mappings/{rawName}} 이었다. 실제 시드
 * 15,308건을 전수 확인한 결과 그 계약으로는 <b>다룰 수 없는 행이 있다.</b>
 *
 * <table border="1">
 *   <caption>{@code raw_name} 에 들어 있는 경로 예약 문자 (시드 전수 조사)</caption>
 *   <tr><th>문자</th><th>건수</th><th>경로 변수로 가능한가</th></tr>
 *   <tr><td>{@code /}</td><td>346</td>
 *       <td><b>불가.</b> 경로 구분자다. {@code %2F} 로 인코딩해도 스프링 시큐리티의
 *           {@code StrictHttpFirewall} 이 기본값으로 거부한다. 통과시키려면 방화벽을
 *           풀어야 하는데, 별칭 하나 고치자고 전역 보안 설정을 낮출 수는 없다</td></tr>
 *   <tr><td>{@code %}</td><td>360</td>
 *       <td>조건부. {@code %25} 로 인코딩하면 되지만, FE 가 한 번이라도 빠뜨리면
 *           {@code %50} 같은 조각이 잘못된 이스케이프로 해석돼 400 이 된다</td></tr>
 *   <tr><td>{@code #}</td><td>7</td><td>조건부. 인코딩하지 않으면 fragment 로 잘려 서버까지 오지 않는다</td></tr>
 *   <tr><td>{@code ?}</td><td>0</td><td>해당 없음</td></tr>
 * </table>
 *
 * <p>그래서 <b>수정·삭제는 {@code ?rawName=} 쿼리 파라미터로 대상을 지정한다.</b> 쿼리
 * 문자열에서는 {@code /} 와 {@code #} 가 평범한 문자이고, {@code %} 만 퍼센트 인코딩하면 된다.
 *
 * <p><b>다만 쿼리로 옮기면 새로 위험해지는 문자가 둘 있다</b> — 경로에서는 평범했던
 * {@code &}(시드 61건)와 {@code +}(38건)다. {@code &} 는 파라미터 구분자라 인코딩하지 않으면
 * 값이 잘리고, {@code +} 는 쿼리 문자열에서 공백으로 디코딩돼 <b>다른 원문이 조회된다.</b>
 * 숨기지 않고 여기 적어 두는 이유는 이것이 계약 변경의 대가이기 때문이다.
 *
 * <p><b>FE 가 해야 할 일은 하나다</b> — {@code encodeURIComponent(rawName)}. 이 함수는
 * {@code / % # & +} 를 모두 이스케이프하므로 위 다섯 문자를 한 번에 처리한다. 반대로
 * 직접 문자열을 이어 붙이면 위 다섯 가지가 각각 다른 방식으로 조용히 깨진다.
 *
 * <pre>
 * PATCH  /api/admin/part-name-mappings?rawName=%EB%92%A4%EB%B2%94%ED%8D%BC2%2F1%ED%83%88%EC%B0%A9
 * DELETE /api/admin/part-name-mappings?rawName=...&amp;changeReason=...
 * </pre>
 *
 * <p><b>대안을 쓰지 않은 이유.</b> 대리 키({@code mappingId})를 새로 만드는 편이 더 깔끔하지만
 * {@code raw_name} 이 PK 인 테이블의 스키마와 15,308건 시드를 함께 바꿔야 하고, 이 작업의
 * 범위를 넘는다. 쿼리 파라미터는 스키마를 건드리지 않고 전체 데이터가 동작하게 만든다.
 */
@RestController
@RequestMapping("/api/admin/part-name-mappings")
@RequiredArgsConstructor
public class AdminPartNameMappingController {

    private final AdminPartNameMappingService service;

    /**
     * 목록. 시드만 1만 5천 건이라 <b>페이지네이션이 필수</b>다.
     *
     * <p>여기 보이는 것은 seed 계약상 {@code MAPPED}·{@code MAPPED_EXTENDED} 에서 나온
     * <b>확정 매핑뿐</b>이다. 미확정 5종은 DB 에 행이 없으므로 이 목록에 나타나지 않고,
     * 이 API 가 그 상태를 만들어 내지도 않는다.
     *
     * @param partActive 대상 부품의 활성 상태로 거른다. {@code false} 로 주면 "왜 사전에서
     *                   빠졌는지" 를 찾는 화면이 된다
     * @param scope      {@code AI_LABEL} 이면 AI 핵심 32종 대상 매핑만, {@code EXTENDED} 면 확장 코드
     *                   대상만. 생략하면 둘 다. 다른 값은 400
     */
    @GetMapping
    public ApiResponse<AdminPageResponse<PartNameMappingAdminResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String partCode,
            @RequestParam(required = false) Boolean partActive,
            @RequestParam(required = false) PartCodeScope scope,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {

        return ApiResponse.of(service.search(keyword, partCode, partActive, scope, page, size, sort));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PartNameMappingAdminResponse>> create(
            @Valid @RequestBody PartNameMappingCreateRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.create(request)));
    }

    /**
     * 대상 부품만 바꾼다. 원문을 바꾸려면 삭제 후 재등록이다.
     *
     * @param rawName 고칠 행의 원문. 경로가 아니라 쿼리 파라미터인 이유는 클래스 Javadoc 에 있다
     */
    @PatchMapping
    public ApiResponse<PartNameMappingAdminResponse> update(
            @RequestParam String rawName, @Valid @RequestBody PartNameMappingUpdateRequest request) {

        return ApiResponse.of(service.update(rawName, request));
    }

    /**
     * @param changeReason 삭제에는 본문이 없어 사유도 쿼리 파라미터로 받는다. 등록·수정의
     *                     본문 필드와 <b>같은 규칙</b>으로 공백·길이를 검증한다
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestParam String rawName, @RequestParam String changeReason) {
        service.delete(rawName, changeReason);
    }
}
