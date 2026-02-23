package com.analyzer.rag.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI 분석 결과: BC/도메인 추천
 * 
 * GPT 권장: 구조화된 JSON 출력
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BcRecommendation {
    
    /**
     * 분석 대상 컴포넌트
     */
    private String component;
    
    /**
     * 추천 도메인명 (한글)
     */
    private String suggestedDomain;
    
    /**
     * 추천 Bounded Context
     */
    private String suggestedBC;
    
    /**
     * 서브도메인 분류 (Core, Supporting, Generic)
     */
    private SubdomainType subdomainType;
    
    /**
     * 확신도
     */
    private ConfidenceLevel confidence;
    
    /**
     * 관련성 점수 (0.0 ~ 1.0)
     * 검색 질의와 얼마나 관련 있는가
     */
    private Double relevanceScore;
    
    /**
     * 판단 근거 리스트
     */
    private List<ReasonEvidence> reasons;
    
    /**
     * 서브도메인 타입
     */
    public enum SubdomainType {
        CORE("Core - 핵심 비즈니스"),
        SUPPORTING("Supporting - 지원"),
        GENERIC("Generic - 범용");
        
        private final String description;
        
        SubdomainType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 확신도
     */
    public enum ConfidenceLevel {
        HIGH("High", 0.8),
        MEDIUM("Medium", 0.5),
        LOW("Low", 0.3);
        
        private final String label;
        private final double threshold;
        
        ConfidenceLevel(String label, double threshold) {
            this.label = label;
            this.threshold = threshold;
        }
        
        public String getLabel() {
            return label;
        }
        
        public double getThreshold() {
            return threshold;
        }
    }
    
    /**
     * 판단 근거
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReasonEvidence {
        
        /**
         * 파일 경로
         */
        private String file;
        
        /**
         * 라인 범위 (예: "23-45")
         */
        private String lines;
        
        /**
         * 판단 이유
         */
        private String why;
    }
}
