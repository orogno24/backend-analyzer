package com.analyzer.rag.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 분석 결과 후처리
 * 
 * 역할:
 * - 중복 제거
 * - Confidence 재계산
 * - 도메인명 정규화
 * - Relevance 점수 계산
 */
@Slf4j
@Component
public class BcResultPostProcessor {
    
    /**
     * 중복 제거 + 정규화
     */
    public List<BcRecommendation> deduplicate(List<BcRecommendation> recommendations) {
        log.info("후처리 시작: {}개 → 중복 제거", recommendations.size());
        
        // 컴포넌트명으로 그룹핑
        Map<String, List<BcRecommendation>> grouped = recommendations.stream()
                .collect(Collectors.groupingBy(BcRecommendation::getComponent));
        
        // 각 그룹에서 최선의 것만 선택
        List<BcRecommendation> deduped = grouped.values().stream()
                .map(this::selectBest)
                .collect(Collectors.toList());
        
        log.info("중복 제거 완료: {}개 → {}개", recommendations.size(), deduped.size());
        
        return deduped;
    }
    
    /**
     * 같은 컴포넌트의 여러 추천 중 최선 선택
     */
    private BcRecommendation selectBest(List<BcRecommendation> candidates) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        
        // 우선순위:
        // 1. reasons 개수 많은 것
        // 2. 도메인명 길이 적절한 것 (너무 길거나 짧지 않음)
        
