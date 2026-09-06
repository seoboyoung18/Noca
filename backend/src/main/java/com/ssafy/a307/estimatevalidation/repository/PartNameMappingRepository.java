package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.entity.PartNameMapping;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartNameMappingRepository extends JpaRepository<PartNameMapping, String> {
    @Override
    @EntityGraph(attributePaths = "partCode")
    List<PartNameMapping> findAll();
}
