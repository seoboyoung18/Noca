package com.ssafy.a307.repairchecklist;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.dto.EstimateNotice;
import com.ssafy.a307.estimate.service.EstimateNoticeProvider;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistItemResponse;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistProgress;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistStatusResponse;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistItemSource;
import com.ssafy.a307.repairchecklist.service.RepairChecklistItemService;
import com.ssafy.a307.repairchecklist.service.RepairChecklistStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 항목 조회·체크·메모·진행률(S15P21A307-483)과 안내 한계 고지(-487 · -488)를 <b>실제 H2</b> 로 본다.
 *
 * <p>워커를 돌리지 않는다 — 이 작업이 보는 것은 <b>이미 만들어진 체크리스트</b>를 다루는 경로이고,
 * 생성 경로는 {@link RepairChecklistGenerationTest} 가 이미 고정했다. 항목을 JDBC 로 직접 심으면
 * LLM 대역도 스케줄러도 필요 없다.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다. 서비스가 자기 트랜잭션에서 쓴 값을 JDBC 로
 * 다시 읽어 확인해야 하기 때문이다. 대신 높은 ID 대역을 쓰고 {@code @AfterEach} 에서 직접 지운다.
 */
@SpringBootTest
@DisplayName("체크리스트 항목 체크·메모·진행률과 안내 고지")
class RepairChecklistItemTest {

    private static final long MEMBER_ID = 96_501L;
    private static final long OTHER_MEMBER_ID = 96_502L;
    private static final long MODEL_ID = 96_503L;
    private static final long VEHICLE_ID = 96_504L;
    private static final long ACCIDENT_ID = 96_505L;
    private static final long OTHER_ACCIDENT_ID = 96_506L;
    private static final long CHECKLIST_ID = 96_507L;
    private static final long OTHER_CHECKLIST_ID = 96_508L;
    private static final long ITEM_AI_1 = 96_511L;
    private static final long ITEM_AI_2 = 96_512L;
    private static final long ITEM_COMMON = 96_513L;
    private static final long ITEM_OTHER = 96_514L;

