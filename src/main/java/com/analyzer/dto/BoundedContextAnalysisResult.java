package com.analyzer.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 클라우드 네이티브 전환을 위한 Bounded Context 분석 결과
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BoundedContextAnalysisResult {

    /**
     * Bounded Context 목록
     */
    private List<BoundedContext> contexts;

    /**
     * 매핑되지 않은 컴포넌트
     */
    private UnmappedComponents unmapped;

    /**
     * 분석 중 발견한 질문
     */
    private List<String> questions;

    /**
     * 다음 단계 권장사항
     */
    private List<String> nextRecommendations;

    /**
     * Bounded Context (도메인 경계)
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BoundedContext {
        /**
         * 도메인명
         */
        private String domain;

        /**
         * 서브도메인명
         */
        private String subdomain;

        /**
         * 컨트롤러 목록 (className만)
         */
        private List<String> controllers;

        /**
         * 서비스 목록 (className만)
         */
        private List<String> services;

        /**
         * 리포지토리 목록 (className만)
         */
        private List<String> repositories;

        /**
         * 엔티티 목록
         */
        private List<EntityMapping> entities;

        /**
         * DB 매핑 노트
         */
        private String dbMappingNote;

        /**
         * 확신도 (0.0 ~ 1.0)
         */
        private Double confidence;

        /**
         * 추가 노트
         */
        private String note;
    }

    /**
     * 엔티티와 테이블 매핑
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityMapping {
        /**
         * 엔티티 클래스명
         */
        private String className;

        /**
         * 테이블명 (없으면 null)
         */
        private String tableName;
    }

    /**
     * 매핑되지 않은 컴포넌트
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnmappedComponents {
        /**
         * 매핑되지 않은 컨트롤러
         */
        private List<String> controllers;

        /**
         * 매핑되지 않은 서비스
         */
        private List<String> services;

        /**
         * 매핑되지 않은 리포지토리
         */
        private List<String> repositories;

        /**
         * 매핑되지 않은 엔티티
         */
        private List<String> entities;
    }
}
