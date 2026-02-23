package com.analyzer.rag.ai;

import com.analyzer.model.TableAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 마이크로서비스 분리 추천 엔진
 * 
 * DB 소유권 + 코드 의존성 기반 정량 계산 ⭐
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {
    
    private final DependencyGraphService dependencyGraphService;
    
    /**
     * 테이블 타입 분류 (패턴 기반) ⭐
     * 
     * 하드코딩 대신 네이밍 패턴으로 자동 분류
     */
    private String classifyTableType(String tableName) {
        String upper = tableName.toUpperCase();
        
        // 1. ⭐ 더미 테이블 (Oracle, MySQL)
        if (upper.equals("DUAL") || upper.equals("DUMMY")) {
            return "DUMMY";
        }
        
        // 2. ⭐ 공통/레퍼런스 테이블 (패턴 기반)
        // 네이밍 규칙: T{COM|COMMON|CMN|SYS}_*, *_CODE, *_MST
        if (upper.matches("T(COM|COMMON|CMN|SYS)_.*") ||        // TCOM_*, TB_COMMON_*
            upper.matches(".*(COM|COMMON|CMN)_(CODE|USER|AUTH|MENU|AUTHOR).*") ||  // 공통 도메인
            upper.endsWith("_CODE") ||                          // *_CODE
            upper.endsWith("_MST") ||                           // *_MST (마스터)
            upper.endsWith("_CD") ||                            // *_CD
            upper.contains("_AUTHOR") ||                        // 권한
            upper.contains("_MENU") ||                          // 메뉴
            upper.contains("_PROGM") ||                         // 프로그램
            upper.matches(".*INSTT.*") ||                       // 기관 정보
            upper.matches(".*LEGALDONG.*")) {                   // 법정동
            return "REFERENCE";
        }
        
        // 3. ⭐ 이력/로그 테이블 (공유 허용)
        if (upper.endsWith("_HIST") ||                          // *_HIST
            upper.endsWith("_LOG") ||                           // *_LOG
            upper.endsWith("_HISTORY") ||                       // *_HISTORY
            upper.contains("_ATCHMNFL") ||                      // 첨부파일
            upper.contains("_FILE") ||                          // 파일
            upper.contains("_AUDIT")) {                         // 감사
            return "REFERENCE";
        }
        
        // 4. ⭐ 나머지는 트랜잭션 테이블 (공유 위험)
        return "TRANSACTION";
    }
    
    /**
     * 마이크로서비스 분리 추천 생성
     * 
     * @param bcGroups BC 그룹 목록
     * @param crudMatrix CRUD Matrix
     * @return 추천 목록 (점수 높은 순)
     */
    public List<MicroserviceRecommendation> generateRecommendations(
            List<BoundedContextGroup> bcGroups,
            CrudMatrix crudMatrix) {
        
        log.info("마이크로서비스 분리 추천 생성 시작: {}개 BC", bcGroups.size());
        
        // 1. 테이블 → BC 역매핑 (공유 테이블 감지용)
        Map<String, List<String>> tableToBC = buildTableToBCMapping(bcGroups);
        
        // 2. 각 BC별 추천 생성
        List<MicroserviceRecommendation> recommendations = new ArrayList<>();
        
        for (BoundedContextGroup bc : bcGroups) {
            MicroserviceRecommendation rec = generateRecommendationForBC(
                bc, bcGroups, crudMatrix, tableToBC);
            recommendations.add(rec);
        }
        
        // 3. 점수 순 정렬
        recommendations.sort(Comparator.comparingInt(MicroserviceRecommendation::getScore).reversed());
        
        log.info("마이크로서비스 분리 추천 완료: {}개", recommendations.size());
        
        return recommendations;
    }
    
    /**
     * 개별 BC 추천 생성 ⭐ 의존성 분석 추가
     */
    private MicroserviceRecommendation generateRecommendationForBC(
            BoundedContextGroup bc,
            List<BoundedContextGroup> allBCs,
            CrudMatrix crudMatrix,
            Map<String, List<String>> tableToBC) {
        
        String bcName = bc.getBcName();
        
        // 1. 테이블 소유권 계산
        List<MicroserviceRecommendation.TableOwnership> ownedTables = 
            calculateTableOwnership(bc);
        
        // 2. 공유 테이블 감지
        List<MicroserviceRecommendation.SharedTableInfo> sharedTables = 
            calculateSharedTables(bc, tableToBC);
        
        // 3. ⭐ BC 의존성 분석 (코드 레벨)
        DependencyGraphService.BCDependencyAnalysis depAnalysis = 
            dependencyGraphService.analyzeBCDependencies(bc, allBCs);
        
        // 4. 점수 계산 (DB + 코드 의존성)
        int score = calculateScore(bc, ownedTables, sharedTables, depAnalysis);
        
        // 5. 등급 결정
        String grade = determineGrade(score);
        
        // 6. 추천 메시지
        String recommendation = generateRecommendationMessage(grade, ownedTables.size(), sharedTables.size());
        
        // 7. 리스크 분석
        List<String> risks = analyzeRisks(bc, ownedTables, sharedTables, depAnalysis);
        
        // 8. 필수 작업
        List<String> requiredActions = generateRequiredActions(grade, sharedTables, bc);
        
        // 9. 예상 공수
        String estimatedEffort = estimateEffort(score, sharedTables.size(), bc.getComponentCount());
        
        // 10. 신뢰도 (BC 확신도 기반)
        String confidence = mapConfidence(bc.getConfidence());
        
        // 11. ⭐ 상위 외부 BC 의존 (Top 3)
        Map<String, Integer> topExternalBCs = depAnalysis.getExternalBCDependencies().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
        
        return MicroserviceRecommendation.builder()
                .bcName(bcName)
                .score(score)
                .grade(grade)
                .recommendation(recommendation)
                .ownedTables(ownedTables)
                .sharedTables(sharedTables)
                .componentCount(bc.getComponentCount())
                .risks(risks)
                .requiredActions(requiredActions)
                .estimatedEffort(estimatedEffort)
                .confidence(confidence)
                // ⭐ 의존성 정보 추가
                .internalDependencies(depAnalysis.getInternalDependencies())
                .externalDependencies(depAnalysis.getExternalDependencies())
                .cohesionScore(depAnalysis.getCohesionScore())
                .topExternalBCs(topExternalBCs)
                .build();
    }
    
    /**
     * BC의 테이블 소유권 계산
     * 
     * CRUD 강도: CREATE=3, UPDATE=2, DELETE=3, READ=1
     */
    private List<MicroserviceRecommendation.TableOwnership> calculateTableOwnership(
            BoundedContextGroup bc) {
        
        List<MicroserviceRecommendation.TableOwnership> result = new ArrayList<>();
        
        Map<String, TableAccess> tableAccessMap = bc.getTableAccessMap();
        if (tableAccessMap == null || tableAccessMap.isEmpty()) {
            return result;
        }
        
        for (Map.Entry<String, TableAccess> entry : tableAccessMap.entrySet()) {
            String tableName = entry.getKey();
            TableAccess ta = entry.getValue();
            
            // CRUD 점수 계산
            int crudScore = 0;
            List<String> operations = new ArrayList<>();
            
            for (TableAccess.CrudOperation op : ta.getOperations()) {
                operations.add(op.name());
                switch (op) {
                    case CREATE: crudScore += 3; break;
                    case UPDATE: crudScore += 2; break;
                    case DELETE: crudScore += 3; break;
                    case READ: crudScore += 1; break;
                }
            }
            
            String ownership = crudScore >= 5 ? "STRONG" : "WEAK";
            
            result.add(MicroserviceRecommendation.TableOwnership.builder()
                    .tableName(tableName)
                    .crudScore(crudScore)
                    .ownership(ownership)
                    .operations(operations)
                    .build());
        }
        
        // 점수 높은 순 정렬
        result.sort(Comparator.comparingInt(MicroserviceRecommendation.TableOwnership::getCrudScore).reversed());
        
        return result;
    }
    
    /**
     * 공유 테이블 계산 ⭐ 개선
     */
    private List<MicroserviceRecommendation.SharedTableInfo> calculateSharedTables(
            BoundedContextGroup bc,
            Map<String, List<String>> tableToBC) {
        
        List<MicroserviceRecommendation.SharedTableInfo> result = new ArrayList<>();
        
        Set<String> bcTables = bc.getAccessedTables();
        if (bcTables == null || bcTables.isEmpty()) {
            return result;
        }
        
        for (String tableName : bcTables) {
            List<String> sharedWithBCs = tableToBC.get(tableName);
            
            if (sharedWithBCs != null && sharedWithBCs.size() >= 2) {
                // 자기 제외
                List<String> others = sharedWithBCs.stream()
                        .filter(name -> !name.equals(bc.getBcName()))
                        .collect(Collectors.toList());
                
                if (!others.isEmpty()) {
                    int totalCount = sharedWithBCs.size();
                    
                    // ⭐ 테이블 타입 분류
                    String tableType = classifyTableType(tableName);
                    
                    // ⭐ 위험도 결정 (DUMMY/REFERENCE는 낮음)
                    String riskLevel;
                    if ("DUMMY".equals(tableType)) {
                        riskLevel = "REFERENCE";  // 더미는 위험 없음
                    } else if ("REFERENCE".equals(tableType)) {
                        riskLevel = "REFERENCE";  // 공통 테이블은 공유 허용
                    } else {
                        // TRANSACTION 테이블만 실제 위험도 계산
                        riskLevel = totalCount >= 5 ? "HIGH" : 
                                   totalCount >= 3 ? "MEDIUM" : "LOW";
                    }
                    
                    result.add(MicroserviceRecommendation.SharedTableInfo.builder()
                            .tableName(tableName)
                            .sharedByCount(totalCount)
                            .riskLevel(riskLevel)
                            .tableType(tableType)
                            .sharedWith(others)
                            .build());
                }
            }
        }
        
        // 위험도 높은 순 정렬
        result.sort(Comparator.comparingInt(MicroserviceRecommendation.SharedTableInfo::getSharedByCount).reversed());
        
        return result;
    }
    
    /**
     * 분리 점수 계산 (0-100) ⭐ DB + 코드 의존성 통합
     */
    private int calculateScore(
            BoundedContextGroup bc,
            List<MicroserviceRecommendation.TableOwnership> ownedTables,
            List<MicroserviceRecommendation.SharedTableInfo> sharedTables,
            DependencyGraphService.BCDependencyAnalysis depAnalysis) {
        
        int score = 0;
        
        // 1. ⭐⭐⭐ 주요 소유 테이블 (STRONG) - 최대 50점 (가장 중요!)
        long strongOwnership = ownedTables.stream()
                .filter(t -> "STRONG".equals(t.getOwnership()))
                .count();
        
        if (strongOwnership == 0) {
            // 🚨 쓰기 소유권 없음 = 마이크로서비스 분리 부적합
            score -= 20;  // 큰 페널티
            log.debug("쓰기 소유권 없음 페널티: {}", bc.getBcName());
        } else {
            score += Math.min(strongOwnership * 10, 50);  // 40점 → 50점
        }
        
        // 2. ⭐ 공유 테이블 페널티 (REFERENCE/DUMMY 제외) - 최대 -30점
        int highRiskCount = (int) sharedTables.stream()
                .filter(t -> "HIGH".equals(t.getRiskLevel()))
                .count();
        int mediumRiskCount = (int) sharedTables.stream()
                .filter(t -> "MEDIUM".equals(t.getRiskLevel()))
                .count();
        
        score -= (highRiskCount * 10 + mediumRiskCount * 5);
        score = Math.max(score, -30);  // 최대 -30점까지만
        
        // 2-1. ⭐ 읽기 전용 BC 보정 (매우 제한적)
        long totalTables = bc.getAccessedTables() != null ? bc.getAccessedTables().size() : 0;
        if (strongOwnership == 0 && totalTables >= 5) {
            // 읽기 전용 BC (Query Service 패턴) - 테이블 5개 이상만
            boolean hasReadOperations = ownedTables.stream()
                    .anyMatch(t -> t.getOperations().contains("READ"));
            
            if (hasReadOperations) {
                score += 10;  // 15점 → 10점 (보정 약화)
                log.debug("읽기 전용 BC 보정 적용 (제한적): {}", bc.getBcName());
            }
        }
        
        // 2-2. ⭐⭐ 코드 응집도 점수 (v2 추가!) - 최대 25점
        double cohesion = depAnalysis.getCohesionScore();
        if (cohesion >= 0.8) {
            score += 25;  // 매우 높은 응집도
            log.debug("높은 응집도 보너스: {} (cohesion: {})", bc.getBcName(), String.format("%.2f", cohesion));
        } else if (cohesion >= 0.6) {
            score += 15;  // 적당한 응집도
        } else if (cohesion >= 0.4) {
            score += 5;   // 낮은 응집도
        } else {
            score -= 10;  // 매우 낮은 응집도 (분리 위험)
            log.debug("낮은 응집도 페널티: {} (cohesion: {})", bc.getBcName(), String.format("%.2f", cohesion));
        }
        
        // 2-3. ⭐ 외부 의존 수 페널티 - 최대 -15점
        int externalDeps = depAnalysis.getExternalDependencies();
        if (externalDeps > 30) {
            score -= 15;  // 매우 많은 외부 의존
        } else if (externalDeps > 15) {
            score -= 10;
        } else if (externalDeps > 5) {
            score -= 5;
        }
        // else: 외부 의존 적음 (좋음)
        
        // 3. 컴포넌트 수 - 최대 30점
        int componentCount = bc.getComponentCount();
        if (componentCount >= 3 && componentCount <= 15) {
            score += Math.min(componentCount * 2, 30);
        } else if (componentCount > 15) {
            score += 15;  // 너무 많으면 오히려 감점
        } else {
            score += 10;  // 너무 적어도 감점
        }
        
        // 4. Service + DAO 완전한 계층 보너스 - 20점
        boolean hasService = bc.getComponents().stream()
                .anyMatch(c -> "Service".equals(c.getType()));
        boolean hasRepo = bc.getComponents().stream()
                .anyMatch(c -> "Repository".equals(c.getType()));
        
        if (hasService && hasRepo) {
            score += 20;
        } else if (hasService || hasRepo) {
            score += 10;
        }
        
        // 5. BC 확신도 보정 - 최대 10점
        if (bc.getConfidence() != null) {
            switch (bc.getConfidence()) {
                case HIGH: score += 10; break;
                case MEDIUM: score += 5; break;
                case LOW: score += 0; break;
            }
        }
        
        return Math.max(0, Math.min(100, score));
    }
    
    /**
     * 등급 결정 (⭐ 기준 강화)
     */
    private String determineGrade(int score) {
        if (score >= 75) return "A";  // 70 → 75 (더 엄격하게)
        if (score >= 55) return "B";  // 50 → 55
        return "C";
    }
    
    /**
     * 추천 메시지 생성 (⭐ 톤 조정)
     */
    private String generateRecommendationMessage(String grade, int ownedCount, int sharedCount) {
        switch (grade) {
            case "A":
                return String.format("✅ 분리 후보 (우선 검토 권장) - %d개 주요 테이블 소유", ownedCount);
            case "B":
                return String.format("⚠️ 리팩토링 후 분리 검토 (공유 테이블 %d개 정리 필요)", sharedCount);
            case "C":
                return "❌ 분리 부적합 (다른 BC와 통합 또는 모듈화 권장)";
            default:
                return "분석 필요";
        }
    }
    
    /**
     * 리스크 분석 ⭐ 코드 의존성 추가
     */
    private List<String> analyzeRisks(
            BoundedContextGroup bc,
            List<MicroserviceRecommendation.TableOwnership> ownedTables,
            List<MicroserviceRecommendation.SharedTableInfo> sharedTables,
            DependencyGraphService.BCDependencyAnalysis depAnalysis) {
        
        List<String> risks = new ArrayList<>();
        
        // 1. ⭐ 공유 테이블 리스크 (TRANSACTION 테이블만)
        long highRisk = sharedTables.stream()
                .filter(t -> "HIGH".equals(t.getRiskLevel()) && 
                            "TRANSACTION".equals(t.getTableType()))
                .count();
        if (highRisk > 0) {
            risks.add(String.format("트랜잭션 테이블 HIGH 위험: %d개 (5개 이상 BC가 쓰기 접근)", highRisk));
        }
        
        // 2. ⭐⭐⭐ 테이블 소유권 약함 (가장 중요한 리스크!)
        if (ownedTables.stream().noneMatch(t -> "STRONG".equals(t.getOwnership()))) {
            risks.add("🚨 주요 소유 테이블 없음 (쓰기 작업 부족 → 마이크로서비스 분리 부적합)");
        }
        
        // 3. ⭐⭐ 낮은 응집도 (코드 레벨)
        double cohesion = depAnalysis.getCohesionScore();
        if (cohesion < 0.4) {
            risks.add(String.format("낮은 코드 응집도: %.1f%% (외부 의존이 많음)", cohesion * 100));
        }
        
        // 4. ⭐ 높은 외부 의존
        int externalDeps = depAnalysis.getExternalDependencies();
        if (externalDeps > 30) {
            risks.add(String.format("높은 외부 의존: %d개 (다른 BC 호출 많음)", externalDeps));
        }
        
        // 5. BC 확신도 낮음
        if (bc.getConfidence() == BcRecommendation.ConfidenceLevel.LOW) {
            risks.add("BC 경계 확신도 낮음 (AI 분석 재검토 필요)");
        }
        
        // 6. 공용 컴포넌트 의심
        long commonComponents = bc.getComponents().stream()
                .map(c -> c.getName().toLowerCase())
                .filter(name -> name.contains("comm") || name.contains("common") || 
                               name.contains("util") || name.contains("base"))
                .count();
        if (commonComponents > 0) {
            risks.add(String.format("공용 컴포넌트 포함 의심: %d개 (BC 경계 오염 가능)", commonComponents));
        }
        
        // 7. CRUD 추출 신뢰도
        if (ownedTables.isEmpty() && !bc.getAccessedTables().isEmpty()) {
            risks.add("CRUD 정보 부족 (MyBatis XML 파싱 확인 필요)");
        }
        
        return risks;
    }
    
    /**
     * 필수 작업 생성 (⭐ 쓰기 소유권 체크 추가)
     */
    private List<String> generateRequiredActions(
            String grade,
            List<MicroserviceRecommendation.SharedTableInfo> sharedTables,
            BoundedContextGroup bc) {
        
        List<String> actions = new ArrayList<>();
        
        // ⭐ 쓰기 소유권 체크
        boolean hasStrongOwnership = bc.getTableAccessMap() != null && 
                bc.getTableAccessMap().values().stream()
                    .anyMatch(ta -> ta.getOperations().stream()
                        .anyMatch(op -> op == TableAccess.CrudOperation.CREATE || 
                                      op == TableAccess.CrudOperation.UPDATE ||
                                      op == TableAccess.CrudOperation.DELETE));
        
        if (!hasStrongOwnership) {
            actions.add("⚠️ 쓰기 소유권 확보 (현재 읽기 전용) 또는 다른 BC와 통합 검토");
        }
        
        if ("A".equals(grade)) {
            actions.add("BC 그룹핑 검토 (연관 BC와 통합 가능성)");
            actions.add("API 인터페이스 정의");
            actions.add("독립 배포 파이프라인 구성");
            if (!sharedTables.isEmpty()) {
                actions.add(String.format("공유 테이블 %d개 정리 (이벤트 소싱 또는 API 통합)", sharedTables.size()));
            }
        } else if ("B".equals(grade)) {
            actions.add("BC 경계 재검토 및 명확화");
            
            long highRisk = sharedTables.stream()
                    .filter(t -> "HIGH".equals(t.getRiskLevel()))
                    .count();
            if (highRisk > 0) {
                actions.add(String.format("HIGH 위험 공유 테이블 %d개 분리 전략 수립", highRisk));
            }
            
            actions.add("외부 의존성 제거 또는 API화");
            actions.add("컴포넌트 재배치 (공용 컴포넌트 분리)");
        } else {
            actions.add("다른 BC와 통합 (1 BC ≠ 1 Microservice)");
            actions.add("또는 모듈러 모놀리스로 내부 구조화");
        }
        
        return actions;
    }
    
    /**
     * 예상 공수 계산
     */
    private String estimateEffort(int score, int sharedTableCount, int componentCount) {
        if (score >= 70 && sharedTableCount <= 2) {
            return "2-3주";
        } else if (score >= 50) {
            return "4-6주";
        } else {
            return "8주 이상 (재설계 포함)";
        }
    }
    
    /**
     * BC 확신도 → 추천 신뢰도 매핑
     */
    private String mapConfidence(BcRecommendation.ConfidenceLevel bcConfidence) {
        if (bcConfidence == null) return "MEDIUM";
        
        switch (bcConfidence) {
            case HIGH: return "HIGH";
            case MEDIUM: return "MEDIUM";
            case LOW: return "LOW";
            default: return "MEDIUM";
        }
    }
    
    /**
     * 테이블 → BC 역매핑
     */
    private Map<String, List<String>> buildTableToBCMapping(List<BoundedContextGroup> bcGroups) {
        Map<String, List<String>> mapping = new HashMap<>();
        
        for (BoundedContextGroup bc : bcGroups) {
            if (bc.getAccessedTables() != null) {
                for (String table : bc.getAccessedTables()) {
                    mapping.computeIfAbsent(table, k -> new ArrayList<>())
                            .add(bc.getBcName());
                }
            }
        }
        
        return mapping;
    }
}
