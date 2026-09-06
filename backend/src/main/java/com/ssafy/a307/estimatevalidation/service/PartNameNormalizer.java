package com.ssafy.a307.estimatevalidation.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class PartNameNormalizer {

    private static final Pattern BRACKETS = Pattern.compile("[()\\[\\]{}]");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern WORK_SUFFIX = Pattern.compile("(교환|탈착|판금|도장|오버홀|수리)$");

    public String normalize(String rawName) {
        if (rawName == null) return "";
        String value = Normalizer.normalize(rawName, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace("왼쪽", "좌")
                .replace("오른쪽", "우");
        value = BRACKETS.matcher(value).replaceAll(" ");
        value = SPACE.matcher(value).replaceAll("");
        return WORK_SUFFIX.matcher(value).replaceFirst("");
    }
}
