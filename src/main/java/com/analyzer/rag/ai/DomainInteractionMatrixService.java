package com.analyzer.rag.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 도메인 간 호출 관계 매트릭스 서비스
 * 
 * 도메인 A → 도메인 B 호출 횟수 분석
 * 분산 모놀리스 위험 조기 감지
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainInteractionMatrixService {
    
    private final DependencyGraphService dependencyGraphService;
    
    /**
     * 도메인 간 호출 관계 매트릭스 생성
     * 
     * @param domains 도메인 목록
     * @param bcGroups BC 그룹 목록
     * @return 도메인 간 호출 매트릭스
     */
    public DomainInteractionMatrix createMatrix(
            List<DomainService.Domain> domains,
            List<BoundedContextGroup> bcGroups) {
        
        log.info("도메인 간 호출 관계 매트릭스 생성 - 도메인: {}개", domains.size());
        
        // 1. BC → 도메인 매핑 생성
        Map<String, String> bcToDomainMap = buildBCToDomainMapping(domains);
        
        // 2. BC 간 의존성 분석 (⭐ JVM 내부 호출 포함)
        BCAnalysisResult bcAnalysis = analyzeBCDependencies(bcGroups);
        
        // 3. 도메인 레벨로 집계
        Map<String, Map<String, Integer>> domainInteractions = aggregateToDomainLevel(
                bcAnalysis.dependencies, bcToDomainMap);
        
        // 4. 매트릭스 구조 생성
        List<String> domainNames = domains.stream()
                .map(DomainService.Domain::getDomainName)
                .collect(Collectors.toList());
        
        DomainInteractionMatrix matrix = DomainInteractionMatrix.builder()
                .domainNames(domainNames)
                .interactions(domainInteractions)
                .totalCommonCalls(bcAnalysis.totalCommonCalls)
                .build();
        
        // 5. 통계 및 위험 분석
        analyzeMatrixStatistics(matrix, domains, bcAnalysis);
        
        log.info("도메인 매트릭스 생성 완료");
        return matrix;
    }
    
    /**
     * BC → 도메인 매핑 생성
     */
    private Map<String, String> buildBCToDomainMapping(List<DomainService.Domain> domains) {
        Map<String, String> mapping = new HashMap<>();
        
        for (DomainService.Domain domain : domains) {
            for (String bcName : domain.getBcNames()) {
                mapping.put(bcName, domain.getDomainName());
            }
        }
        
        log.debug("BC → 도메인 매핑: {}개", mapping.size());
        return mapping;
    }
    
    /**
     * BC 분석 결과 (내부용)
     */
    private static class BCAnalysisResult {
        Map<String, Map<String, Integer>> dependencies;
        int totalInternalCalls;
        int totalExternalCalls;
        int totalCommonCalls;
        Map<String, Integer> bcCommonCalls;  // BC별 공통 호출 수
    }
    
    /**
     * BC 간 의존성 분석 (⭐ JVM 내부 호출 포함)
     */
    private BCAnalysisResult analyzeBCDependencies(
            List<BoundedContextGroup> bcGroups) {
        
        BCAnalysisResult result = new BCAnalysisResult();
        result.dependencies = new HashMap<>();
        result.bcCommonCalls = new HashMap<>();
        
        for (BoundedContextGroup bc : bcGroups) {
            result.dependencies.putIfAbsent(bc.getBcName(), new HashMap<>());
            
            // DependencyGraphService를 통해 BC 간 의존성 가져오기
            DependencyGraphService.BCDependencyAnalysis analysis = 
                    dependencyGraphService.analyzeBCDependencies(bc, bcGroups);
            
            result.totalInternalCalls += analysis.getInternalDependencies();
            result.totalExternalCalls += analysis.getExternalDependencies();
            result.totalCommonCalls += analysis.getCommonDependencies();
            result.bcCommonCalls.put(bc.getBcName(), analysis.getCommonDependencies());
            
            // externalBCDependencies: {targetBC: callCount}
            if (analysis.getExternalBCDependencies() != null) {
                result.dependencies.get(bc.getBcName()).putAll(analysis.getExternalBCDependencies());
            }
            
            // ⭐ 내부 호출도 self-호출로 기록 (대각선 값용)
            if (analysis.getInternalDependencies() > 0) {
                result.dependencies.get(bc.getBcName())
                        .merge(bc.getBcName(), analysis.getInternalDependencies(), Integer::sum);
            }
        }
        
        log.info("BC 의존성 분석 완료: {}개 BC (내부호출: {}, 외부호출: {}, 공통호출: {})", 
                result.dependencies.size(), result.totalInternalCalls, 
                result.totalExternalCalls, result.totalCommonCalls);
        return result;
    }
    
    /**
     * BC 레벨 의존성을 도메인 레벨로 집계
     */
    private Map<String, Map<String, Integer>> aggregateToDomainLevel(
            Map<String, Map<String, Integer>> bcDependencies,
            Map<String, String> bcToDomainMap) {
        
        Map<String, Map<String, Integer>> domainInteractions = new HashMap<>();
        
        for (Map.Entry<String, Map<String, Integer>> entry : bcDependencies.entrySet()) {
            String callerBC = entry.getKey();
            String callerDomain = bcToDomainMap.get(callerBC);
            
            if (callerDomain == null) {
                log.warn("BC '{}' 도메인 매핑 없음", callerBC);
                continue;
            }
            
            domainInteractions.putIfAbsent(callerDomain, new HashMap<>());
            
            for (Map.Entry<String, Integer> dep : entry.getValue().entrySet()) {
                String calleeBC = dep.getKey();
                Integer callCount = dep.getValue();
                
                String calleeDomain = bcToDomainMap.get(calleeBC);
                
                if (calleeDomain == null) {
                    log.warn("BC '{}' 도메인 매핑 없음", calleeBC);
                    continue;
                }
                
                // 도메인 레벨 집계
                domainInteractions.get(callerDomain).merge(calleeDomain, callCount, Integer::sum);
            }
        }
        
        log.debug("도메인 레벨 집계 완료: {}개 도메인", domainInteractions.size());
        return domainInteractions;
    }
    
    /**
     * 매트릭스 통계 및 위험 분석
     */
    private void analyzeMatrixStatistics(
            DomainInteractionMatrix matrix,
            List<DomainService.Domain> domains,
            BCAnalysisResult bcAnalysis) {
        
        Map<String, MatrixStatistics> stats = new HashMap<>();
        
        // ⭐ BC별 공통 호출 → 도메인별로 집계
        Map<String, Integer> domainCommonCalls = new HashMap<>();
        if (bcAnalysis.bcCommonCalls != null) {
            // BC → 도메인 매핑 생성
            Map<String, String> bcToDomain = new HashMap<>();
            for (DomainService.Domain d : domains) {
                for (String bcName : d.getBcNames()) {
                    bcToDomain.put(bcName, d.getDomainName());
                }
            }
            for (Map.Entry<String, Integer> e : bcAnalysis.bcCommonCalls.entrySet()) {
                String domain = bcToDomain.get(e.getKey());
                if (domain != null) {
                    domainCommonCalls.merge(domain, e.getValue(), Integer::sum);
                }
            }
        }
        
        for (String domainName : matrix.getDomainNames()) {
            MatrixStatistics stat = new MatrixStatistics();
            stat.setDomainName(domainName);
            
            // Self-invocation (대각선)
            int selfCalls = matrix.getInteractions()
                    .getOrDefault(domainName, Collections.emptyMap())
                    .getOrDefault(domainName, 0);
            stat.setSelfInvocation(selfCalls);
            
            // Outbound calls (행 합계, 자기 자신 제외)
            int outbound = matrix.getInteractions()
                    .getOrDefault(domainName, Collections.emptyMap())
                    .entrySet().stream()
                    .filter(e -> !e.getKey().equals(domainName))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
            stat.setOutboundCalls(outbound);
            
            // Inbound calls (열 합계, 자기 자신 제외)
            int inbound = matrix.getInteractions().values().stream()
                    .mapToInt(m -> m.getOrDefault(domainName, 0))
                    .sum() - selfCalls;
            stat.setInboundCalls(inbound);
            
            // ⭐ 공통/유틸 호출 수
            stat.setCommonCalls(domainCommonCalls.getOrDefault(domainName, 0));
            
            // 응집도 계산
            int totalCalls = selfCalls + outbound;
            double cohesion = totalCalls > 0 ? (double) selfCalls / totalCalls : 0.0;
            stat.setCohesion(cohesion);
            
            // 위험 분석
            List<String> risks = new ArrayList<>();
            
            // 낮은 응집도 (내부 호출 < 50%)
            if (cohesion < 0.5 && totalCalls > 5) {
                risks.add(String.format("⚠️ 낮은 응집도 (%.0f%%) - 도메인 경계 재검토 필요", cohesion * 100));
            }
            
            // 높은 외부 의존 (outbound > 10)
            if (outbound > 10) {
                risks.add(String.format("🚨 높은 외부 의존 (%d회) - 강결합 위험", outbound));
            }
            
            // 특정 도메인과 강결합 (한 도메인에 5회 이상)
            matrix.getInteractions()
                    .getOrDefault(domainName, Collections.emptyMap())
                    .forEach((target, count) -> {
                        if (!target.equals(domainName) && count >= 5) {
                            risks.add(String.format("🔗 '%s'와 강결합 (%d회) - 통합 검토", target, count));
                        }
                    });
            
            stat.setRisks(risks);
            
            stats.put(domainName, stat);
        }
        
        matrix.setStatistics(stats);
        
        log.info("매트릭스 통계 분석 완료");
    }
    
    /**
     * 도메인 간 호출 관계 매트릭스
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class DomainInteractionMatrix {
        private List<String> domainNames;
        
        /**
         * interactions[caller][callee] = callCount
         * 
         * Caller: 호출을 보내는 도메인 (행)
         * Callee: 호출을 받는 도메인 (열)
         * 대각선 값: 내부 호출 (JVM 내부 Service→Service)
         */
        private Map<String, Map<String, Integer>> interactions;
        
        /**
         * 도메인별 통계
         */
        private Map<String, MatrixStatistics> statistics;
        
        /**
         * 전체 통계
         */
        private int totalCommonCalls;  // 공통/유틸 호출 수 (노이즈 분리)
    }
    
    /**
     * 도메인별 통계
     */
    @Data
    public static class MatrixStatistics {
        private String domainName;
        private int selfInvocation;      // 내부 호출 (대각선)
        private int outboundCalls;       // 외부로 보내는 호출
        private int inboundCalls;        // 외부에서 받는 호출
        private int commonCalls;         // 공통/유틸 호출 수
        private double cohesion;         // 응집도 (selfInvocation / (selfInvocation + outboundCalls))
        private List<String> risks;      // 위험 요소
    }
}
