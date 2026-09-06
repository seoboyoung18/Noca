package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.estimatevalidation.entity.PartCode;
import com.ssafy.a307.estimatevalidation.entity.PartNameMapping;
import com.ssafy.a307.estimatevalidation.repository.PartNameMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PartNameMappingService {

    private final PartNameMappingRepository repository;
    private final PartNameNormalizer normalizer;

    @Transactional(readOnly = true)
    public Map<String, MappedPart> loadDictionary() {
        Map<String, MappedPart> dictionary = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (PartNameMapping mapping : repository.findAll()) {
            PartCode part = mapping.getPartCode();
            if (!part.isActive()) continue;
            String key = normalizer.normalize(mapping.getRawName());
            MappedPart candidate = new MappedPart(part.getPartCode(), part.getNameKo());
            MappedPart previous = dictionary.putIfAbsent(key, candidate);
            if (previous != null && !previous.partCode().equals(candidate.partCode())) {
                ambiguous.add(key);
            }
        }
        ambiguous.forEach(dictionary::remove);
        return Map.copyOf(dictionary);
    }

    public MappedPart map(String rawName, Map<String, MappedPart> dictionary) {
        return dictionary.get(normalizer.normalize(rawName));
    }

    public record MappedPart(String partCode, String nameKo) {
    }
}