    @Autowired private RepairChecklistStatusService statusService;
    @Autowired private RepairChecklistItemService itemService;
    @Autowired private EstimateNoticeProvider noticeProvider;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','checklist-item','tester','USER','ACTIVE')", MEMBER_ID);
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','checklist-stranger','stranger','USER','ACTIVE')", OTHER_MEMBER_ID);
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(?,'현대','아반떼','SEDAN','Mid-size',true)", MODEL_ID);
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2024)",
                VEHICLE_ID, MEMBER_ID, MODEL_ID);
        insertAccident(ACCIDENT_ID);
        insertAccident(OTHER_ACCIDENT_ID);

        insertChecklist(CHECKLIST_ID, ACCIDENT_ID, "COMPLETED");
        insertChecklist(OTHER_CHECKLIST_ID, OTHER_ACCIDENT_ID, "COMPLETED");

        // AI 두 줄 + 공통 한 줄. 공통 문안과 코드는 마스터에서 읽어 온다 — 여기에 한글을 적으면
        // 문안 사본이 하나 더 생긴다(RepairChecklistGenerationTest 와 같은 규칙).
        insertItem(ITEM_AI_1, CHECKLIST_ID, "AI", null, "첫 번째 확인 항목", 1);
        insertItem(ITEM_AI_2, CHECKLIST_ID, "AI", null, "두 번째 확인 항목", 2);
        Map<String, Object> master = jdbc.queryForMap(
                "select code, message from repair_checklist_common_item"
                        + " where is_active = true order by display_order asc, code asc limit 1");
        insertItem(ITEM_COMMON, CHECKLIST_ID, "COMMON", (String) master.get("code"),
                (String) master.get("message"), 3);
        insertItem(ITEM_OTHER, OTHER_CHECKLIST_ID, "AI", null, "남의 사고 항목", 1);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbc.update("delete from estimate_notice where code = ?",
                EstimateNoticeProvider.GUIDANCE_LIMIT_NOTICE_CODE);
        jdbc.update("delete from repair_checklist_item where checklist_id in (?,?)",
                CHECKLIST_ID, OTHER_CHECKLIST_ID);
        jdbc.update("delete from repair_checklist where checklist_id in (?,?)",
                CHECKLIST_ID, OTHER_CHECKLIST_ID);
        jdbc.update("delete from accident where accident_id in (?,?)", ACCIDENT_ID, OTHER_ACCIDENT_ID);
        jdbc.update("delete from vehicle where vehicle_id = ?", VEHICLE_ID);
        jdbc.update("delete from vehicle_model where model_id = ?", MODEL_ID);
        jdbc.update("delete from member where member_id in (?,?)", MEMBER_ID, OTHER_MEMBER_ID);
    }

    private void insertAccident(long accidentId) {
        jdbc.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type,
                    snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                    snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                values(?, ?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Mid-size', 2024)
                """, accidentId, VEHICLE_ID, MODEL_ID);
    }

    private void insertChecklist(long checklistId, long accidentId, String status) {
        jdbc.update("insert into repair_checklist(checklist_id,accident_id,status,generation_no,"
                + "completed_at) values(?,?,?,1,current_timestamp)", checklistId, accidentId, status);
    }

    private void insertItem(long itemId, long checklistId, String source, String commonCode,
                            String content, int order) {
        jdbc.update("insert into repair_checklist_item(item_id,checklist_id,source,common_code,"
                        + "content,is_checked,display_order) values(?,?,?,?,?,false,?)",
                itemId, checklistId, source, commonCode, content, order);
    }

    // ─────────────────────────────────────────────────────────── 조회

    @Nested
    @DisplayName("항목 조회 (S15P21A307-483)")
    class Query {

        @Test
        @DisplayName("항목이 display_order 순으로 나오고 source 가 코드다")
        void itemsComeInDisplayOrder() {
            RepairChecklistStatusResponse found = statusService.status(MEMBER_ID, ACCIDENT_ID);

            assertThat(found.items()).extracting(RepairChecklistItemResponse::itemId)
                    .containsExactly(ITEM_AI_1, ITEM_AI_2, ITEM_COMMON);
            assertThat(found.items()).extracting(RepairChecklistItemResponse::displayOrder)
                    .containsExactly((short) 1, (short) 2, (short) 3);
            assertThat(found.items()).extracting(RepairChecklistItemResponse::source)
                    .containsExactly(RepairChecklistItemSource.AI, RepairChecklistItemSource.AI,
                            RepairChecklistItemSource.COMMON);
        }

        @Test
        @DisplayName("common_code 는 COMMON 항목에만 있다 — ck_rcli_link 가 양방향이다")
        void onlyCommonItemsCarryTheCode() {
            List<RepairChecklistItemResponse> items = statusService.status(MEMBER_ID, ACCIDENT_ID).items();

            assertThat(items.get(0).commonCode()).isNull();
            assertThat(items.get(1).commonCode()).isNull();
            assertThat(items.get(2).commonCode()).isNotBlank();
        }

        @Test
        @DisplayName("완성되지 않은 체크리스트는 항목을 주지 않는다")
        void inFlightChecklistHasNoItems() {
            jdbc.update("update repair_checklist set status='QUEUED', completed_at=null"
                    + " where checklist_id = ?", CHECKLIST_ID);

            RepairChecklistStatusResponse found = statusService.status(MEMBER_ID, ACCIDENT_ID);

            assertThat(found.items()).isEmpty();
            assertThat(found.progress()).isEqualTo(RepairChecklistProgress.EMPTY);
        }

        @Test
        @DisplayName("남의 사고와 없는 사고는 모두 404 다")
        void othersAndMissingAreBoth404() {
            assertThat(notFound(() -> statusService.status(OTHER_MEMBER_ID, ACCIDENT_ID))).isTrue();
            assertThat(notFound(() -> statusService.status(MEMBER_ID, 99_999_999L))).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────── 진행률

    @Nested
    @DisplayName("진행률 (S15P21A307-482 완료/전체)")
    class Progress {

        @Test
        @DisplayName("완료/전체 두 수로 나온다")
        void progressIsTwoNumbers() {
            assertThat(statusService.status(MEMBER_ID, ACCIDENT_ID).progress())
                    .isEqualTo(new RepairChecklistProgress(0, 3));

            itemService.changeChecked(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, true);

            assertThat(statusService.status(MEMBER_ID, ACCIDENT_ID).progress())
                    .isEqualTo(new RepairChecklistProgress(1, 3));
        }

        /**
         * §2-2 의 고정. <b>퍼센트 필드가 없다.</b> 나눗셈은 화면이 한다 — 서버가 미리 나누면
         * 0/0 표시와 소수점 자리 같은 표현 결정을 서버가 떠안는다.
         */
        @Test
        @DisplayName("퍼센트 필드가 없다 — 두 수뿐이다")
        void progressHasNoPercentField() {
            assertThat(Arrays.stream(RepairChecklistProgress.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .containsExactlyInAnyOrder("completed", "total");
        }

        @Test
        @DisplayName("진행률 열을 만들지 않았다 — repair_checklist 에 그 컬럼이 없다")
        void noProgressColumnExists() {
            List<String> columns = jdbc.queryForList(
                    "select lower(column_name) from information_schema.columns"
                            + " where lower(table_name) = 'repair_checklist'", String.class);

            assertThat(columns).doesNotContain("progress", "completed_count", "total_count", "percent");
        }
    }

    // ─────────────────────────────────────────────────────────── 체크

    @Nested
    @DisplayName("완료 체크 (ck_rcli_checked)")
    class Check {

        @Test
        @DisplayName("체크하면 is_checked 와 checked_at 이 함께 찬다")
        void checkSetsBothColumns() {
            RepairChecklistItemResponse changed =
                    itemService.changeChecked(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, true);

            assertThat(changed.checked()).isTrue();
            assertThat(changed.checkedAt()).isNotNull();
            assertThat(isCheckedOf(ITEM_AI_1)).isTrue();
            assertThat(checkedAtOf(ITEM_AI_1)).isNotNull();
        }

        /**
         * §2-3 의 핵심. {@code CHECK (checked_at IS NULL OR is_checked = TRUE)} 이므로
         * <b>해제하면서 {@code checked_at} 을 남기면 UPDATE 가 거부된다.</b>
         */
        @Test
        @DisplayName("체크를 해제하면 checked_at 이 NULL 로 돌아간다")
        void uncheckClearsCheckedAt() {
            itemService.changeChecked(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, true);
            assertThat(checkedAtOf(ITEM_AI_1)).isNotNull();

            RepairChecklistItemResponse changed =
                    itemService.changeChecked(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, false);

            assertThat(changed.checked()).isFalse();
            assertThat(changed.checkedAt()).isNull();
            assertThat(isCheckedOf(ITEM_AI_1)).isFalse();
            assertThat(checkedAtOf(ITEM_AI_1)).isNull();
        }

        @Test
        @DisplayName("DB 가 실제로 막는다 — 해제하면서 checked_at 을 남기면 거부된다")
        void databaseRejectsInconsistentPair() {
            assertThatThrownBy(() -> jdbc.update(
                    "update repair_checklist_item set is_checked = false,"
                            + " checked_at = current_timestamp where item_id = ?", ITEM_AI_1))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("같은 값을 두 번 보내도 결과가 같고 처음 체크한 시각이 보존된다")
        void checkIsIdempotent() {
            itemService.changeChecked(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, true);
            Object first = checkedAtOf(ITEM_AI_1);

            itemService.changeChecked(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, true);

            assertThat(checkedAtOf(ITEM_AI_1)).isEqualTo(first);
            assertThat(statusService.status(MEMBER_ID, ACCIDENT_ID).progress().completed()).isEqualTo(1);
        }

        @Test
        @DisplayName("없는 항목·남의 항목·다른 사고의 항목은 모두 404 다")
        void wrongItemsAreAll404() {
            assertThat(notFound(() -> itemService.changeChecked(MEMBER_ID, ACCIDENT_ID, 99_999_999L, true)))
                    .isTrue();
            assertThat(notFound(() -> itemService.changeChecked(OTHER_MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, true)))
                    .isTrue();
            // 항목은 내 것이지만 경로의 사고가 다르다. 받아 주면 경로가 거짓말을 하게 된다.
            assertThat(notFound(() -> itemService.changeChecked(MEMBER_ID, OTHER_ACCIDENT_ID, ITEM_AI_1, true)))
                    .isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────── 메모

    @Nested
    @DisplayName("메모 (§2-4 체크와 독립)")
    class Memo {

        @Test
        @DisplayName("체크하지 않은 항목에도 메모를 남길 수 있다")
        void memoDoesNotRequireCheck() {
            RepairChecklistItemResponse changed =
                    itemService.changeMemo(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, "정비사 답변 기록");

            assertThat(changed.memo()).isEqualTo("정비사 답변 기록");
            assertThat(changed.checked()).isFalse();
            assertThat(memoOf(ITEM_AI_1)).isEqualTo("정비사 답변 기록");
            assertThat(isCheckedOf(ITEM_AI_1)).isFalse();
        }

        @Test
        @DisplayName("메모가 체크 상태를 건드리지 않는다")
        void memoKeepsCheckedState() {
            itemService.changeChecked(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, true);
            Object checkedAt = checkedAtOf(ITEM_AI_1);

            itemService.changeMemo(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, "메모만 바꾼다");

            assertThat(isCheckedOf(ITEM_AI_1)).isTrue();
            assertThat(checkedAtOf(ITEM_AI_1)).isEqualTo(checkedAt);
        }

        @Test
        @DisplayName("빈 메모를 보내면 지운다 — 빈 문자열을 남기지 않는다")
        void blankMemoClearsIt() {
            itemService.changeMemo(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, "지울 메모");
            assertThat(memoOf(ITEM_AI_1)).isNotNull();

            itemService.changeMemo(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, "   ");

            assertThat(memoOf(ITEM_AI_1)).isNull();
        }

        @Test
        @DisplayName("체크를 해제해도 메모는 남는다")
        void memoSurvivesUncheck() {
            itemService.changeChecked(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, true);
            itemService.changeMemo(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, "남아야 하는 메모");

            itemService.changeChecked(MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, false);

            assertThat(memoOf(ITEM_AI_1)).isEqualTo("남아야 하는 메모");
        }

        @Test
        @DisplayName("남의 항목 메모는 404 다")
        void othersMemoIs404() {
            assertThat(notFound(() -> itemService.changeMemo(OTHER_MEMBER_ID, ACCIDENT_ID, ITEM_AI_1, "x")))
                    .isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────── 고지 문구

    @Nested
    @DisplayName("안내 한계 고지 (S15P21A307-487 · -488)")
    class Notice {

        /**
         * <b>기대 문안을 자바에 적지 않는다.</b> 마이그레이션 파일에서 읽어 와 DB 에 넣고, 그 값이
         * 조회 응답까지 글자 하나 바뀌지 않고 도달하는지만 본다 — 여기에 한글을 적으면 문안 사본이
         * 하나 더 생긴다({@code RepairChecklistGenerationTest} 와 같은 규칙).
         *
         * <p>지라 {@code -487} 본문과 이 파일의 대조는 사람이 했고 결과는 {@code answer70} 에 있다.
         */
        @Test
        @DisplayName("고지 문구가 조회 응답에 실리고 마이그레이션 문안과 글자까지 같다")
        void noticeReachesTheResponseUnchanged() {
            String expected = messageFromMigration();
            seedNotice(expected);

            RepairChecklistStatusResponse found = statusService.status(MEMBER_ID, ACCIDENT_ID);

            assertThat(found.notice()).isEqualTo(expected);
            assertThat(noticeProvider.guidanceLimitNotice()).isEqualTo(expected);
        }

        @Test
        @DisplayName("문구가 없어도 조회는 200 이고 notice 만 null 이다")
        void missingNoticeDoesNotKillTheQuery() {
            RepairChecklistStatusResponse found = statusService.status(MEMBER_ID, ACCIDENT_ID);

            assertThat(found.notice()).isNull();
            assertThat(found.items()).hasSize(3);
        }

        @Test
        @DisplayName("내려 둔 문구는 없는 것으로 본다")
        void inactiveNoticeIsHidden() {
            seedNotice(messageFromMigration());
            jdbc.update("update estimate_notice set is_active = false where code = ?",
                    EstimateNoticeProvider.GUIDANCE_LIMIT_NOTICE_CODE);

            assertThat(noticeProvider.guidanceLimitNotice()).isNull();
        }

        /**
         * 이 문구는 {@code estimate_notice} 에 얹혀 있지만 <b>견적 화면에 나가면 안 된다</b> —
         * 문장이 "본 체크리스트와 질문은 …" 으로 시작해 있지도 않은 섹션을 가리킨다.
         */
        @Test
        @DisplayName("견적 조회의 notices[] 에는 섞이지 않는다")
        void estimateNoticesExcludeIt() {
            seedNotice(messageFromMigration());

            List<EstimateNotice> notices = noticeProvider.activeNotices();

            assertThat(notices).extracting(EstimateNotice::code)
                    .contains(EstimateNoticeProvider.LEGAL_NOTICE_CODE)
                    .doesNotContain(EstimateNoticeProvider.GUIDANCE_LIMIT_NOTICE_CODE);
        }

        @Test
        @DisplayName("마이그레이션이 두 번 돌아도 행이 하나다 — ON CONFLICT DO NOTHING")
        void seedIsIdempotent() {
            String message = messageFromMigration();
            seedNotice(message);
            seedNotice(message);

            assertThat(jdbc.queryForObject("select count(*) from estimate_notice where code = ?",
                    Integer.class, EstimateNoticeProvider.GUIDANCE_LIMIT_NOTICE_CODE)).isEqualTo(1);
        }

        private void seedNotice(String message) {
            jdbc.update("merge into estimate_notice(code, message, display_order, is_active)"
                            + " key(code) values(?,?,1,true)",
                    EstimateNoticeProvider.GUIDANCE_LIMIT_NOTICE_CODE, message);
        }
    }

    // ─────────────────────────────────────────────────────────── 도우미

    /** 마이그레이션 파일에서 고지 문안을 그대로 읽어 온다. 자바에 사본을 두지 않기 위해서다. */
    private static String messageFromMigration() {
        Path relative = Path.of("Docs/Erd/migrations/2026-09-15-guidance-notice.sql");
        Path fromModule = Path.of("..").resolve(relative);
        Path path = Files.exists(fromModule) ? fromModule : relative;
        String sql;
        try {
            sql = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("마이그레이션 파일을 읽지 못했다: " + path, e);
        }
        Matcher matcher = Pattern.compile(
                "\\('GUIDANCE_LIMIT_NOTICE',\\s*'([^']+)'").matcher(sql);
        if (!matcher.find()) {
            throw new AssertionError("마이그레이션에서 고지 문안을 찾지 못했다: " + path);
        }
        return matcher.group(1);
    }

    private static boolean notFound(Runnable call) {
        try {
            call.run();
            return false;
        } catch (BusinessException e) {
            return e.getErrorCode() == ErrorCode.NOT_FOUND;
        }
    }

    private Boolean isCheckedOf(long itemId) {
        return jdbc.queryForObject("select is_checked from repair_checklist_item where item_id = ?",
                Boolean.class, itemId);
    }

    private Object checkedAtOf(long itemId) {
        return jdbc.queryForObject("select checked_at from repair_checklist_item where item_id = ?",
                Object.class, itemId);
    }

    private String memoOf(long itemId) {
        return jdbc.queryForObject("select memo from repair_checklist_item where item_id = ?",
                String.class, itemId);
    }
}
