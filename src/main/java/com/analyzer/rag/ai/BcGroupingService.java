package com.analyzer.rag.ai;

import com.analyzer.rag.CodeChunk;
import com.analyzer.rag.DependencyGraph;
import com.analyzer.rag.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * BC 그룹핑 서비스 (개선 버전)
 * 
 * 역할:
 * - 개별 컴포넌트 추천 → BC 그룹으로 묶기
 * - 같은 도메인/유사한 이름 → 하나의 BC
 * - Controller → Service → DAO까지 1-2 hop 확장 ⭐
 * - DAO/Mapper에서 accessedTables 자동 수집 ⭐
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BcGroupingService {
    
    private final DependencyGraph dependencyGraph;
    private final RagService ragService;
    
    /**
     * 추천 결과를 BC 그룹으로 묶기
     * 
     * @param recommendations 개별 컴포넌트 추천 리스트
     * @return BC 그룹 리스트
     */
    public List<BoundedContextGroup> groupByBoundedContext(List<BcRecommendation> recommendations) {
        log.info("BC 그룹핑 시작: {}개 컴포넌트", recommendations.size());
        
        // 1. 도메인명으로 1차 그룹핑
        Map<String, List<BcRecommendation>> domainGroups = recommendations.stream()
                .collect(Collectors.groupingBy(r -> 
                        normalizeDomainForGrouping(r.getSuggestedDomain())));
        
        // 2. 각 도메인 그룹을 BoundedContextGroup으로 변환
        List<BoundedContextGroup> bcGroups = new ArrayList<>();
        
        for (Map.Entry<String, List<BcRecommendation>> entry : domainGroups.entrySet()) {
            String normalizedDomain = entry.getKey();
            List<BcRecommendation> components = entry.getValue();
            
            BoundedContextGroup bcGroup = createBcGroup(normalizedDomain, components);
            bcGroups.add(bcGroup);
        }
        
        // 3. 컴포넌트 수로 정렬 (큰 BC가 먼저)
        bcGroups.sort(Comparator.comparingInt(BoundedContextGroup::getComponentCount).reversed());
        
        log.info("BC 그룹핑 완료: {}개 BC", bcGroups.size());
        
        return bcGroups;
    }
    
    /**
     * 도메인 그룹을 BoundedContextGroup으로 변환 (개선 버전)
     */
    private BoundedContextGroup createBcGroup(String domain, List<BcRecommendation> recommendations) {
        BoundedContextGroup group = new BoundedContextGroup();
        
        // 기본 정보
        group.setDomain(domain);
        group.setBcName(domain + " BC");
        
        // 1. 초기 컴포넌트 수집
        Set<String> allComponentNames = recommendations.stream()
                .map(BcRecommendation::getComponent)
                .collect(Collectors.toSet());
        
        // 2. 초기 컴포넌트의 직접 의존성 먼저 수집 ⭐
        Set<String> expandedComponentNames = new HashSet<>(allComponentNames);
        for (String componentName : allComponentNames) {
            CodeChunk chunk = getChunk(componentName);
            if (chunk != null && chunk.getDependencies() != null) {
                // Controller의 Service 의존성 추가
                for (String dep : chunk.getDependencies()) {
                    expandedComponentNames.add(dep);
                    
                    // 인터페이스명(XxxService) → 구현체명(XxxServiceImpl) 변환 시도 ⭐
                    if (dep.endsWith("Service") && !dep.endsWith("ServiceImpl")) {
                        String implName = dep + "Impl";
                        if (getChunk(implName) != null) {
                            expandedComponentNames.add(implName);
                            log.debug("인터페이스 '{}' → 구현체 '{}' 매핑", dep, implName);
                        }
                    }
                }
                log.debug("컴포넌트 '{}' 직접 의존성: {}", componentName, chunk.getDependencies());
            }
        }
        
        // 3. Dependency Graph로 1-2 hop 확장 (Service → DAO) ⭐
        Set<String> fullyExpanded = new HashSet<>(expandedComponentNames);
        for (String componentName : expandedComponentNames) {
            Set<String> related = dependencyGraph.expandDependencies(componentName, 2);
            fullyExpanded.addAll(related);
        }
        
        log.debug("BC '{}': {}개 컴포넌트 → {}개(직접 의존성) → {}개(전체 확장)", 
                domain, allComponentNames.size(), expandedComponentNames.size(), fullyExpanded.size());
        
        // 4. 확장된 컴포넌트 정보 수집
        List<BoundedContextGroup.ComponentInfo> components = new ArrayList<>();
        Set<String> allTables = new HashSet<>();
        Map<String, com.analyzer.model.TableAccess> tableAccessMap = new HashMap<>();  // ⭐ NEW
        Set<String> addedComponentNames = new HashSet<>();  // ⭐ 중복 방지용
        List<String> collectedMapperIssues = new ArrayList<>();  // ⭐ Mapper 파싱 이슈 수집
        
        for (String componentName : fullyExpanded) {
            // CodeChunk 조회
            CodeChunk chunk = getChunk(componentName);
            if (chunk == null) {
                log.debug("컴포넌트 '{}' CodeChunk를 찾을 수 없음 (스킵)", componentName);
                continue;
            }
            
            // ⭐ 컴포넌트명 정규화 (Service/Impl 통합)
            String normalizedName = normalizeComponentName(componentName);
            
            // 중복 체크
            if (addedComponentNames.contains(normalizedName)) {
                log.debug("중복 컴포넌트 스킵: {} (정규화명: {})", componentName, normalizedName);
                continue;
            }
            addedComponentNames.add(normalizedName);
            
            // ComponentInfo 추가
            components.add(BoundedContextGroup.ComponentInfo.builder()
                    .name(normalizedName)  // ⭐ 정규화된 이름 사용
                    .type(extractComponentType(normalizedName))
                    .filePath(chunk.getFilePath())
                    .build());
            
            // 테이블 수집 (기존 방식: 테이블명만) - 호환성 유지
            if (chunk.getAccessedTables() != null && !chunk.getAccessedTables().isEmpty()) {
                allTables.addAll(chunk.getAccessedTables());
            }

            // ⭐ Mapper 파싱 이슈 수집 (DAO 컴포넌트에서만)
            if (chunk.getMapperIssues() != null && !chunk.getMapperIssues().isEmpty()) {
                collectedMapperIssues.addAll(chunk.getMapperIssues());
            }
            
            // ⭐ NEW: TableAccess 수집 (CRUD 정보 포함)
            if (chunk.getTableAccesses() != null && !chunk.getTableAccesses().isEmpty()) {
                for (com.analyzer.model.TableAccess ta : chunk.getTableAccesses()) {
                    String tableName = ta.getTableName();
                    
                    // 기존 TableAccess가 있으면 CRUD 작업 병합
                    tableAccessMap.computeIfAbsent(tableName, k -> 
                        com.analyzer.model.TableAccess.builder()
                            .tableName(tableName)
                            .build()
                    );
                    
                    // CRUD 작업 병합
                    tableAccessMap.get(tableName).getOperations().addAll(ta.getOperations());
                }
            }
        }
        
        group.setComponents(components);
        group.setAccessedTables(allTables);
        group.setTableAccessMap(tableAccessMap);  // ⭐ NEW: CRUD 정보 포함
        group.setComponentCount(components.size());
        if (!collectedMapperIssues.isEmpty()) {
            group.setMapperIssues(collectedMapperIssues);
        }
        
        // ⭐ Fallback: tableAccessMap 비어있으면 accessedTables 기반으로 기본값 채우기
        if (tableAccessMap.isEmpty() && !allTables.isEmpty()) {
            log.warn("BC '{}': tableAccessMap 비어있음 - accessedTables 기반 fallback 사용 ({}개 테이블)", 
                    domain, allTables.size());
            
            for (String tableName : allTables) {
                com.analyzer.model.TableAccess ta = com.analyzer.model.TableAccess.builder()
                        .tableName(tableName)
                        .build();
                
                // 보수적 기본값: READ만 설정
                ta.addOperation(com.analyzer.model.TableAccess.CrudOperation.READ);
                
                tableAccessMap.put(tableName, ta);
            }
            group.setTableAccessMap(tableAccessMap);
        }
        
        // 5. 서브도메인 타입 (다수결)
        BcRecommendation.SubdomainType mostCommonType = recommendations.stream()
                .map(BcRecommendation::getSubdomainType)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(BcRecommendation.SubdomainType.SUPPORTING);
        group.setSubdomainType(mostCommonType);
        
        // 6. 확신도 재계산 (Service/DAO/테이블 있으면 +가점) ⭐
        BcRecommendation.ConfidenceLevel confidence = calculateConfidenceWithBonus(
                recommendations, components, allTables);
        group.setConfidence(confidence);
        
        // 7. 설명 생성
        group.setDescription(generateDescription(group));
        
        // 8. 마이크로서비스 분리 제안
        group.setMicroserviceSuggestion(generateMicroserviceSuggestion(group));
        
        log.debug("BC 그룹 생성 완료: {} ({}개 컴포넌트, {}개 테이블)", 
                domain, components.size(), allTables.size());
        
        return group;
    }
    
    /**
     * 그룹핑용 도메인명 정규화
     */
    private String normalizeDomainForGrouping(String domain) {
        if (domain == null) return "미분류";
        
        String normalized = domain.trim().toLowerCase();
        
        // 유사한 도메인 통일
        if (normalized.contains("ems") || normalized.contains("전자민원")) {
            return "EMS 관리";
        } else if (normalized.contains("user") || normalized.contains("사용자")) {
            return "사용자 관리";
        } else if (normalized.contains("api")) {
            return "API 관리";
        } else if (normalized.contains("map") || normalized.contains("지도") || normalized.contains("주제도")) {
            return "지도 관리";
        } else if (normalized.contains("bbs") || normalized.contains("게시판")) {
            return "게시판";
        } else if (normalized.contains("sys") || normalized.contains("시스템")) {
            return "시스템 관리";
        } else if (normalized.contains("app") || normalized.contains("애플리케이션")) {
            return "앱 관리";
        } else if (normalized.contains("rpt") || normalized.contains("보고서")) {
            return "보고서";
        } else if (normalized.contains("auth") || normalized.contains("인증")) {
            return "인증/인가";
        }
        
        return domain;
    }
    
    /**
     * 컴포넌트명 정규화 (Service/Impl 통합) ⭐
     */
    private String normalizeComponentName(String componentName) {
        // XxxServiceImpl → XxxService
        if (componentName.endsWith("ServiceImpl")) {
            return componentName.replace("ServiceImpl", "Service");
        }
        return componentName;
    }
    
    /**
     * 컴포넌트 타입 추출
     */
    private String extractComponentType(String componentName) {
        if (componentName.contains("Controller")) return "Controller";
        if (componentName.contains("Service")) return "Service";
        if (componentName.contains("DAO") || componentName.contains("Repository")) return "Repository";
        return "Unknown";
    }
    
    /**
     * CodeChunk 조회 (인터페이스명 → 구현체명 변환 시도) ⭐
     */
    private CodeChunk getChunk(String componentName) {
        // 1. 직접 조회
        CodeChunk chunk = ragService.getChunkByComponentName(componentName);
        if (chunk != null) {
            return chunk;
        }
        
        // 2. 인터페이스명이면 구현체명으로 시도
        if (componentName.endsWith("Service") && !componentName.endsWith("ServiceImpl")) {
            chunk = ragService.getChunkByComponentName(componentName + "Impl");
            if (chunk != null) {
                return chunk;
            }
        }
        
        // 3. DAO도 시도
        if (componentName.endsWith("DAO")) {
            // 이미 DAO로 끝나면 그대로
            return null;
        }
        
        return null;
    }
    
    /**
     * 파일 경로 추출
     */
    private String extractFilePath(BcRecommendation rec) {
        if (rec.getReasons() != null && !rec.getReasons().isEmpty()) {
            return rec.getReasons().get(0).getFile();
        }
        return null;
    }
    
    /**
     * 평균 확신도 계산
     */
    private BcRecommendation.ConfidenceLevel calculateAverageConfidence(List<BcRecommendation> recommendations) {
        Map<BcRecommendation.ConfidenceLevel, Long> counts = recommendations.stream()
                .map(BcRecommendation::getConfidence)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
        
        long highCount = counts.getOrDefault(BcRecommendation.ConfidenceLevel.HIGH, 0L);
        long mediumCount = counts.getOrDefault(BcRecommendation.ConfidenceLevel.MEDIUM, 0L);
        long lowCount = counts.getOrDefault(BcRecommendation.ConfidenceLevel.LOW, 0L);
        
        if (highCount >= recommendations.size() / 2) {
            return BcRecommendation.ConfidenceLevel.HIGH;
        } else if (mediumCount + highCount >= recommendations.size() / 2) {
            return BcRecommendation.ConfidenceLevel.MEDIUM;
        } else {
            return BcRecommendation.ConfidenceLevel.LOW;
        }
    }
    
    /**
     * 확신도 재계산 (Service/DAO/테이블 포함 시 +가점) ⭐
     */
    private BcRecommendation.ConfidenceLevel calculateConfidenceWithBonus(
            List<BcRecommendation> recommendations,
            List<BoundedContextGroup.ComponentInfo> components,
            Set<String> tables) {
        
        // 기본 확신도
        BcRecommendation.ConfidenceLevel baseConfidence = calculateAverageConfidence(recommendations);
        
        // 가점 계산
        int bonusPoints = 0;
        
        // Service 있으면 +1
        boolean hasService = components.stream()
                .anyMatch(c -> "Service".equals(c.getType()));
        if (hasService) bonusPoints++;
        
        // Repository/DAO 있으면 +1
        boolean hasRepo = components.stream()
                .anyMatch(c -> "Repository".equals(c.getType()));
        if (hasRepo) bonusPoints++;
        
        // 테이블 있으면 +1
        if (tables != null && !tables.isEmpty()) {
            bonusPoints++;
        }
        
        // 테이블이 2개 이상이면 추가 +1
        if (tables != null && tables.size() >= 2) {
            bonusPoints++;
        }
        
        // 가점 적용
        if (bonusPoints >= 3) {
            // 3점 이상: 한 단계 상승
            if (baseConfidence == BcRecommendation.ConfidenceLevel.LOW) {
                return BcRecommendation.ConfidenceLevel.MEDIUM;
            } else if (baseConfidence == BcRecommendation.ConfidenceLevel.MEDIUM) {
                return BcRecommendation.ConfidenceLevel.HIGH;
            }
        } else if (bonusPoints == 0) {
            // 가점 없으면 한 단계 하락 (Controller만 있는 경우)
            if (baseConfidence == BcRecommendation.ConfidenceLevel.HIGH) {
                return BcRecommendation.ConfidenceLevel.MEDIUM;
            } else if (baseConfidence == BcRecommendation.ConfidenceLevel.MEDIUM) {
                return BcRecommendation.ConfidenceLevel.LOW;
            }
        }
        
        return baseConfidence;
    }
    
    /**
     * BC 설명 생성 (타입별 개수)
     */
    private String generateDescription(BoundedContextGroup group) {
        int controllerCount = (int) group.getComponents().stream()
                .filter(c -> "Controller".equals(c.getType()))
                .count();
        int serviceCount = (int) group.getComponents().stream()
                .filter(c -> "Service".equals(c.getType()))
                .count();
        int repoCount = (int) group.getComponents().stream()
                .filter(c -> "Repository".equals(c.getType()))
                .count();
        
        int tableCount = group.getAccessedTables() != null ? group.getAccessedTables().size() : 0;
        
        return String.format("%s 도메인 (Controller: %d, Service: %d, Repository: %d, 테이블: %d)",
                group.getDomain(), controllerCount, serviceCount, repoCount, tableCount);
    }
    
    /**
     * 마이크로서비스 분리 제안 (개선 버전)
     */
    private String generateMicroserviceSuggestion(BoundedContextGroup group) {
        int controllerCount = (int) group.getComponents().stream()
                .filter(c -> "Controller".equals(c.getType())).count();
        int serviceCount = (int) group.getComponents().stream()
                .filter(c -> "Service".equals(c.getType())).count();
        int repoCount = (int) group.getComponents().stream()
                .filter(c -> "Repository".equals(c.getType())).count();
        int tableCount = group.getAccessedTables() != null ? group.getAccessedTables().size() : 0;
        
        // 완전한 계층 구조 (Controller + Service + DAO + 테이블)
        if (controllerCount > 0 && serviceCount > 0 && repoCount > 0 && tableCount > 0) {
            return String.format("✅ 독립 마이크로서비스로 분리 권장 (완전한 계층, 테이블 %d개 소유)", tableCount);
        }
        // Service + DAO + 테이블 (Controller 없음)
        else if (serviceCount > 0 && repoCount > 0 && tableCount > 0) {
            return String.format("⚠️ 내부 서비스 모듈 (Controller 없음, 테이블 %d개)", tableCount);
        }
        // Controller만 (Service/DAO 없음)
        else if (controllerCount > 0 && serviceCount == 0 && repoCount == 0) {
            return "⚠️ API Gateway 또는 Facade 패턴 의심 (비즈니스 로직 부재)";
        }
        // 컴포넌트 3개 이상 + Core 도메인
        else if (group.getSubdomainType() == BcRecommendation.SubdomainType.CORE && 
                 group.getComponentCount() >= 3) {
            return String.format("🔄 마이크로서비스 후보 (%d개 컴포넌트, 계층 구조 보완 필요)", group.getComponentCount());
        }
        // Supporting 도메인
        else if (group.getSubdomainType() == BcRecommendation.SubdomainType.SUPPORTING) {
            return "📚 공통 서비스 또는 라이브러리로 분리 권장 (Supporting 도메인)";
        }
        // 기타
        else {
            return "🔧 범용 유틸리티로 통합 가능 (Generic 도메인)";
        }
    }
}
