package com.analyzer.rag.ai;

import com.analyzer.model.TableAccess;
import com.analyzer.rag.CodeChunk;
import com.analyzer.rag.RagService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 도메인 간 상호작용 매트릭스 서비스 (v2)
 *
 * 1. CallMatrix      – Service→Service 직접 호출 횟수 (기존)
 * 2. DataCoupling    – Domain A가 Domain B 소유 테이블에 접근한 R / CUD 횟수 (신규)
 * 3. TxCoupling      – @Transactional 스코프 내 다중 도메인 CUD → 분산 Tx 위험 (신규)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainInteractionMatrixService {

    private final DependencyGraphService dependencyGraphService;
    private final RagService ragService;
    
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

        // 1. BC → 도메인 매핑
        Map<String, String> bcToDomainMap = buildBCToDomainMapping(domains);
        // 1b. 테이블 → 소유 도메인 매핑 (domains.ownedTables 기반)
        Map<String, String> tableToOwnerDomain = buildTableToOwnerDomainMapping(domains);

        // 2. BC 간 의존성 분석 (JVM 내부 호출 포함)
        BCAnalysisResult bcAnalysis = analyzeBCDependencies(bcGroups);

        // 3. 도메인 레벨 Call 집계
        Map<String, Map<String, Integer>> domainInteractions = aggregateToDomainLevel(
                bcAnalysis.dependencies, bcToDomainMap);

        // 4. 호출 상세 (메서드명 + QUERY/COMMAND 타입)
        Map<String, Map<String, List<String>>> callDetails =
                aggregateCallDetailsToDomainLevel(bcAnalysis.callDetails, bcToDomainMap);

        // 5. DataCoupling 매트릭스
        Map<String, Map<String, DataCouplingEntry>> dataCoupling =
                computeDataCoupling(domains, bcGroups, tableToOwnerDomain);

        // 6. TxCoupling 위험 탐지
        List<TxCouplingRisk> txRisks = detectTxCouplingRisks(domains, bcGroups, tableToOwnerDomain);

        // 7. 최종 매트릭스 구성
        List<String> domainNames = domains.stream()
                .map(DomainService.Domain::getDomainName)
                .collect(Collectors.toList());

        DomainInteractionMatrix matrix = DomainInteractionMatrix.builder()
                .domainNames(domainNames)
                .interactions(domainInteractions)
                .callDetails(callDetails)
                .dataCoupling(dataCoupling)
                .txCouplingRisks(txRisks)
                .totalCommonCalls(bcAnalysis.totalCommonCalls)
                .build();

        // 8. 통계 분석
        analyzeMatrixStatistics(matrix, domains, bcAnalysis);

        log.info("도메인 매트릭스 생성 완료 (DataCoupling: {}쌍, TxRisk: {}개)",
                dataCoupling.values().stream().mapToLong(m -> m.size()).sum(), txRisks.size());
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
        Map<String, Map<String, List<String>>> callDetails; // callerBC → targetBC → [detail strings]
        int totalInternalCalls;
        int totalExternalCalls;
        int totalCommonCalls;
        Map<String, Integer> bcCommonCalls;
    }
    
    /**
     * BC 간 의존성 분석 (⭐ JVM 내부 호출 포함)
     */
    private BCAnalysisResult analyzeBCDependencies(
            List<BoundedContextGroup> bcGroups) {
        
        BCAnalysisResult result = new BCAnalysisResult();
        result.dependencies = new HashMap<>();
        result.callDetails = new HashMap<>();
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
            // callDetails: {targetBC: [detail strings]}
            if (analysis.getExternalCallDetails() != null && !analysis.getExternalCallDetails().isEmpty()) {
                result.callDetails.put(bc.getBcName(), analysis.getExternalCallDetails());
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

    // ========== 신규: 테이블 → 소유 도메인 ==========

    private Map<String, String> buildTableToOwnerDomainMapping(List<DomainService.Domain> domains) {
        Map<String, String> map = new HashMap<>();
        for (DomainService.Domain d : domains) {
            if (d.getOwnedTables() != null) {
                for (String table : d.getOwnedTables()) map.put(table.toUpperCase(), d.getDomainName());
            }
        }
        return map;
    }

    // ========== 신규: 호출 상세 도메인 집계 ==========

    private Map<String, Map<String, List<String>>> aggregateCallDetailsToDomainLevel(
            Map<String, Map<String, List<String>>> bcCallDetails, Map<String, String> bcToDomainMap) {

        Map<String, Map<String, List<String>>> result = new HashMap<>();
        if (bcCallDetails == null) return result;

        for (Map.Entry<String, Map<String, List<String>>> entry : bcCallDetails.entrySet()) {
            String callerBC = entry.getKey();
            String callerDomain = bcToDomainMap.get(callerBC);
            if (callerDomain == null) continue;

            for (Map.Entry<String, List<String>> target : entry.getValue().entrySet()) {
                String calleeBC = target.getKey();
                String calleeDomain = bcToDomainMap.get(calleeBC);
                if (calleeDomain == null || calleeDomain.equals(callerDomain)) continue;

                result.computeIfAbsent(callerDomain, k -> new HashMap<>())
                      .computeIfAbsent(calleeDomain, k -> new ArrayList<>())
                      .addAll(annotateCallType(target.getValue()));
            }
        }
        return result;
    }

    /** 메서드 호출 문자열에 [QUERY]/[COMMAND] 태그 추가 */
    private List<String> annotateCallType(List<String> rawCalls) {
        return rawCalls.stream().map(call -> {
            // "CallerClass → TargetType.methodName()" 형태에서 methodName 추출
            String methodName = "";
            int dot = call.lastIndexOf('.');
            int paren = call.indexOf('(', dot);
            if (dot >= 0 && paren > dot) methodName = call.substring(dot + 1, paren);
            String type = classifyCallType(methodName);
            return "[" + type + "] " + call;
        }).collect(Collectors.toList());
    }

    private String classifyCallType(String methodName) {
        if (methodName.isEmpty()) return "UNKNOWN";
        String lc = methodName.toLowerCase();
        if (lc.startsWith("get") || lc.startsWith("find") || lc.startsWith("list") ||
            lc.startsWith("search") || lc.startsWith("select") || lc.startsWith("fetch") ||
            lc.startsWith("count") || lc.startsWith("exists") || lc.startsWith("load") ||
            lc.startsWith("read") || lc.startsWith("query")) return "QUERY";
        return "COMMAND";
    }

    // ========== 신규: DataCouplingMatrix ==========

    /**
     * DataCouplingMatrix 계산
     * Domain A가 Domain B 소유 테이블에 접근한 R / CUD 횟수
     * CUD 가중치 = 3, R = 1
     */
    private Map<String, Map<String, DataCouplingEntry>> computeDataCoupling(
            List<DomainService.Domain> domains,
            List<BoundedContextGroup> bcGroups,
            Map<String, String> tableToOwnerDomain) {

        Map<String, Map<String, DataCouplingEntry>> result = new HashMap<>();
        Map<String, String> bcToDomainMap = buildBCToDomainMapping(domains);

        for (BoundedContextGroup bc : bcGroups) {
            String callerDomain = bcToDomainMap.get(bc.getBcName());
            if (callerDomain == null || bc.getTableAccessMap() == null) continue;

            for (Map.Entry<String, TableAccess> entry : bc.getTableAccessMap().entrySet()) {
                String table = entry.getKey().toUpperCase();
                String ownerDomain = tableToOwnerDomain.get(table);
                if (ownerDomain == null || ownerDomain.equals(callerDomain)) continue;

                TableAccess ta = entry.getValue();
                boolean hasR   = ta.getOperations().contains(TableAccess.CrudOperation.READ);
                boolean hasC   = ta.getOperations().contains(TableAccess.CrudOperation.CREATE);
                boolean hasU   = ta.getOperations().contains(TableAccess.CrudOperation.UPDATE);
                boolean hasD   = ta.getOperations().contains(TableAccess.CrudOperation.DELETE);
                int rCnt  = hasR ? 1 : 0;
                int cudCnt = (hasC ? 1 : 0) + (hasU ? 1 : 0) + (hasD ? 1 : 0);

                if (rCnt == 0 && cudCnt == 0) continue;

                DataCouplingEntry dce = result
                        .computeIfAbsent(callerDomain, k -> new HashMap<>())
                        .computeIfAbsent(ownerDomain, k -> new DataCouplingEntry());
                dce.readCount  += rCnt;
                dce.cudCount   += cudCnt;
                dce.totalWeight += rCnt + cudCnt * 3;
                dce.accessedTables.add(table + (cudCnt > 0 ? "(CUD)" : "(R)"));
            }
        }
        return result;
    }

    // ========== 신규: TxCouplingRisk ==========

    /**
     * @Transactional 스코프에서 여러 도메인 테이블에 CUD가 발생하면 경고
     * "코드 호출이 낮아도 분산 트랜잭션 위험" 탐지
     */
    private List<TxCouplingRisk> detectTxCouplingRisks(
            List<DomainService.Domain> domains,
            List<BoundedContextGroup> bcGroups,
            Map<String, String> tableToOwnerDomain) {

        List<TxCouplingRisk> risks = new ArrayList<>();
        Map<String, String> bcToDomainMap = buildBCToDomainMapping(domains);

        // CodeChunk 맵 (Service 컴포넌트만 필요)
        List<CodeChunk> allChunks = ragService.getAllChunks();
        Map<String, CodeChunk> chunkMap = new HashMap<>();
        for (CodeChunk c : allChunks) {
            chunkMap.put(c.getName(), c);
            if (c.getName().endsWith("Impl"))
                chunkMap.putIfAbsent(c.getName().substring(0, c.getName().length() - 4), c);
        }

        for (BoundedContextGroup bc : bcGroups) {
            String callerDomain = bcToDomainMap.get(bc.getBcName());
            if (callerDomain == null || bc.getTableAccessMap() == null) continue;

            // BC의 Service 컴포넌트 중 @Transactional이 있는지 확인
            boolean hasTransactional = bc.getComponents().stream().anyMatch(comp -> {
                CodeChunk chunk = chunkMap.get(comp.getName());
                return chunk != null && chunk.getAnnotations() != null &&
                       chunk.getAnnotations().stream().anyMatch(a -> a.contains("Transactional"));
            });
            if (!hasTransactional) continue;

            // 이 BC의 테이블 접근에서 외부 도메인 CUD 탐지
            Map<String, List<String>> domainCudTables = new HashMap<>();
            for (Map.Entry<String, TableAccess> entry : bc.getTableAccessMap().entrySet()) {
                String table = entry.getKey().toUpperCase();
                TableAccess ta = entry.getValue();
                boolean hasCUD = ta.getOperations().contains(TableAccess.CrudOperation.CREATE)
                        || ta.getOperations().contains(TableAccess.CrudOperation.UPDATE)
                        || ta.getOperations().contains(TableAccess.CrudOperation.DELETE);
                if (!hasCUD) continue;

                String ownerDomain = tableToOwnerDomain.get(table);
                if (ownerDomain != null && !ownerDomain.equals(callerDomain)) {
                    domainCudTables.computeIfAbsent(ownerDomain, k -> new ArrayList<>()).add(table);
                }
            }

            if (domainCudTables.isEmpty()) continue;

            // 관여된 도메인 목록
            List<String> involvedDomains = new ArrayList<>(domainCudTables.keySet());
            String riskLevel = involvedDomains.size() >= 2 ? "HIGH" : "MEDIUM";

            // 서비스 컴포넌트명 수집
            String serviceNames = bc.getComponents().stream()
                    .filter(c -> c.getType() != null && c.getType().contains("Service"))
                    .map(BoundedContextGroup.ComponentInfo::getName)
                    .collect(Collectors.joining(", "));

            TxCouplingRisk risk = new TxCouplingRisk();
            risk.setBcName(bc.getBcName());
            risk.setDomainName(callerDomain);
            risk.setServiceName(serviceNames.isEmpty() ? bc.getBcName() : serviceNames);
            risk.setInvolvedDomains(involvedDomains);
            risk.setTablesByDomain(domainCudTables.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> String.join(", ", e.getValue()))));
            risk.setRiskLevel(riskLevel);
            risks.add(risk);
        }

        risks.sort(Comparator.comparing(r -> r.riskLevel.equals("HIGH") ? 0 : 1));
        log.info("TxCoupling 위험 탐지: {}개", risks.size());
        return risks;
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
            
            // InternalCallRatio (≒ 응집도, 용어 변경)
            int totalCalls = selfCalls + outbound;
            double internalCallRatio = totalCalls > 0 ? (double) selfCalls / totalCalls : 0.0;
            stat.setInternalCallRatio(internalCallRatio);

            // 위험 분석
            List<String> risks = new ArrayList<>();

            // 낮은 InternalCallRatio (내부 호출 < 50%)
            if (internalCallRatio < 0.5 && totalCalls > 5) {
                risks.add(String.format("⚠️ 낮은 InternalCallRatio (%.0f%%) - 도메인 경계 재검토 필요", internalCallRatio * 100));
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
     * 도메인 간 상호작용 매트릭스 (v2)
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class DomainInteractionMatrix {
        private List<String> domainNames;

        /** [CallMatrix] interactions[caller][callee] = callCount (대각선=내부 호출) */
        private Map<String, Map<String, Integer>> interactions;

        /** [CallMatrix] 도메인 간 호출 메서드 상세 (caller→callee→[QUERY/COMMAND 태그+메서드]) */
        private Map<String, Map<String, List<String>>> callDetails;

        /** [DataCoupling] caller 도메인이 callee 소유 테이블에 접근한 R/CUD 횟수 */
        private Map<String, Map<String, DataCouplingEntry>> dataCoupling;

        /** [TxCoupling] @Transactional 내 다중 도메인 CUD 위험 목록 */
        private List<TxCouplingRisk> txCouplingRisks;

        private Map<String, MatrixStatistics> statistics;
        private int totalCommonCalls;
    }

    /** DataCoupling 엔트리: R 접근 횟수 / CUD 접근 횟수 / 가중 합계 */
    @Data
    public static class DataCouplingEntry {
        int readCount;
        int cudCount;
        int totalWeight;  // readCount*1 + cudCount*3
        List<String> accessedTables = new ArrayList<>();
    }

    /** TxCoupling 위험: @Transactional 내 다중 도메인 CUD */
    @Data
    public static class TxCouplingRisk {
        String bcName;
        String domainName;
        String serviceName;
        List<String> involvedDomains;           // CUD 대상 외부 도메인 목록
        Map<String, String> tablesByDomain;     // 도메인 → 테이블 목록 문자열
        String riskLevel;                       // HIGH / MEDIUM
    }

    /**
     * 도메인별 통계
     */
    @Data
    public static class MatrixStatistics {
        private String domainName;
        private int selfInvocation;
        private int outboundCalls;
        private int inboundCalls;
        private int commonCalls;
        private double internalCallRatio;   // 내부호출비율 (selfInvocation / (selfInvocation+outboundCalls))
        private List<String> risks;
    }
}
