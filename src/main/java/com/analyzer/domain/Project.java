package com.analyzer.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 분석 대상 프로젝트 엔티티
 * 업로드된 Spring Boot 프로젝트의 메타정보를 저장
 */
@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class Project {

    @Id
    private String id;  // UUID 기반 프로젝트 ID

    /**
     * 프로젝트 이름
     */
    @Column(nullable = false)
    private String name;

    /**
     * 프로젝트 설명
     */
    @Column(length = 1000)
    private String description;

    /**
     * 업로드된 zip 파일 경로
     */
    @Column(nullable = false)
    private String zipFilePath;

    /**
     * 압축 해제된 프로젝트 디렉토리 경로
     */
    @Column(nullable = false)
    private String extractedPath;

    /**
     * 분석 결과 JSON 파일 경로
     */
    private String analysisResultPath;

    /**
     * 분석 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnalysisStatus status = AnalysisStatus.UPLOADED;

    /**
     * 생성 일시
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 분석 완료 일시
     */
    private LocalDateTime analyzedAt;

    public Project(String id, String name, String description, String zipFilePath, String extractedPath) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.zipFilePath = zipFilePath;
        this.extractedPath = extractedPath;
    }
}
