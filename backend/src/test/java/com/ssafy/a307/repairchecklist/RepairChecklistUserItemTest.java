package com.ssafy.a307.repairchecklist;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistItemResponse;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistStatusResponse;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistItem;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistItemSource;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus;
import com.ssafy.a307.repairchecklist.service.RepairChecklistItemService;
import com.ssafy.a307.repairchecklist.service.RepairChecklistRegenerateService;
import com.ssafy.a307.repairchecklist.service.RepairChecklistStatusService;
import com.ssafy.a307.repairchecklist.service.RepairChecklistWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 사용자 항목 추가·수정·삭제({@code S15P21A307-485})와 재생성({@code -486})을 <b>실제 H2</b> 로 본다.
 *
 * <p>워커를 켠다 — 재생성이 사용자 항목을 남겨 둔 뒤 <b>워커가 다시 돌았을 때도 살아 있는지</b>가
 * 이 작업의 핵심이기 때문이다. 재생성만 보고 끝내면 몇 초 뒤에 지워지는 것을 놓친다.
 * LLM 대역은 {@link RepairChecklistGenerationTest.ScriptedLlmConfig} 를 그대로 쓴다 — 같은 패키지의
 * 같은 필요라 하나 더 만들지 않는다.
 *
 * <p>{@code initial-delay} 를 1시간으로 밀어 <b>스케줄러가 스스로 돌지 않게</b> 한다. 주기를
 * 테스트가 직접 부르지 않으면 어느 시점에 무엇이 처리됐는지 단언할 수 없다.
 */
@SpringBootTest(properties = {
        "app.repair-checklist.enabled=true",
        "app.repair-checklist.poll-interval=PT1H",
        "app.repair-checklist.initial-delay=PT1H",
        "app.repair-checklist.batch-size=5",
        "app.repair-checklist.processing-timeout=PT10M"
})
@Import(RepairChecklistGenerationTest.ScriptedLlmConfig.class)
@DisplayName("사용자 항목 추가·수정·삭제와 재생성")
class RepairChecklistUserItemTest {

    private static final long MEMBER_ID = 96_601L;
    private static final long OTHER_MEMBER_ID = 96_602L;
    private static final long MODEL_ID = 96_603L;
    private static final long VEHICLE_ID = 96_604L;
    private static final long ACCIDENT_ID = 96_605L;
    private static final long OTHER_ACCIDENT_ID = 96_606L;
    private static final long CHECKLIST_ID = 96_607L;
    private static final long OTHER_CHECKLIST_ID = 96_608L;
    private static final long ITEM_AI = 96_611L;
    private static final long ITEM_COMMON = 96_612L;
    private static final long ITEM_OTHER = 96_613L;

