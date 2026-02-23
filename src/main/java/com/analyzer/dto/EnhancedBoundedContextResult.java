package com.analyzer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 향상된 Bounded Context 분석 결과
 * (의존성 관계 기반)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnhancedBoundedContextResult {

    private List<EnhancedContext> contexts;
    private List<CouplingIssue> couplingIssues;
    private List<MicroserviceSuggestion> microserviceSuggestions;
    private UnmappedComponents unmapped;
    private List<String> questions;
    private List<String> nextRecommendations;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EnhancedContext {
        private String domain;
        private String subdomain;
        private String description;
        private List<String> controllers;
        private List<String> services;
        private List<String> repositories;
        private List<EntityMapping> entities;
        private List<String> accessedTables;
        private List<String> callChains;
        private List<String> sharedComponents;
        private String dbMappingNote;
        private Double confidence;
        private String note;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EntityMapping {
        private String className;
        private String tableName;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CouplingIssue {
        private String component;
        private List<String> sharedBy;
        private String reason;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MicroserviceSuggestion {
        private String name;
        private List<String> boundedContexts;
        private List<String> tables;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UnmappedComponents {
        private List<String> controllers;
        private List<String> services;
        private List<String> repositories;
        private List<String> entities;
    }
}
