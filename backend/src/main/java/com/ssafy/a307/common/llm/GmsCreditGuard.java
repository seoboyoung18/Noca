package com.ssafy.a307.common.llm;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 잔여 크레딧과 만료일을 보고 호출을 막는다.
 *
 * <p><b>왜 필요한가</b> — GMS 키는 <b>팀 공유 자원</b>이고 크레딧 상한과 만료일이 있다. 가드가
 * 없으면 크레딧이 바닥난 뒤 모든 호출이 401/402 로 실패하는데, 그 원인이 로그 어디에도 남지 않아
 * "왜 갑자기 안 되는지" 를 아무도 모른다. 그 사이에도 실패한 요청이 계속 나간다.
 *
 * <p><b>세 가지 원칙</b>
 * <ul>
 *   <li><b>요청마다 조회하지 않는다.</b> 조회 자체가 호출이고 프록시 레이트리밋을 소모한다.
 *       TTL 캐시를 둔다</li>
 *   <li><b>조회 실패가 본 작업을 막지 않는다.</b> 갱신에 실패하면 직전 값을 계속 쓰고 경고만
 *       남긴다. 크레딧을 못 읽었다는 이유로 멀쩡한 호출을 막으면 가드가 장애 원인이 된다</li>
 *   <li><b>거절 메시지에 숫자를 담지 않는다.</b> 잔액·만료일·키는 팀 내부 정보이고 이 메시지는
 *       사용자 화면까지 갈 수 있다. 숫자는 로그에만 남긴다</li>
 * </ul>
 *
 * <p><b>새 의존성을 넣지 않았다.</b> 캐시라고 해야 값 하나에 시각 하나라
 * {@link AtomicReference} 로 충분하다. Caffeine 을 넣을 이유가 없다.
 */
@Slf4j
class GmsCreditGuard {

    /** 사용자에게 보여도 되는 문구. 잔액·만료일·키를 담지 않는다. */
    private static final String REJECTION_MESSAGE = "LLM 서비스를 일시적으로 사용할 수 없습니다. 관리자에게 문의해 주세요.";

    /** {@code expiredDate} 가 KST 기준이라고만 문서에 적혀 있다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final GmsKeyInfoClient keyInfoClient;
    private final GmsProperties properties;
    private final Clock clock;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    GmsCreditGuard(GmsKeyInfoClient keyInfoClient, GmsProperties properties, Clock clock) {
        this.keyInfoClient = keyInfoClient;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 기동 시 1회 남기는 로그.
     *
     * <p>운영자가 "왜 갑자기 실패하는지" 를 로그에서 찾을 수 있어야 한다. <b>actuator health 에는
     * 담지 않는다</b> — {@code management.endpoints.web.exposure.include=health} 만 열려 있고
     * 인증이 없어 크레딧과 만료일이 외부에 노출된다.
     */
    void logStartupSnapshot() {
        GmsKeyInfo info = current();
        if (info == null) {
            log.warn("GMS 크레딧을 확인하지 못했다. 호출은 계속 시도한다.");
            return;
        }
        log.info("GMS 크레딧: 잔여={}, 총={}, 사용={}, 만료일={}",
                info.remainCredit(), info.totalCredit(), info.usedCredit(), info.expiredDate());
    }

    /**
     * 호출 직전 관문.
     *
     * @throws BusinessException 잔여 크레딧이 임계값 미만이거나 키가 만료됐을 때.
     *                           <b>이 경우 전송은 한 번도 일어나지 않는다.</b>
     */
    void ensureUsable() {
        GmsKeyInfo info = current();
        if (info == null) {
            // 한 번도 못 읽었으면 막지 않는다. 조회 실패로 본 작업을 세우지 않는다.
            return;
        }
        if (info.hasRemainCredit() && info.remainCredit() < properties.minRemainCredit()) {
            log.error("GMS 잔여 크레딧이 임계값 미만이라 호출을 거절한다. 잔여={}, 임계값={}",
                    info.remainCredit(), properties.minRemainCredit());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, REJECTION_MESSAGE);
        }
        if (isExpired(info.expiredDate())) {
            log.error("GMS 키가 만료되어 호출을 거절한다. 만료일={}", info.expiredDate());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, REJECTION_MESSAGE);
        }
    }

    /** TTL 이 지났으면 갱신을 시도하고, 실패하면 직전 값을 그대로 쓴다. */
    private GmsKeyInfo current() {
        Cached cached = cache.get();
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.fetchedAt().plus(properties.keyInfoCacheTtl()))) {
            return cached.info();
        }
        try {
            GmsKeyInfo fresh = keyInfoClient.fetch();
            cache.set(new Cached(fresh, now));
            return fresh;
        } catch (RuntimeException e) {
            // 본문·키를 남기지 않는다. 예외 종류만 남긴다.
            log.warn("GMS 크레딧 조회에 실패했다. 직전 값으로 계속한다. 원인={}", e.getClass().getSimpleName());
            return cached == null ? null : cached.info();
        }
    }

    /**
     * 만료 판정. <b>형식을 확정하지 못했으므로 실패하면 판정을 건너뛴다.</b>
     *
     * <p>파싱 실패를 만료로 취급하면 형식이 조금만 달라도 <b>멀쩡한 키로 모든 호출이 막힌다.</b>
     * 반대로 만료된 키를 못 잡으면 호출이 401 로 실패할 뿐이고, 그건 이미 오류 매핑이 다룬다.
     * 둘 중 덜 나쁜 쪽을 고른다.
     */
    private boolean isExpired(String expiredDate) {
        if (expiredDate == null || expiredDate.isBlank()) return false;
        try {
            LocalDate expiry = LocalDate.parse(expiredDate.strip().substring(0, 10));
            return expiry.isBefore(LocalDate.ofInstant(clock.instant(), KST));
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            log.warn("GMS 만료일 형식을 해석하지 못해 만료 판정을 건너뛴다.");
            return false;
        }
    }

    private record Cached(GmsKeyInfo info, Instant fetchedAt) {
    }
}
