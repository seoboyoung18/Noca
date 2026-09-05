package com.ssafy.a307.guide.service;

import com.ssafy.a307.guide.dto.ChecklistResponse;
import org.springframework.stereotype.Service;

/**
 * 사고 현장 체크리스트 문안 제공.
 *
 * <p>DB 를 쓰지 않으므로 Entity·Repository 가 없고 {@code @Transactional} 도 없다.
 * 문안은 기동 시점에 한 번 읽어 그대로 돌려준다.
 */
@Service
public class ChecklistService {

    private static final String CONTENT_LOCATION = "checklist.json";

    private final ChecklistResponse checklist;

    public ChecklistService(GuideContentLoader loader) {
        this.checklist = loader.load(CONTENT_LOCATION, ChecklistResponse.class);
    }

    public ChecklistResponse getChecklist() {
        return checklist;
    }
}