    @Autowired private RepairChecklistItemService itemService;
    @Autowired private RepairChecklistRegenerateService regenerateService;
    @Autowired private RepairChecklistStatusService statusService;
    @Autowired private RepairChecklistWorker worker;
    @Autowired private RepairChecklistGenerationTest.ScriptedLlmChatPort llm;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanUp();
        llm.reset();
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','checklist-user-item','tester','USER','ACTIVE')", MEMBER_ID);
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','checklist-user-other','stranger','USER','ACTIVE')", OTHER_MEMBER_ID);
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(?,'현대','아반떼','SEDAN','Mid-size',true)", MODEL_ID);
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2024)",
                VEHICLE_ID, MEMBER_ID, MODEL_ID);
        insertAccident(ACCIDENT_ID);
        insertAccident(OTHER_ACCIDENT_ID);
        insertChecklist(CHECKLIST_ID, ACCIDENT_ID);
        insertChecklist(OTHER_CHECKLIST_ID, OTHER_ACCIDENT_ID);

        // AI 한 줄 + 공통 한 줄. 공통 문안은 마스터에서 읽어 온다 — 자바에 사본을 만들지 않는다.
        insertItem(ITEM_AI, CHECKLIST_ID, "AI", null, "AI 가 만든 항목", 1);
        Map<String, Object> master = jdbc.queryForMap(
                "select code, message from repair_checklist_common_item"
                        + " where is_active = true order by display_order asc, code asc limit 1");
        insertItem(ITEM_COMMON, CHECKLIST_ID, "COMMON", (String) master.get("code"),
                (String) master.get("message"), 2);
        insertItem(ITEM_OTHER, OTHER_CHECKLIST_ID, "USER", null, "남의 사고 항목", 1);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
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

    private void insertChecklist(long checklistId, long accidentId) {
        jdbc.update("insert into repair_checklist(checklist_id,accident_id,status,generation_no,"
                + "completed_at) values(?,?,'COMPLETED',1,current_timestamp)", checklistId, accidentId);
    }

    private void insertItem(long itemId, long checklistId, String source, String commonCode,
                            String content, int order) {
        jdbc.update("insert into repair_checklist_item(item_id,checklist_id,source,common_code,"
                        + "content,is_checked,display_order) values(?,?,?,?,?,false,?)",
                itemId, checklistId, source, commonCode, content, order);
    }

    // ─────────────────────────────────────────────────────────── 추가

    @Nested
    @DisplayName("항목 추가 (S15P21A307-485)")
    class Add {

        @Test
        @DisplayName("source 가 USER 이고 common_code 가 비어 있다")
        void addedItemIsUserSourced() {
            RepairChecklistItemResponse added =
                    itemService.add(MEMBER_ID, ACCIDENT_ID, "내가 직접 물어볼 것");

            assertThat(added.source()).isEqualTo(RepairChecklistItemSource.USER);
            assertThat(added.commonCode()).isNull();
            assertThat(added.checked()).isFalse();
            assertThat(sourceOf(added.itemId())).isEqualTo("USER");
            assertThat(commonCodeOf(added.itemId())).isNull();
        }

        @Test
        @DisplayName("display_order 가 기존 최댓값 + 1 이다 — 맨 뒤에 붙는다")
        void addedItemGoesLast() {
            RepairChecklistItemResponse first = itemService.add(MEMBER_ID, ACCIDENT_ID, "첫 추가");
            RepairChecklistItemResponse second = itemService.add(MEMBER_ID, ACCIDENT_ID, "둘째 추가");

            assertThat(first.displayOrder()).isEqualTo((short) 3);
            assertThat(second.displayOrder()).isEqualTo((short) 4);
        }

        @Test
        @DisplayName("항목이 하나도 없으면 display_order 가 1 이다")
        void firstItemStartsAtOne() {
            jdbc.update("delete from repair_checklist_item where checklist_id = ?", CHECKLIST_ID);

            assertThat(itemService.add(MEMBER_ID, ACCIDENT_ID, "유일한 항목").displayOrder())
                    .isEqualTo((short) 1);
        }

        /** §3-1 #3. 400 이 아니라 <b>절단</b>이다 — prompt67 §2-1 이 그렇게 정했다. */
        @Test
        @DisplayName("content 가 500자를 넘으면 잘려서 저장된다")
        void longContentIsTruncated() {
            String tooLong = "가".repeat(RepairChecklistItem.MAX_CONTENT_LENGTH + 50);

            RepairChecklistItemResponse added = itemService.add(MEMBER_ID, ACCIDENT_ID, tooLong);

            assertThat(added.content()).hasSize(RepairChecklistItem.MAX_CONTENT_LENGTH);
            assertThat(contentOf(added.itemId())).hasSize(RepairChecklistItem.MAX_CONTENT_LENGTH);
        }

        @Test
        @DisplayName("체크리스트가 없는 사고는 404 다")
        void missingChecklistIs404() {
            jdbc.update("delete from repair_checklist_item where checklist_id = ?", CHECKLIST_ID);
            jdbc.update("delete from repair_checklist where checklist_id = ?", CHECKLIST_ID);

            assertThat(notFound(() -> itemService.add(MEMBER_ID, ACCIDENT_ID, "넣을 수 없다"))).isTrue();
        }

        @Test
        @DisplayName("남의 사고에는 넣을 수 없다 — 404")
        void othersAccidentIs404() {
            assertThat(notFound(() -> itemService.add(OTHER_MEMBER_ID, ACCIDENT_ID, "남의 것"))).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────── 수정·삭제

    @Nested
    @DisplayName("USER 항목만 고치고 지울 수 있다")
    class UserOnly {

        @Test
        @DisplayName("USER 항목 문안을 고칠 수 있다")
        void userItemContentCanChange() {
            Long itemId = itemService.add(MEMBER_ID, ACCIDENT_ID, "처음 문안").itemId();

            RepairChecklistItemResponse changed =
                    itemService.changeContent(MEMBER_ID, ACCIDENT_ID, itemId, "고친 문안");

            assertThat(changed.content()).isEqualTo("고친 문안");
            assertThat(contentOf(itemId)).isEqualTo("고친 문안");
        }

        @Test
        @DisplayName("AI 항목 문안 수정은 400 이다")
        void aiItemContentIsRejected() {
            assertThat(badRequest(() ->
                    itemService.changeContent(MEMBER_ID, ACCIDENT_ID, ITEM_AI, "고쳐 보자"))).isTrue();
            assertThat(contentOf(ITEM_AI)).isEqualTo("AI 가 만든 항목");
        }

        @Test
        @DisplayName("COMMON 항목 문안 수정은 400 이다")
        void commonItemContentIsRejected() {
            assertThat(badRequest(() ->
                    itemService.changeContent(MEMBER_ID, ACCIDENT_ID, ITEM_COMMON, "고쳐 보자"))).isTrue();
        }

        @Test
        @DisplayName("USER 항목은 지울 수 있다")
        void userItemCanBeDeleted() {
            Long itemId = itemService.add(MEMBER_ID, ACCIDENT_ID, "지울 항목").itemId();

            itemService.delete(MEMBER_ID, ACCIDENT_ID, itemId);

            assertThat(countItems(CHECKLIST_ID)).isEqualTo(2);
            assertThat(sourceOf(itemId)).isNull();
        }

        @Test
        @DisplayName("COMMON 항목 삭제는 400 이다 — 체크하지 않고 두면 된다")
        void commonItemDeleteIsRejected() {
            assertThat(badRequest(() -> itemService.delete(MEMBER_ID, ACCIDENT_ID, ITEM_COMMON))).isTrue();
            assertThat(countItems(CHECKLIST_ID)).isEqualTo(2);
        }

        @Test
        @DisplayName("AI 항목 삭제도 400 이다")
        void aiItemDeleteIsRejected() {
            assertThat(badRequest(() -> itemService.delete(MEMBER_ID, ACCIDENT_ID, ITEM_AI))).isTrue();
        }

        /** §2-5. 항목이 내 것이어도 <b>경로의 사고가 다르면</b> 404 다. */
        @Test
        @DisplayName("다른 체크리스트의 itemId 는 404 다")
        void itemFromAnotherChecklistIs404() {
            assertThat(notFound(() ->
                    itemService.changeContent(MEMBER_ID, ACCIDENT_ID, ITEM_OTHER, "남의 것"))).isTrue();
            assertThat(notFound(() -> itemService.delete(MEMBER_ID, ACCIDENT_ID, ITEM_OTHER))).isTrue();
        }

        @Test
        @DisplayName("남의 항목은 404 다 — 존재 사실을 알려 주지 않는다")
        void othersItemIs404() {
            Long itemId = itemService.add(MEMBER_ID, ACCIDENT_ID, "내 항목").itemId();

            assertThat(notFound(() ->
                    itemService.changeContent(OTHER_MEMBER_ID, ACCIDENT_ID, itemId, "가로채기"))).isTrue();
            assertThat(notFound(() -> itemService.delete(OTHER_MEMBER_ID, ACCIDENT_ID, itemId))).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────── 재생성

    @Nested
    @DisplayName("재생성 (S15P21A307-486)")
    class Regenerate {

        @Test
        @DisplayName("USER 항목만 남고 AI·COMMON 은 지워진다")
        void userItemsSurvive() {
            Long mine = itemService.add(MEMBER_ID, ACCIDENT_ID, "내가 적은 것").itemId();

            regenerateService.regenerate(MEMBER_ID, ACCIDENT_ID);

            assertThat(itemsOf(CHECKLIST_ID)).extracting(row -> row.get("item_id"))
                    .extracting(Object::toString)
                    .containsExactly(mine.toString());
            assertThat(sourceOf(ITEM_AI)).isNull();
            assertThat(sourceOf(ITEM_COMMON)).isNull();
        }

        @Test
        @DisplayName("generation_no 가 2 가 되고 regenerated_at 이 함께 찬다 — ck_rcl_regen")
        void generationAndTimestampMoveTogether() {
            RepairChecklistStatusResponse response = regenerateService.regenerate(MEMBER_ID, ACCIDENT_ID);

            assertThat(response.generationNo()).isEqualTo((short) 2);
            assertThat(response.regeneratedAt()).isNotNull();
            assertThat(response.status()).isEqualTo(RepairChecklistStatus.QUEUED);
            assertThat(generationNoOf(CHECKLIST_ID)).isEqualTo(2);
            assertThat(regeneratedAtOf(CHECKLIST_ID)).isNotNull();
            assertThat(completedAtOf(CHECKLIST_ID)).isNull();
        }

        @Test
        @DisplayName("머리 행을 새로 만들지 않는다 — uk_rcl_accident")
        void headRowIsReused() {
            regenerateService.regenerate(MEMBER_ID, ACCIDENT_ID);

            assertThat(jdbc.queryForObject(
                    "select count(*) from repair_checklist where accident_id = ?",
                    Integer.class, ACCIDENT_ID)).isEqualTo(1);
        }

        @Test
        @DisplayName("USER 항목의 체크 상태와 메모가 유지된다")
        void checkedStateSurvives() {
            Long mine = itemService.add(MEMBER_ID, ACCIDENT_ID, "체크해 둔 내 항목").itemId();
            itemService.changeChecked(MEMBER_ID, ACCIDENT_ID, mine, true);
            itemService.changeMemo(MEMBER_ID, ACCIDENT_ID, mine, "정비사 답변");

            regenerateService.regenerate(MEMBER_ID, ACCIDENT_ID);

            assertThat(jdbc.queryForObject(
                    "select is_checked from repair_checklist_item where item_id = ?",
                    Boolean.class, mine)).isTrue();
            assertThat(jdbc.queryForObject(
                    "select memo from repair_checklist_item where item_id = ?",
                    String.class, mine)).isEqualTo("정비사 답변");
        }

        /**
         * <b>이 테스트가 이 작업의 핵심이다.</b> 재생성이 사용자 항목을 남겨도, 워커가 다시 돌면서
         * "앞 시도 정리" 로 전부 지우면 몇 초 뒤에 사라진다. 지우는 곳이 둘이라 둘 다 같은 규칙이어야
         * 한다({@code deleteGeneratedByChecklistId}).
         */
        @Test
        @DisplayName("워커가 다시 돌아도 USER 항목이 살아남는다")
        void userItemsSurviveTheWorker() {
            Long mine = itemService.add(MEMBER_ID, ACCIDENT_ID, "워커가 지우면 안 되는 것").itemId();
            itemService.changeChecked(MEMBER_ID, ACCIDENT_ID, mine, true);
            regenerateService.regenerate(MEMBER_ID, ACCIDENT_ID);
            llm.nextJson = "{\"items\": [{\"content\": \"새로 만든 AI 항목\"}]}";

            worker.pollOnce();

            assertThat(statusOf(CHECKLIST_ID)).isEqualTo("COMPLETED");
            List<Map<String, Object>> items = itemsOf(CHECKLIST_ID);
            assertThat(items).extracting(row -> row.get("source"))
                    .contains("USER").contains("AI").contains("COMMON");
            assertThat(items.stream().filter(row -> "USER".equals(row.get("source"))).toList())
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.get("content")).isEqualTo("워커가 지우면 안 되는 것");
                        assertThat(row.get("is_checked")).isEqualTo(Boolean.TRUE);
                    });
        }

        @Test
        @DisplayName("완성되지 않은 체크리스트는 409 다 — 실패한 것은 재시도지 재생성이 아니다")
        void unfinishedChecklistIsConflict() {
            jdbc.update("update repair_checklist set status='FAILED', failure_reason='INTERNAL'"
                    + " where checklist_id = ?", CHECKLIST_ID);

            assertThatThrownBy(() -> regenerateService.regenerate(MEMBER_ID, ACCIDENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("체크리스트가 없거나 남의 것이면 404 다")
        void missingOrOthersIs404() {
            assertThat(notFound(() -> regenerateService.regenerate(OTHER_MEMBER_ID, ACCIDENT_ID))).isTrue();
            assertThat(notFound(() -> regenerateService.regenerate(MEMBER_ID, 99_999_999L))).isTrue();
        }

        @Test
        @DisplayName("재생성 응답에는 항목을 싣지 않는다 — 남은 USER 가 결과처럼 보이면 안 된다")
        void responseCarriesNoItems() {
            itemService.add(MEMBER_ID, ACCIDENT_ID, "남는 항목");

            assertThat(regenerateService.regenerate(MEMBER_ID, ACCIDENT_ID).items()).isEmpty();
            // 조회도 완성 전에는 주지 않는다(prompt70).
            assertThat(statusService.status(MEMBER_ID, ACCIDENT_ID).items()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────── DB 제약

    /**
     * §3-1. 엔티티 단위 테스트로는 CHECK 제약이 검증되지 않는다 — H2 에 실제로 써 봐야 한다.
     */
    @Nested
    @DisplayName("제약을 실제로 밟는다")
    class Constraints {

        @Test
        @DisplayName("USER 항목에 common_code 를 채우면 거부된다 — ck_rcli_link")
        void userItemCannotCarryCommonCode() {
            String code = jdbc.queryForObject(
                    "select code from repair_checklist_common_item order by code limit 1", String.class);

            assertThatThrownBy(() -> jdbc.update(
                    "insert into repair_checklist_item(checklist_id,source,common_code,content,"
                            + "is_checked,display_order) values(?,'USER',?,'문안',false,9)",
                    CHECKLIST_ID, code))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("COMMON 항목에 common_code 가 없으면 거부된다 — ck_rcli_link 는 양방향이다")
        void commonItemMustCarryCommonCode() {
            assertThatThrownBy(() -> jdbc.update(
                    "insert into repair_checklist_item(checklist_id,source,content,"
                            + "is_checked,display_order) values(?,'COMMON','문안',false,9)",
                    CHECKLIST_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("generation_no 가 1 인데 regenerated_at 을 채우면 거부된다 — ck_rcl_regen")
        void regeneratedAtNeedsSecondGeneration() {
            assertThatThrownBy(() -> jdbc.update(
                    "update repair_checklist set regenerated_at = current_timestamp"
                            + " where checklist_id = ?", CHECKLIST_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("사용자 항목은 몇 개든 들어간다 — NULL 은 uk_rcli_common 을 통과한다")
        void manyUserItemsAreAllowed() {
            itemService.add(MEMBER_ID, ACCIDENT_ID, "하나");
            itemService.add(MEMBER_ID, ACCIDENT_ID, "둘");
            itemService.add(MEMBER_ID, ACCIDENT_ID, "셋");

            assertThat(countItems(CHECKLIST_ID)).isEqualTo(5);
        }
    }

    // ─────────────────────────────────────────────────────────── 도우미

    private static boolean notFound(Runnable call) {
        return errorCodeIs(call, ErrorCode.NOT_FOUND);
    }

    private static boolean badRequest(Runnable call) {
        return errorCodeIs(call, ErrorCode.INVALID_REQUEST);
    }

    private static boolean errorCodeIs(Runnable call, ErrorCode expected) {
        try {
            call.run();
            return false;
        } catch (BusinessException e) {
            return e.getErrorCode() == expected;
        }
    }

    private String sourceOf(long itemId) {
        List<String> found = jdbc.queryForList(
                "select source from repair_checklist_item where item_id = ?", String.class, itemId);
        return found.isEmpty() ? null : found.getFirst();
    }

    private String commonCodeOf(long itemId) {
        return jdbc.queryForObject("select common_code from repair_checklist_item where item_id = ?",
                String.class, itemId);
    }

    private String contentOf(long itemId) {
        return jdbc.queryForObject("select content from repair_checklist_item where item_id = ?",
                String.class, itemId);
    }

    private Integer countItems(long checklistId) {
        return jdbc.queryForObject("select count(*) from repair_checklist_item where checklist_id = ?",
                Integer.class, checklistId);
    }

    private List<Map<String, Object>> itemsOf(long checklistId) {
        return jdbc.queryForList("select item_id, source, content, is_checked, display_order"
                + " from repair_checklist_item where checklist_id = ?"
                + " order by display_order asc, item_id asc", checklistId);
    }

    private String statusOf(long checklistId) {
        return jdbc.queryForObject("select status from repair_checklist where checklist_id = ?",
                String.class, checklistId);
    }

    private Integer generationNoOf(long checklistId) {
        return jdbc.queryForObject("select generation_no from repair_checklist where checklist_id = ?",
                Integer.class, checklistId);
    }

    private Object regeneratedAtOf(long checklistId) {
        return jdbc.queryForObject("select regenerated_at from repair_checklist where checklist_id = ?",
                Object.class, checklistId);
    }

    private Object completedAtOf(long checklistId) {
        return jdbc.queryForObject("select completed_at from repair_checklist where checklist_id = ?",
                Object.class, checklistId);
    }
}
