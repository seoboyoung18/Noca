package com.ssafy.a307.repairchecklist.service;

import java.util.List;

/**
 * LLM 지시문에 실을 사고 정보. <b>이미 확정된 값만 담는다.</b>
 *
 * <p>차량 값은 {@code accident} 의 <b>스냅샷 열</b>에서 온다 — 현재 {@code vehicle} 값이 아니다.
 * 사고 당시의 차를 기준으로 물어볼 것을 만들어야 하고, 스냅샷을 쓰는 것이 이 저장소의 규칙이다
 * ({@code AccidentRepository} 의 검색 조건 주석).
 *
 * @param parts 분석이 찾아낸 손상 부위. <b>없을 수 있다</b> — 분석을 아직 돌리지 않았거나
 *              실패한 사고다. 그때는 차량 정보만으로 만든다
 */
public record RepairChecklistContext(
        String manufacturer,
        String modelName,
        Short modelYear,
        List<DamagedPartView> parts) {

    /**
     * 손상 부위 한 줄. 값은 전부 DDL 표기 그대로다 — 백엔드가 한글로 옮기지 않는다.
     */
    public record DamagedPartView(String partCode, String damageType, String repairMethod) {
    }
}
