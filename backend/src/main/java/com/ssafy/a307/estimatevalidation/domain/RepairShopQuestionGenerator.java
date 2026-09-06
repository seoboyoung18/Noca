package com.ssafy.a307.estimatevalidation.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class RepairShopQuestionGenerator {

    private static final int MAX_SUBJECT_LENGTH = 60;
    private static final int MAX_QUESTION_LENGTH = 500;
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?is)<script[^>]*>.*?</script>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ACCUSATORY = Pattern.compile("사기|허위|불필요(?:한 수리|확정)?");
    private static final Pattern UNSAFE = Pattern.compile("[^0-9A-Za-z가-힣 ()/_-]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public List<GeneratedQuestion> generate(List<QuestionSource> sources) {
        if (sources == null || sources.isEmpty()) return List.of();
        List<QuestionCandidate> candidates = sources.stream()
                .flatMap(source -> source.flags().stream()
                        .map(flag -> new QuestionCandidate(source, flag)))
                .sorted(Comparator.comparingInt((QuestionCandidate candidate) -> candidate.source().lineNo())
                        .thenComparingInt(candidate -> candidate.flag().questionOrder()))
                .toList();

        Set<String> seen = new HashSet<>();
        List<GeneratedQuestion> questions = new ArrayList<>();
        for (QuestionCandidate candidate : candidates) {
            String text = question(candidate.source(), candidate.flag());
            if (seen.add(text)) {
                questions.add(new GeneratedQuestion(
                        candidate.source().lineNo(), candidate.flag(), text, questions.size() + 1));
            }
        }
        return List.copyOf(questions);
    }

    private String question(QuestionSource source, ValidationFlag flag) {
        String subject = sanitize(source.standardPartName());
        if (subject.isEmpty()) subject = sanitize(source.rawItemName());
        if (subject.isEmpty()) subject = "해당 항목";
        String work = source.workType().displayName();
        String text = switch (flag) {
            case OVER_P75 -> "%s %s 비용의 산정 근거와 작업 범위, 적용 공임 단가를 알려주실 수 있나요?".formatted(subject, work);
            case NOT_IN_ANALYSIS -> "%s 교환이 필요한 손상 근거와 가능한 다른 수리 방식을 알려주실 수 있나요?".formatted(subject);
            case DUPLICATE_LABOR -> "%s %s 공임이 여러 번 산정된 기준과 각각의 작업 범위를 확인해 주실 수 있나요?".formatted(subject, work);
            case UNMAPPED_ITEM -> "%s 항목이 정확히 어떤 부품과 작업을 의미하는지 알려주실 수 있나요?".formatted(subject);
            case INSUFFICIENT_REFERENCE -> "%s %s 작업 범위와 비용 산정 근거를 알려주실 수 있나요?".formatted(subject, work);
        };
        return text.length() <= MAX_QUESTION_LENGTH ? text : text.substring(0, MAX_QUESTION_LENGTH - 1) + "?";
    }

    private String sanitize(String value) {
        if (value == null) return "";
        String sanitized = SCRIPT_BLOCK.matcher(value).replaceAll(" ");
        sanitized = TAG.matcher(sanitized).replaceAll(" ");
        sanitized = ACCUSATORY.matcher(sanitized).replaceAll(" ");
        sanitized = UNSAFE.matcher(sanitized).replaceAll(" ");
        sanitized = WHITESPACE.matcher(sanitized).replaceAll(" ").strip();
        return sanitized.length() <= MAX_SUBJECT_LENGTH
                ? sanitized
                : sanitized.substring(0, MAX_SUBJECT_LENGTH).strip();
    }

    private record QuestionCandidate(QuestionSource source, ValidationFlag flag) {
    }
}
