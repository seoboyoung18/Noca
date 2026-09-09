package com.ssafy.a307.estimate.domain;

import com.ssafy.a307.estimatevalidation.domain.StandardRepairMethod;

import java.util.Arrays;
import java.util.Locale;

/**
 * 수리 방식의 화면 표시 문구.
 *
 * <p><b>어휘를 새로 만들지 않고 {@link StandardRepairMethod} 를 재사용한다.</b>
 * S15P21A307-415 가 견적 작업 어휘를 그 enum 하나로 통합했는데, 여기서 코드를 다시 나열하면
 * 그 작업이 무의미해지고 한쪽만 바뀌었을 때 어긋난다. 이 클래스는 표시 문구만 얹는다.
 *
 * <p><b>문구를 서버가 내려주는 이유</b> — 프론트가 4종을 하드코딩하면 어휘가 바뀔 때 두 곳을
 * 고쳐야 한다. 견적서 검증이 {@code gradeDisplayName} 을 서버에서 주는 것과 같은 판단이다.
 */
public enum RepairMethodDisplay {

    EXCHANGE(StandardRepairMethod.EXCHANGE, "교환"),
    SHEET_METAL(StandardRepairMethod.SHEET_METAL, "판금"),
    COATING(StandardRepairMethod.COATING, "도장"),
    REPAIR(StandardRepairMethod.REPAIR, "수리");

    private final StandardRepairMethod method;
    private final String displayName;

    RepairMethodDisplay(StandardRepairMethod method, String displayName) {
        this.method = method;
        this.displayName = displayName;
    }

    public String code() {
        return method.code();
    }

    public String displayName() {
        return displayName;
    }

    /**
     * DB 에 저장된 코드({@code exchange}·{@code sheet_metal}·{@code coating}·{@code repair})를
     * 표시 문구로 바꾼다.
     * <p>
     * 모르는 코드면 코드를 그대로 돌려준다. {@code ck_ei_method} 가 네 값만 허용하므로 정상
     * 경로에서는 일어나지 않지만, 조회가 예외로 끊기는 것보다 원문을 보여 주는 편이 낫다.
     */
    public static String displayNameOf(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(display -> display.code().equals(normalized))
                .map(RepairMethodDisplay::displayName)
                .findFirst()
                .orElse(code);
    }
}
