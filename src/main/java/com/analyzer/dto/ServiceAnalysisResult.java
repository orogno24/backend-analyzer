package com.analyzer.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * AI 서비스/엔티티 분석 결과 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ServiceAnalysisResult {

    /**
     * 서비스 추천 목록
     */
    private List<ServiceRecommendation> services;

    /**
     * 엔티티 추천 목록
     */
    private List<EntityRecommendation> entities;

    /**
     * 추가 질문/제안
     */
    private List<String> questions;

    /**
     * 서비스 추천
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceRecommendation {
        /**
         * 원본 클래스명
         */
        private String className;

        /**
         * 추천 한글명
         */
        private String suggestedNameKo;

        /**
         * 추천 영문명
         */
        private String suggestedNameEn;

        /**
         * 책임/역할
         */
        private String responsibility;

        /**
         * 신뢰도 (0.0 ~ 1.0)
         */
        private Double confidence;
    }

    /**
     * 엔티티 추천
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityRecommendation {
        /**
         * 원본 클래스명
         */
        private String className;

        /**
         * 테이블명
         */
        private String tableName;

        /**
         * 추천 도메인명
         */
        private String suggestedDomainName;

        /**
         * 설명
         */
        private String description;

        /**
         * 신뢰도 (0.0 ~ 1.0)
         */
        private Double confidence;
    }
}