        return candidates.stream()
                .max(Comparator
                        .comparingInt((BcRecommendation r) -> 
                                r.getReasons() != null ? r.getReasons().size() : 0)
                        .thenComparingInt(r -> {
                            String domain = r.getSuggestedDomain();
                            if (domain == null) return 0;
                            int len = domain.length();
                            // 5~15자가 적절
                            if (len >= 5 && len <= 15) return 10;
                            if (len < 5) return 5 - len;
                            return 20 - len;
                        }))
                .orElse(candidates.get(0));
    }
    
    /**
     * Confidence 재계산 (시스템 기반)
     */
    public void recalculateConfidence(List<BcRecommendation> recommendations) {
        log.info("Confidence 재계산 시작");
        
        for (BcRecommendation rec : recommendations) {
            int score = 0;
            
            // 1. reasons 개수 (최대 30점)
            if (rec.getReasons() != null) {
                score += Math.min(rec.getReasons().size() * 10, 30);
            }
            
            // 2. 도메인명이 한글인가? (10점)
            if (rec.getSuggestedDomain() != null && 
                rec.getSuggestedDomain().matches(".*[가-힣]+.*")) {
                score += 10;
            }
            
            // 3. BC명이 있는가? (10점)
            if (rec.getSuggestedBC() != null && !rec.getSuggestedBC().isEmpty()) {
                score += 10;
            }
            
            // 4. subdomainType이 설정됐는가? (10점)
            if (rec.getSubdomainType() != null) {
                score += 10;
            }
            
            // 점수로 confidence 재설정
            BcRecommendation.ConfidenceLevel newConfidence;
            if (score >= 50) {
                newConfidence = BcRecommendation.ConfidenceLevel.HIGH;
            } else if (score >= 30) {
                newConfidence = BcRecommendation.ConfidenceLevel.MEDIUM;
            } else {
                newConfidence = BcRecommendation.ConfidenceLevel.LOW;
            }
            
            // AI가 설정한 것과 다르면 로그
            if (rec.getConfidence() != newConfidence) {
                log.debug("Confidence 조정: {} - {} → {} (score: {})", 
                        rec.getComponent(), 
                        rec.getConfidence(), 
                        newConfidence,
                        score);
                rec.setConfidence(newConfidence);
            }
        }
        
        log.info("Confidence 재계산 완료");
    }
    
    /**
     * 도메인명 정규화
     */
    public void normalizeDomainNames(List<BcRecommendation> recommendations) {
        log.info("도메인명 정규화 시작");
        
        for (BcRecommendation rec : recommendations) {
            if (rec.getSuggestedDomain() != null) {
                String normalized = normalizeDomain(rec.getSuggestedDomain());
                if (!normalized.equals(rec.getSuggestedDomain())) {
                    log.debug("도메인명 정규화: {} → {}", 
                            rec.getSuggestedDomain(), normalized);
                    rec.setSuggestedDomain(normalized);
                }
            }
        }
        
        log.info("도메인명 정규화 완료");
    }
    
    /**
     * 도메인명 정규화 규칙
     */
    private String normalizeDomain(String domain) {
        // 1. 앞뒤 공백 제거
        String normalized = domain.trim();
        
        // 2. "관리" 통일 (관리, 管理 등)
        normalized = normalized.replaceAll("\\s*관리\\s*", " 관리");
        
        // 3. 중복 공백 제거
        normalized = normalized.replaceAll("\\s+", " ");
        
        // 4. 영어 약어 대문자로 통일 (간단 버전)
        // EMS, API 등은 이미 대문자이므로 소문자 약어만 처리
        normalized = normalized.replace("ems", "EMS")
                               .replace("api", "API")
                               .replace("bbs", "BBS")
                               .replace("app", "APP")
                               .replace("sys", "SYS")
                               .replace("pop", "POP")
                               .replace("rpt", "RPT")
                               .replace("map", "MAP")
                               .replace("mnt", "MNT");
        
        return normalized.trim();
    }
    
    /**
     * Relevance 점수 계산
     * 
     * @param query 원래 검색 질의
     */
    public void calculateRelevance(List<BcRecommendation> recommendations, String query) {
        log.info("Relevance 점수 계산 시작: query='{}'", query);
        
        String queryLower = query.toLowerCase();
        
        for (BcRecommendation rec : recommendations) {
            double score = 0.0;
            
            // 1. 컴포넌트명에 질의 키워드 포함? (40점)
            if (rec.getComponent() != null && 
                rec.getComponent().toLowerCase().contains(queryLower)) {
                score += 0.4;
            }
            
            // 2. 도메인명에 질의 키워드 포함? (30점)
            if (rec.getSuggestedDomain() != null && 
                rec.getSuggestedDomain().toLowerCase().contains(queryLower)) {
                score += 0.3;
            }
            
            // 3. BC명에 질의 키워드 포함? (20점)
            if (rec.getSuggestedBC() != null && 
                rec.getSuggestedBC().toLowerCase().contains(queryLower)) {
                score += 0.2;
            }
            
            // 4. reasons에 질의 키워드 언급? (10점)
            if (rec.getReasons() != null) {
                boolean mentioned = rec.getReasons().stream()
                        .anyMatch(r -> r.getWhy() != null && 
                                r.getWhy().toLowerCase().contains(queryLower));
                if (mentioned) {
                    score += 0.1;
                }
            }
            
            rec.setRelevanceScore(Math.min(score, 1.0));
            
            log.debug("Relevance: {} = {:.2f}", rec.getComponent(), rec.getRelevanceScore());
        }
        
        log.info("Relevance 계산 완료");
    }
    
    /**
     * Relevance 기준으로 정렬 + 필터링
     * 
     * @param threshold 최소 relevance (예: 0.3)
     */
    public List<BcRecommendation> filterByRelevance(
            List<BcRecommendation> recommendations, 
            double threshold) {
        
        return recommendations.stream()
                .filter(r -> r.getRelevanceScore() != null && 
                             r.getRelevanceScore() >= threshold)
                .sorted(Comparator.comparingDouble(BcRecommendation::getRelevanceScore).reversed())
                .collect(Collectors.toList());
    }
    
    /**
     * 전체 후처리 파이프라인
     * 
     * @param recommendations AI 원본 결과
     * @param query 원래 검색 질의
     * @return 후처리된 결과
     */
    public List<BcRecommendation> process(List<BcRecommendation> recommendations, String query) {
        if (recommendations == null || recommendations.isEmpty()) {
            return recommendations;
        }
        
        log.info("후처리 파이프라인 시작: {}개", recommendations.size());
        
        // 1. 중복 제거
        List<BcRecommendation> deduped = deduplicate(recommendations);
        
        // 2. 도메인명 정규화
        normalizeDomainNames(deduped);
        
        // 3. Confidence 재계산
        recalculateConfidence(deduped);
        
        // 4. Relevance 계산
        calculateRelevance(deduped, query);
        
        // 5. Relevance 기준 필터링 (0.2 이상만)
        List<BcRecommendation> filtered = filterByRelevance(deduped, 0.2);
        
        log.info("후처리 완료: {}개 → {}개 (relevance >= 0.2)", 
                deduped.size(), filtered.size());
        
        return filtered;
    }
}
