package com.analyzer.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 도메인별 아키텍처 분석 결과
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DomainAnalysisResult {

    /**
     * 도메인 목록
     */
    private List<Domain> domains;

    /**
     * 분석 요약
     */
    private Summary summary;

    /**
     * 비즈니스 도메인
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Domain {
        /**
         * 도메인명 (한글)
         */
        private String domainName;

        /**
         * 도메인명 (영문)
         */
        private String domainNameEn;

        /**
         * 도메인 설명
         */
        private String description;

        /**
         * 도메인 내 서비스 목록
         */
        private List<ServiceInfo> services;

        /**
         * 관련 엔티티 (추론)
         */
        private List<String> relatedEntities;

        /**
         * 관련 컨트롤러
         */
        private List<String> controllers;
    }

    /**
     * 서비스 정보
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceInfo {
        /**
         * 서비스명
         */
        private String name;

        /**
         * 기능 설명
         */
        private String functionality;
    }

    /**
     * 분석 요약
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        /**
         * 총 도메인 수
         */
        private Integer totalDomains;

        /**
         * 주요 발견사항
         */
        private String keyFindings;
    }
}
