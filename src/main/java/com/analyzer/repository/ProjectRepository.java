package com.analyzer.repository;

import com.analyzer.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 프로젝트 데이터 접근 계층
 */
@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {

    /**
     * 프로젝트 이름으로 조회
     */
    List<Project> findByNameContaining(String name);

    /**
     * 최근 생성된 프로젝트 목록 조회
     */
    List<Project> findAllByOrderByCreatedAtDesc();
}
