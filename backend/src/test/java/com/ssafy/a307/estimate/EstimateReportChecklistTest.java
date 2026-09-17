package com.ssafy.a307.estimate;

import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.estimate.dto.EstimateBasisResponse;
import com.ssafy.a307.estimate.dto.EstimateReportResponse;
import com.ssafy.a307.estimate.dto.EstimateResponse;
import com.ssafy.a307.estimate.pdf.EstimatePdfProcessor;
import com.ssafy.a307.estimate.pdf.EstimatePdfRepository;
import com.ssafy.a307.estimate.pdf.EstimatePdfRepository.JobView;
import com.ssafy.a307.estimate.pdf.EstimatePdfStoragePort;
import com.ssafy.a307.estimate.pdf.EstimatePdfStoragePort.StoredPdf;
import com.ssafy.a307.estimate.repository.EstimateReportRepository;
import com.ssafy.a307.estimate.repository.EstimateReportRepository.ReportContextView;
import com.ssafy.a307.estimate.service.EstimateQueryService;
import com.ssafy.a307.estimate.service.EstimateReportService;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus;
import com.ssafy.a307.repairchecklist.service.RepairChecklistGenerator;
import com.ssafy.a307.repairchecklist.service.RepairChecklistStatusService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 리포트(화면)와 견적 PDF 가 체크리스트를 산정 근거로 싣되 <b>LLM 을 부르지 않는다</b>.
 *
 * <p>체크리스트 문안은 워커가 LLM 을 한 번 불러 저장해 둔 것이다. 리포트·PDF 는 그 행을 읽기만 해야
 * 전체 흐름의 LLM 호출이 1회로 끝난다. 여기서는 그것을 <b>호출 횟수와 구조</b> 두 가지로 고정한다.
 *
 * <ul>
 *   <li><b>호출 횟수</b> — 컨텍스트에 세는 {@link LlmChatPort} 대역을 올려 두고 리포트를 두 번 조립하고,
 *       PDF 를 실제로 한 장 만든다. 누가 이 경로에서 LLM 을 부르면 그 대역이 센다</li>
 *   <li><b>구조</b> — {@link EstimateReportService} · {@link EstimatePdfProcessor} 에서 생성자 주입을 따라가도
 *       {@link LlmChatPort} 에 닿지 않는다. 주입하지 않으면 부를 수 없다</li>
 * </ul>
 *
 * <p><b>체크리스트 쪽과 PDF 렌더링은 진짜로 돈다</b>({@link RepairChecklistStatusService} · 리포지토리 · H2 ·
 * {@code EstimatePdfGenerator}). 목으로 둔 것은 PostgreSQL 네이티브 쿼리인 리포트 문맥 조회
 * ({@link EstimateReportRepository}) · 견적 조회({@link EstimateQueryService}) · PDF 작업 행
 * ({@link EstimatePdfRepository})와, 버킷이 없는 테스트에서 뜰 수 없는 PDF 보관소({@link EstimatePdfStoragePort})다.
 * 앞의 셋은 {@code EstimateReportApiTest}·{@code EstimatePdfApiTest} 처럼 로컬 PostgreSQL 이 있어야 돌고,
 * 그 테스트들은 지금 건너뛰어진다. 이 테스트가 보려는 것은 견적이 아니라 체크리스트 연결이다.
 *
 * <p>{@code app.estimate-pdf.enabled=true} 로 PDF 처리기를 띄우되 {@code initial-delay} 를 1시간으로 밀어
 * 워커가 스스로 돌지 않게 한다. 처리는 테스트가 {@link EstimatePdfProcessor#process} 로 직접 부른다.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다. 시드는 높은 ID 대역으로 넣고 직접 지운다
 * ({@code RepairChecklistGenerationTest} 와 같은 방식).
 */
@SpringBootTest(properties = {
        "app.estimate-pdf.enabled=true",
        "app.estimate-pdf.poll-interval=PT1H",
        "app.estimate-pdf.initial-delay=PT1H",
        "app.estimate-pdf.batch-size=5",
        "app.estimate-pdf.processing-timeout=PT5M"
})
@Import(EstimateReportChecklistTest.CountingLlmConfig.class)
@DisplayName("리포트·견적 PDF — 체크리스트를 산정 근거로 싣고 LLM 은 부르지 않는다")
class EstimateReportChecklistTest {

    private static final long MEMBER_ID = 96_401L;
    private static final long MODEL_ID = 96_402L;
    private static final long VEHICLE_ID = 96_403L;
    private static final long ACCIDENT_ID = 96_404L;
    private static final long CHECKLIST_ID = 96_405L;
    private static final long ESTIMATE_ID = 96_406L;
    private static final long JOB_ID = 96_407L;
    private static final long REPORT_ID = 96_408L;
    private static final String REPORT_NO = "R-20260917-9640";
    private static final String STORAGE_KEY = "estimate-reports/R-20260917-9640.pdf";

    @TestConfiguration
    static class CountingLlmConfig {

        @Bean
        CountingLlmChatPort countingLlmChatPort() {
            return new CountingLlmChatPort();
        }
    }

    /** 불리면 센다. 응답은 체크리스트 스키마 모양이라, 누가 불러도 그 자리에서 터지지 않고 횟수만 남는다. */
    static class CountingLlmChatPort implements LlmChatPort {

        int calls;

        @Override
        public ChatResult complete(ChatRequest request) {
            calls++;
            return new ChatResult("{\"items\":[{\"content\":\"대역이 만든 문장\"}]}", "test-model", Usage.unknown());
        }
    }

    @MockitoBean private EstimateReportRepository reportRepository;
    @MockitoBean private EstimateQueryService estimateQueryService;
    @MockitoBean private EstimatePdfRepository pdfRepository;
    @MockitoBean private EstimatePdfStoragePort pdfStorage;

    @Autowired private EstimateReportService reportService;
    @Autowired private EstimatePdfProcessor pdfProcessor;
    @Autowired private CountingLlmChatPort llm;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanUp();
        llm.calls = 0;

        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','report-checklist','tester','USER','ACTIVE')", MEMBER_ID);
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(?,'현대','아반떼','SEDAN','Mid-size',true)", MODEL_ID);
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2024)",
                VEHICLE_ID, MEMBER_ID, MODEL_ID);
        jdbc.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type,
                    snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                    snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                values(?, ?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Mid-size', 2024)
                """, ACCIDENT_ID, VEHICLE_ID, MODEL_ID);

        // 목으로 둔 두 조회. 소유자 검사를 통과한 이 사고의 견적이라는 설정이다.
        ReportContextView context = mock(ReportContextView.class);
        given(context.getAccidentId()).willReturn(ACCIDENT_ID);
        given(context.getJobId()).willReturn(JOB_ID);
        given(context.getManufacturer()).willReturn("현대");
        given(context.getModelName()).willReturn("아반떼");
        given(context.getVehicleType()).willReturn("SEDAN");
        given(context.getCarClass()).willReturn("Mid-size");
        given(context.getModelYear()).willReturn((short) 2024);
        given(context.getAccidentCreatedAt()).willReturn(Instant.parse("2026-09-17T01:00:00Z"));
        given(reportRepository.findContext(ESTIMATE_ID, MEMBER_ID)).willReturn(Optional.of(context));
        given(reportRepository.findAnalyzedImages(JOB_ID)).willReturn(List.of());
        given(reportRepository.findLinkedValidationId(ACCIDENT_ID, ESTIMATE_ID)).willReturn(Optional.empty());

        Instant now = Instant.parse("2026-09-17T01:30:00Z");
        given(estimateQueryService.detail(ESTIMATE_ID, MEMBER_ID)).willReturn(new EstimateResponse(
                ESTIMATE_ID, JOB_ID, (short) 1, false, "참조 사례 부족", null, null,
                null, null, null, null, null, List.of(), List.of(), now));
        given(estimateQueryService.basis(ESTIMATE_ID, MEMBER_ID))
                .willReturn(new EstimateBasisResponse(ESTIMATE_ID, (short) 1, List.of()));

        // PDF 작업 행과 보관소. 선점된 이 견적의 PDF 한 건이라는 설정이다.
        JobView job = mock(JobView.class);
        given(job.getEstimateId()).willReturn(ESTIMATE_ID);
        given(job.getReportNo()).willReturn(REPORT_NO);
        given(pdfRepository.findJob(REPORT_ID)).willReturn(Optional.of(job));
        given(pdfRepository.findOwnerMemberId(ESTIMATE_ID)).willReturn(Optional.of(MEMBER_ID));
        given(pdfRepository.complete(REPORT_ID, STORAGE_KEY)).willReturn(1);
        given(pdfStorage.store(eq(REPORT_NO), any())).willReturn(new StoredPdf(STORAGE_KEY, 1));
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ------------------------------------------------------------------ LLM 0회

    @Test
    @DisplayName("완성된 체크리스트를 실은 리포트를 두 번 조립해도 LLM 은 한 번도 불리지 않는다")
    void reportNeverCallsLlmEvenWhenRefreshed() {
        insertChecklist("COMPLETED");
        insertItem(96_411L, "AI", null, "앞 범퍼 교환 대신 판금이 가능한지 확인", false, null, 1);

        EstimateReportResponse first = reportService.report(ESTIMATE_ID, MEMBER_ID);
        EstimateReportResponse second = reportService.report(ESTIMATE_ID, MEMBER_ID);

        assertThat(llm.calls).isZero();
        assertThat(first.checklist().items()).extracting(EstimateReportResponse.ChecklistItem::content)
                .containsExactly("앞 범퍼 교환 대신 판금이 가능한지 확인");
        assertThat(second.checklist()).isEqualTo(first.checklist());
    }

    /**
     * 체크리스트가 완성된 뒤 PDF 를 만들면 섹션이 실리고, 그 사이에도 LLM 은 0회다.
     *
     * <p>PDF 는 화면과 같은 {@link EstimateReportService#report} 를 부른다. 저장소에 넘어간 바이트를 실제로
     * 열어 문장이 들어갔는지 본다 — 렌더링이 조용히 섹션을 빠뜨리는 것을 잡으려면 파일을 봐야 한다.
     */
    @Test
    @DisplayName("PDF 를 만들어도 LLM 은 한 번도 불리지 않고, 완성된 체크리스트가 PDF 에 실린다")
    void pdfNeverCallsLlmAndPrintsChecklist() throws Exception {
        insertChecklist("COMPLETED");
        insertItem(96_411L, "AI", null, "앞 범퍼 교환 대신 판금이 가능한지 확인", false, null, 1);
        insertItem(96_412L, "USER", null, "보험사 담당자에게 사진 보내기", true, "내 메모", 2);

        pdfProcessor.process(REPORT_ID);

        assertThat(llm.calls).isZero();
        String text = storedPdfText();
        assertThat(text).contains(normalized("정비 체크리스트"))
                .contains(normalized("앞 범퍼 교환 대신 판금이 가능한지 확인"))
                .doesNotContain(normalized("보험사 담당자에게 사진 보내기"))
                .doesNotContain(normalized("내 메모"));
        verify(pdfRepository).complete(REPORT_ID, STORAGE_KEY);
    }

    /**
     * 구조로 막았는지. 리포트 서비스와 PDF 처리기에서 생성자 주입을 끝까지 따라가 LLM 포트에 닿지 않는지 본다.
     *
     * <p>걸어간 길이 비어 있으면 "없다" 가 아무 뜻이 없으므로, 실제로 체크리스트 조회까지 닿았는지도 함께 본다.
     */
    @Test
    @DisplayName("리포트 서비스·PDF 처리기의 주입 그래프 어디에도 LlmChatPort·체크리스트 생성기가 없다")
    void reportAndPdfPathsCannotReachLlm() {
        Set<Class<?>> fromReport = reachableByConstructorInjection(EstimateReportService.class);
        Set<Class<?>> fromPdf = reachableByConstructorInjection(EstimatePdfProcessor.class);

        assertThat(fromReport).contains(RepairChecklistStatusService.class);
        assertThat(fromPdf).contains(EstimateReportService.class, RepairChecklistStatusService.class);
        assertThat(fromReport).doesNotContain(LlmChatPort.class, RepairChecklistGenerator.class);
        assertThat(fromPdf).doesNotContain(LlmChatPort.class, RepairChecklistGenerator.class);
    }

    // ------------------------------------------------------------------ 무엇을 싣나

    @Test
    @DisplayName("AI 항목만 싣고 공통·사용자 항목과 체크 여부·메모는 싣지 않는다")
    void onlyAiItemsWithoutCheckOrMemo() {
        insertChecklist("COMPLETED");
        insertItem(96_411L, "AI", null, "도장 색상 맞춤 범위 확인", true, "정비소에서 괜찮다고 함", 1);
        insertItem(96_412L, "AI", null, "앞 범퍼 교환 대신 판금이 가능한지 확인", false, null, 2);
        insertItem(96_413L, "COMMON", "ESTIMATE_DOCUMENT", "견적서 서면 수령·항목별 금액 확인", true, null, 3);
        insertItem(96_414L, "USER", null, "보험사 담당자에게 사진 보내기", true, "내 메모", 4);

        EstimateReportResponse.Checklist checklist = reportService.report(ESTIMATE_ID, MEMBER_ID).checklist();

        assertThat(checklist.status()).isEqualTo(RepairChecklistStatus.COMPLETED);
        assertThat(checklist.items())
                .extracting(EstimateReportResponse.ChecklistItem::itemId, EstimateReportResponse.ChecklistItem::content)
                .containsExactly(
                        tuple(96_411L, "도장 색상 맞춤 범위 확인"),
                        tuple(96_412L, "앞 범퍼 교환 대신 판금이 가능한지 확인"));
        // 체크 여부·메모·출처는 담을 자리 자체가 없다.
        assertThat(Arrays.stream(EstimateReportResponse.ChecklistItem.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("itemId", "content");
    }

    // ------------------------------------------------------------------ 없거나 만드는 중이어도 리포트는 뜬다

    @Test
    @DisplayName("체크리스트를 요청한 적 없는 사고도 리포트가 뜨고, 체크리스트는 null 이 아닌 빈 상태다")
    void reportWithoutChecklistRow() {
        EstimateReportResponse report = reportService.report(ESTIMATE_ID, MEMBER_ID);

        assertThat(report.legalNotice()).isNotBlank();
        assertThat(report.checklist()).isEqualTo(EstimateReportResponse.Checklist.NOT_REQUESTED);
        assertThat(report.checklist().status()).isNull();
        assertThat(report.checklist().items()).isEmpty();
        assertThat(llm.calls).isZero();
    }

    /** 앞 시도가 남긴 AI 행이 있어도 완성 전에는 보이지 않는다 — 부분 결과가 최신처럼 읽히면 안 된다. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RepairChecklistStatus.class, names = {"QUEUED", "PROCESSING", "FAILED"})
    @DisplayName("체크리스트가 완성되지 않았으면 리포트는 뜨고 상태만 싣는다")
    void reportWhileChecklistNotCompleted(RepairChecklistStatus status) {
        insertChecklist(status.name());
        insertItem(96_411L, "AI", null, "앞 시도가 남긴 문장", false, null, 1);

        EstimateReportResponse report = reportService.report(ESTIMATE_ID, MEMBER_ID);

        assertThat(report.checklist().status()).isEqualTo(status);
        assertThat(report.checklist().items()).isEmpty();
        assertThat(llm.calls).isZero();
    }

    // ------------------------------------------------------------------ 체크리스트보다 PDF 가 먼저여도 PDF 는 만들어진다

    /**
     * 체크리스트를 요청한 적 없는 사고의 PDF. 두 워커는 서로를 기다리지 않으므로 이 경우가 흔하다.
     * PDF 처리기가 체크리스트 때문에 실패하면 건이 재시도 끝에 {@code FAILED} 로 굳는다.
     */
    @Test
    @DisplayName("체크리스트가 없어도 PDF 가 만들어져 저장·완료되고, 섹션은 없다")
    void pdfIsMadeWithoutChecklist() throws Exception {
        pdfProcessor.process(REPORT_ID);

        assertThat(llm.calls).isZero();
        verify(pdfRepository).complete(REPORT_ID, STORAGE_KEY);
        assertThat(storedPdfText()).contains(normalized("예상 견적 리포트"))
                .doesNotContain(normalized("정비 체크리스트"));
    }

    /** 생성 중에 PDF 를 만들면 그 PDF 에는 섹션이 없다 — 앞 시도가 남긴 문장이 새어 들어가지도 않는다. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RepairChecklistStatus.class, names = {"QUEUED", "PROCESSING", "FAILED"})
    @DisplayName("체크리스트가 완성되지 않았어도 PDF 는 만들어지고, 섹션도 앞 시도의 문장도 없다")
    void pdfIsMadeWhileChecklistNotCompleted(RepairChecklistStatus status) throws Exception {
        insertChecklist(status.name());
        insertItem(96_411L, "AI", null, "앞 시도가 남긴 문장", false, null, 1);

        pdfProcessor.process(REPORT_ID);

        assertThat(llm.calls).isZero();
        verify(pdfRepository).complete(REPORT_ID, STORAGE_KEY);
        assertThat(storedPdfText()).doesNotContain(normalized("정비 체크리스트"))
                .doesNotContain(normalized("앞 시도가 남긴 문장"));
    }

    // ------------------------------------------------------------------ helpers

    /** 보관소에 넘어간 PDF 바이트를 열어 텍스트를 뽑는다. 공백은 셀 폭에 따라 끊기므로 모두 지운다. */
    private String storedPdfText() throws Exception {
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(pdfStorage).store(eq(REPORT_NO), bytes.capture());
        try (PDDocument document = PDDocument.load(bytes.getValue())) {
            return normalized(new PDFTextStripper().getText(document));
        }
    }

    private static String normalized(String value) {
        return value.replaceAll("(?U)\\s+", "");
    }

    private void insertChecklist(String status) {
        boolean done = "COMPLETED".equals(status) || "FAILED".equals(status);
        jdbc.update("insert into repair_checklist(checklist_id,accident_id,status,generation_no,completed_at)"
                        + " values(?,?,?,1," + (done ? "current_timestamp" : "null") + ")",
                CHECKLIST_ID, ACCIDENT_ID, status);
    }

    private void insertItem(long itemId, String source, String commonCode, String content,
                            boolean checked, String memo, int order) {
        jdbc.update("insert into repair_checklist_item(item_id,checklist_id,source,common_code,content,"
                        + "is_checked,memo,display_order,checked_at) values(?,?,?,?,?,?,?,?,"
                        + (checked ? "current_timestamp" : "null") + ")",
                itemId, CHECKLIST_ID, source, commonCode, content, checked, memo, order);
    }

    private void cleanUp() {
        jdbc.update("delete from repair_checklist_item where checklist_id in"
                + " (select checklist_id from repair_checklist where accident_id = ?)", ACCIDENT_ID);
        jdbc.update("delete from repair_checklist where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from accident where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from vehicle where vehicle_id = ?", VEHICLE_ID);
        jdbc.update("delete from vehicle_model where model_id = ?", MODEL_ID);
        jdbc.update("delete from member where member_id = ?", MEMBER_ID);
    }

    /**
     * 생성자 주입을 따라간 타입 전부. {@code Optional<X>}·{@code List<X>} 같은 제네릭은 안쪽 타입도 본다.
     * 이 프로젝트 클래스만 더 들어간다 — 인터페이스(포트·리포지토리)는 그 자체가 도착점이다.
     */
    private static Set<Class<?>> reachableByConstructorInjection(Class<?> root) {
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Type> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            Type type = pending.pop();
            if (type instanceof ParameterizedType parameterized) {
                pending.addAll(Arrays.asList(parameterized.getActualTypeArguments()));
                type = parameterized.getRawType();
            }
            if (!(type instanceof Class<?> clazz) || !seen.add(clazz)) continue;
            if (!clazz.getName().startsWith("com.ssafy.a307.") || clazz.isInterface()) continue;
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                pending.addAll(Arrays.asList(constructor.getGenericParameterTypes()));
            }
        }
        return seen;
    }
}
