package com.analyzer.rag.ai;

import com.analyzer.model.TableAccess;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 도메인 서비스 (Domain Service)
 * 
 * BC 그룹핑 → 도메인 서비스 추천
 * 23개 BC → 5~8개 도메인 서비스 제안
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainService {
    
    /**
     * 공통 테이블 제외 패턴 (⭐ 매우 중요!)
     * 
     * 이 테이블들은 BC 묶기 신호로 사용하면 안 됨
     * (전체 시스템이 하나로 연결되어 버림)
     */
    private boolean isCommonTable(String tableName) {
        String upper = tableName.toUpperCase();
        
        // 1. 더미 테이블
        if (upper.equals("DUAL") || upper.equals("DUMMY")) {
            return true;
        }
        
        // 2. 공통/시스템 테이블 (TCOM_, TIFS_, TB_COMMON_, CMN_, SYS_)
        if (upper.startsWith("TCOM_") || 
            upper.startsWith("TIFS_") || 
            upper.startsWith("TB_COMMON_") ||
            upper.startsWith("CMN_") ||
            upper.startsWith("SYS_")) {
            return true;
        }
        
        // 3. 코드/마스터 테이블
        if (upper.endsWith("_CODE") || 
            upper.endsWith("_MST") || 
            upper.endsWith("_CD") ||
            upper.contains("_CODE_")) {
            return true;
        }
        
        // 4. 이력/로그/감사 테이블
        if (upper.endsWith("_HIST") || 
            upper.endsWith("_LOG") || 
            upper.endsWith("_HISTORY") ||
            upper.endsWith("_AUDIT") ||
            upper.contains("_ATCHMNFL") ||  // 첨부파일
            upper.contains("_FILE")) {
            return true;
        }
        
        // 5. 권한/메뉴 테이블
        if (upper.contains("_AUTHOR") || 
            upper.contains("_MENU") || 
            upper.contains("_PROGM")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * BC를 도메인 서비스로 묶기
     * 
     * @param bcGroups BC 목록
     * @param targetDomainCount 목표 도메인 개수 (기본: 6개)
     * @return 도메인 목록
     */
    public List<Domain> createDomains(
            List<BoundedContextGroup> bcGroups, 
            int targetDomainCount) {
        
        log.info("도메인 생성 시작: {}개 BC → {}개 도메인 목표", bcGroups.size(), targetDomainCount);
        
        // 1. 공통 테이블 제외
        Set<String> significantTables = extractSignificantTables(bcGroups);
        log.info("유의미한 테이블: {}개 (공통 테이블 제외)", significantTables.size());
        
        // 2. 테이블별 주 소유 BC 결정
        Map<String, String> tableOwners = determineTableOwners(bcGroups, significantTables);
        
        // 3. BC 간 연결 강도 계산
        Map<String, Map<String, Double>> connections = calculateBCConnections(
                bcGroups, significantTables, tableOwners);
        
        // 4. 클러스터링 (그래프 기반)
        List<Set<String>> clusters = clusterBCs(bcGroups, connections, targetDomainCount);
        
        // 5. 도메인 생성
        List<Domain> domains = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            Set<String> clusterBCs = clusters.get(i);
            Domain domain = createDomain(
                    i + 1, clusterBCs, bcGroups, significantTables, tableOwners);
            domains.add(domain);
        }
        
        log.info("도메인 생성 완료: {}개 도메인", domains.size());
        return domains;
    }
    
    /**
     * 공통 테이블 제외 (⭐ 매우 중요!)
     */
    private Set<String> extractSignificantTables(List<BoundedContextGroup> bcGroups) {
        Set<String> allTables = new HashSet<>();
        
        for (BoundedContextGroup bc : bcGroups) {
            if (bc.getAccessedTables() != null) {
                allTables.addAll(bc.getAccessedTables());
            }
        }
        
        // ⭐ 공통 테이블 제외 (패턴 기반)
        Set<String> significantTables = allTables.stream()
                .filter(table -> !isCommonTable(table))
                .collect(Collectors.toSet());
        
        log.info("유의미한 테이블 추출: 전체 {}개 → 유의미 {}개 (공통 테이블 {}개 제외)", 
                allTables.size(), significantTables.size(), allTables.size() - significantTables.size());
        
        return significantTables;
    }
    
    /**
     * 테이블별 주 소유 BC 결정
     * C=3, U=2, D=3, R=1 점수로 소유자 결정
     */
    private Map<String, String> determineTableOwners(
            List<BoundedContextGroup> bcGroups,
            Set<String> significantTables) {
        
        Map<String, String> owners = new HashMap<>();
        
        for (String table : significantTables) {
            String owner = null;
            int maxScore = 0;
            
            for (BoundedContextGroup bc : bcGroups) {
                if (bc.getTableAccessMap() == null) continue;
                
                TableAccess ta = bc.getTableAccessMap().get(table);
                if (ta == null) continue;
                
                int score = 0;
                for (TableAccess.CrudOperation op : ta.getOperations()) {
                    switch (op) {
                        case CREATE: score += 3; break;
                        case UPDATE: score += 2; break;
                        case DELETE: score += 3; break;
                        case READ: score += 1; break;
                    }
                }
                
                if (score > maxScore) {
                    maxScore = score;
                    owner = bc.getBcName();
                }
            }
            
            if (owner != null) {
                owners.put(table, owner);
            }
        }
        
        log.debug("테이블 소유자 결정: {}개 테이블", owners.size());
        return owners;
    }
    
    /**
     * BC 간 연결 강도 계산 (⭐⭐⭐ DB + 도메인 의미 통합)
     */
    private Map<String, Map<String, Double>> calculateBCConnections(
            List<BoundedContextGroup> bcGroups,
            Set<String> significantTables,
            Map<String, String> tableOwners) {
        
        Map<String, Map<String, Double>> connections = new HashMap<>();
        
        for (BoundedContextGroup bc1 : bcGroups) {
            connections.putIfAbsent(bc1.getBcName(), new HashMap<>());
            
            for (BoundedContextGroup bc2 : bcGroups) {
                if (bc1.getBcName().equals(bc2.getBcName())) continue;
                
                double strength = 0.0;
                
                Set<String> sharedTables = getSharedTables(bc1, bc2, significantTables);
                
                // 1️⃣ DB 기반 연결 강도
                for (String table : sharedTables) {
                    boolean bc1HasWrite = hasWriteOperation(bc1, table);
                    boolean bc2HasWrite = hasWriteOperation(bc2, table);
                    
                    if (bc1HasWrite && bc2HasWrite) {
                        // C/U/D 공유 = 매우 강한 트랜잭션 결합!
                        strength += 20.0;
                        log.debug("강한 결합 발견: {} <-> {} via {} (C/U/D 공유)", 
                                bc1.getBcName(), bc2.getBcName(), table);
                    } else if (bc1HasWrite || bc2HasWrite) {
                        // 한쪽만 쓰기 = 중간 강도
                        strength += 5.0;
                    } else {
                        // 둘 다 READ만 = 약한 연결
                        strength += 1.0;
                    }
                    
                    // 동일 소유자 테이블 보너스
                    String owner = tableOwners.get(table);
                    if (owner != null && 
                        (owner.equals(bc1.getBcName()) || owner.equals(bc2.getBcName()))) {
                        strength += 5.0;
                    }
                }
                
                // 2️⃣ ⭐⭐⭐ 도메인 키워드 유사도 (NEW!)
                double domainSimilarity = calculateDomainSimilarity(bc1, bc2);
                strength += domainSimilarity * 10.0;  // 최대 +10점
                
                if (strength > 0) {
                    connections.get(bc1.getBcName()).put(bc2.getBcName(), strength);
                }
            }
        }
        
        log.info("BC 연결 강도 계산 완료 (DB + 도메인 의미)");
        return connections;
    }
    
    /**
     * 도메인 키워드 유사도 계산 ⭐⭐⭐
     * 
     * BC 이름/도메인에서 공통 키워드 추출
     */
    private double calculateDomainSimilarity(BoundedContextGroup bc1, BoundedContextGroup bc2) {
        // 핵심 도메인 키워드
        String[] domainKeywords = {
            "사용자", "user", "인증", "auth", "로그인", "login",
            "지도", "map", "tmtmap", "gis",
            "API", "api", "relay", "연동",
            "앱", "app", "sdk", "알림", "push", "notification",
            "운영", "관리", "admin", "system", "sys",
            "보고서", "report", "rpt", "통계", "stats",
            "게시판", "bbs", "board", "콘텐츠", "content",
            "검색", "search", "조회", "query",
            "EMS", "ems", "모니터링", "monitoring"
        };
        
        Set<String> bc1Keywords = extractKeywords(bc1.getBcName(), bc1.getDomain(), domainKeywords);
        Set<String> bc2Keywords = extractKeywords(bc2.getBcName(), bc2.getDomain(), domainKeywords);
        
        // Jaccard 유사도
        Set<String> intersection = new HashSet<>(bc1Keywords);
        intersection.retainAll(bc2Keywords);
        
        Set<String> union = new HashSet<>(bc1Keywords);
        union.addAll(bc2Keywords);
        
        if (union.isEmpty()) return 0.0;
        
        double similarity = (double) intersection.size() / union.size();
        
        if (similarity > 0) {
            log.debug("도메인 유사도: {} <-> {} = {:.2f} (공통: {})", 
                    bc1.getBcName(), bc2.getBcName(), similarity, intersection);
        }
        
        return similarity;
    }
    
    /**
     * 키워드 추출
     */
    private Set<String> extractKeywords(String bcName, String domain, String[] keywords) {
        Set<String> result = new HashSet<>();
        String text = (bcName + " " + (domain != null ? domain : "")).toLowerCase();
        
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) {
                result.add(keyword.toLowerCase());
            }
        }
        
        return result;
    }
    
    /**
     * BC 클러스터링 (간단한 Greedy 알고리즘)
     */
    private List<Set<String>> clusterBCs(
            List<BoundedContextGroup> bcGroups,
            Map<String, Map<String, Double>> connections,
            int targetCount) {
        
        List<Set<String>> clusters = new ArrayList<>();
        Set<String> assigned = new HashSet<>();
        
        // BC를 연결 강도 순으로 정렬
        List<String> bcNames = bcGroups.stream()
                .map(BoundedContextGroup::getBcName)
                .collect(Collectors.toList());
        
        while (clusters.size() < targetCount && assigned.size() < bcNames.size()) {
            Set<String> cluster = new HashSet<>();
            
            // 아직 할당되지 않은 BC 중 시작점 선택
            String seed = bcNames.stream()
                    .filter(bc -> !assigned.contains(bc))
                    .findFirst()
                    .orElse(null);
            
            if (seed == null) break;
            
            cluster.add(seed);
            assigned.add(seed);
            
            // ⭐ 연결된 BC 추가 (강한 연결부터, 최대 5개 제한)
            while (cluster.size() < 5) {  // 최대 5개 BC
                String bestCandidate = null;
                double bestStrength = 0.0;
                
                for (String bcInCluster : cluster) {
                    Map<String, Double> conns = connections.get(bcInCluster);
                    if (conns == null) continue;
                    
                    for (Map.Entry<String, Double> entry : conns.entrySet()) {
                        String candidate = entry.getKey();
                        if (assigned.contains(candidate)) continue;
                        
                        if (entry.getValue() > bestStrength) {
                            bestStrength = entry.getValue();
                            bestCandidate = candidate;
                        }
                    }
                }
                
                // ⭐ 최소 연결 강도 (DB 기반 10.0, 도메인 유사도로 보완 가능)
                if (bestCandidate == null || bestStrength < 10.0) break;
                
                cluster.add(bestCandidate);
                assigned.add(bestCandidate);
            }
            
            clusters.add(cluster);
        }
        
        // ⭐⭐⭐ 남은 BC 처리 (도메인 키워드 기반 2차 그룹핑)
        Set<String> orphanBCs = bcNames.stream()
                .filter(bc -> !assigned.contains(bc))
                .collect(Collectors.toSet());
        
        if (!orphanBCs.isEmpty()) {
            log.info("고립된 BC: {}개 → 도메인 키워드 기반 2차 그룹핑", orphanBCs.size());
            
            // 도메인 키워드 기반 그룹핑
            List<BoundedContextGroup> orphanBCGroups = bcGroups.stream()
                    .filter(bc -> orphanBCs.contains(bc.getBcName()))
                    .collect(Collectors.toList());
            
            Map<String, Set<String>> domainGroups = groupByDomainKeywords(orphanBCGroups);
            
            // 각 도메인 그룹을 클러스터로 추가
            for (Map.Entry<String, Set<String>> entry : domainGroups.entrySet()) {
                Set<String> group = entry.getValue();
                
                // 최대 5개씩만 묶기
                List<Set<String>> subGroups = splitLargeGroup(group, 5);
                clusters.addAll(subGroups);
            }
        }
        
        return clusters;
    }
    
    /**
     * 도메인 키워드로 BC 그룹핑 ⭐⭐⭐
     */
    private Map<String, Set<String>> groupByDomainKeywords(List<BoundedContextGroup> bcs) {
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        groups.put("사용자/인증", new HashSet<>());
        groups.put("앱/SDK", new HashSet<>());
        groups.put("운영/시스템", new HashSet<>());
        groups.put("조회/검색", new HashSet<>());
        groups.put("기타", new HashSet<>());
        
        for (BoundedContextGroup bc : bcs) {
            String bcName = bc.getBcName().toLowerCase();
            String domain = (bc.getDomain() != null ? bc.getDomain() : "").toLowerCase();
            String text = bcName + " " + domain;
            
            boolean categorized = false;
            
            // 사용자/인증
            if (text.contains("사용자") || text.contains("user") || 
                text.contains("로그인") || text.contains("login") || 
                text.contains("인증") || text.contains("auth") ||
                text.contains("회원") || text.contains("계약")) {
                groups.get("사용자/인증").add(bc.getBcName());
                categorized = true;
            }
            // 앱/SDK
            else if (text.contains("앱") || text.contains("app") || 
                     text.contains("sdk") || text.contains("알림") || 
                     text.contains("push") || text.contains("팝업")) {
                groups.get("앱/SDK").add(bc.getBcName());
                categorized = true;
            }
            // 운영/시스템
            else if (text.contains("운영") || text.contains("시스템") || 
                     text.contains("system") || text.contains("admin") ||
                     text.contains("ems") || text.contains("커뮤니케이션") ||
                     text.contains("신청서")) {
                groups.get("운영/시스템").add(bc.getBcName());
                categorized = true;
            }
            // 조회/검색
            else if (text.contains("조회") || text.contains("검색") || 
                     text.contains("search") || text.contains("보고서") ||
                     text.contains("report") || text.contains("법률") ||
                     text.contains("인스타")) {
                groups.get("조회/검색").add(bc.getBcName());
                categorized = true;
            }
            
            if (!categorized) {
                groups.get("기타").add(bc.getBcName());
            }
        }
        
        // 빈 그룹 제거
        groups.entrySet().removeIf(e -> e.getValue().isEmpty());
        
        log.info("도메인 키워드 그룹핑: {}", groups.keySet());
        return groups;
    }
    
    /**
     * 큰 그룹 분할 (최대 N개씩)
     */
    private List<Set<String>> splitLargeGroup(Set<String> group, int maxSize) {
        List<Set<String>> result = new ArrayList<>();
        
        if (group.size() <= maxSize) {
            result.add(group);
            return result;
        }
        
        // 큰 그룹을 최대 크기로 분할
        List<String> list = new ArrayList<>(group);
        for (int i = 0; i < list.size(); i += maxSize) {
            Set<String> subGroup = new HashSet<>(list.subList(i, Math.min(i + maxSize, list.size())));
            result.add(subGroup);
        }
        
        return result;
    }
    
    /**
     * 도메인 생성 (⭐ 이름 생성 개선)
     */
    private Domain createDomain(
            int domainId,
            Set<String> bcNames,
            List<BoundedContextGroup> allBCs,
            Set<String> significantTables,
            Map<String, String> tableOwners) {
        
        List<BoundedContextGroup> domainBCs = allBCs.stream()
                .filter(bc -> bcNames.contains(bc.getBcName()))
                .collect(Collectors.toList());
        
        // ⭐ 도메인 이름 생성 (스마트)
        String domainName = generateDomainName(domainId, domainBCs, tableOwners);
        
        // 소유 테이블
        Set<String> ownedTables = new HashSet<>();
        for (BoundedContextGroup bc : domainBCs) {
            for (Map.Entry<String, String> entry : tableOwners.entrySet()) {
                if (entry.getValue().equals(bc.getBcName())) {
                    ownedTables.add(entry.getKey());
                }
            }
        }
        
        // 공유 테이블 (도메인 내부 BC 간)
        Set<String> internalSharedTables = new HashSet<>();
        for (String table : significantTables) {
            long accessCount = domainBCs.stream()
                    .filter(bc -> bc.getAccessedTables() != null && 
                                 bc.getAccessedTables().contains(table))
                    .count();
            
            if (accessCount >= 2) {
                internalSharedTables.add(table);
            }
        }
        
        // ⭐ 리스크 분석 강화
        List<String> risks = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        
        // 내부 공유 테이블 리스크
        if (internalSharedTables.size() > 5) {
            risks.add(String.format("도메인 내부 공유 테이블 많음: %d개 (트랜잭션 조율 필요)", internalSharedTables.size()));
            recommendations.add("Saga 패턴 또는 2PC 검토");
        } else if (internalSharedTables.size() > 0) {
            recommendations.add(String.format("내부 공유 테이블 %d개 트랜잭션 전략 수립", internalSharedTables.size()));
        }
        
        // BC 개수 리스크
        if (domainBCs.size() > 6) {
            risks.add("BC 개수 많음 (향후 2차 분리 고려)");
            recommendations.add("모듈러 모놀리스로 시작 → 점진적 분리");
        } else if (domainBCs.size() == 1) {
            recommendations.add("단일 BC → 독립 마이크로서비스 적합");
        }
        
        // 읽기 전용 BC 많음
        long readOnlyCount = domainBCs.stream()
                .filter(bc -> bc.getTableAccessMap() == null || 
                             bc.getTableAccessMap().values().stream()
                                .noneMatch(ta -> ta.getOperations().stream()
                                    .anyMatch(op -> op == TableAccess.CrudOperation.CREATE ||
                                                  op == TableAccess.CrudOperation.UPDATE ||
                                                  op == TableAccess.CrudOperation.DELETE)))
                .count();
        
        if (readOnlyCount > domainBCs.size() / 2) {
            recommendations.add("읽기 전용 중심 → CQRS 패턴 검토");
        }
        
        // 소유 테이블 없음
        if (ownedTables.isEmpty()) {
            risks.add("소유 테이블 없음 (읽기 전용 또는 공통 서비스)");
            
            // 외부연동/조회 도메인은 읽기 전용이 정상
            if (!domainName.contains("외부연동") && !domainName.contains("조회")) {
                recommendations.add("다른 도메인과 통합 또는 공통 라이브러리로 전환");
            }
        }
        
        // 도메인 이름별 특화 가이드
        if (domainName.contains("외부연동") || domainName.contains("조회")) {
            recommendations.add("BFF(Backend For Frontend) 패턴 검토");
            recommendations.add("캐싱 전략 수립 (읽기 부하 분산)");
        } else if (domainName.contains("사용자") || domainName.contains("인증")) {
            recommendations.add("SSO/OAuth 통합 인증 시스템 구축");
            recommendations.add("GDPR/개인정보 보호 정책 적용");
        } else if (domainName.contains("앱") || domainName.contains("SDK")) {
            recommendations.add("버전 관리 전략 수립");
            recommendations.add("SDK 배포 자동화");
        }
        
        return Domain.builder()
                .domainId(domainId)
                .domainName(domainName)
                .bcNames(new ArrayList<>(bcNames))
                .bcCount(bcNames.size())
                .ownedTables(new ArrayList<>(ownedTables))
                .internalSharedTables(new ArrayList<>(internalSharedTables))
                .risks(risks)
                .recommendations(recommendations)
                .build();
    }
    
    /**
     * 스마트 도메인 이름 생성 ⭐⭐⭐ (도메인 키워드 우선)
     */
    private String generateDomainName(
            int domainId,
            List<BoundedContextGroup> domainBCs,
            Map<String, String> tableOwners) {
        
        if (domainBCs.isEmpty()) {
            return "서비스 #" + domainId;
        }
        
        // ⭐ BC 이름에서 공통 키워드 추출
        Map<String, Integer> keywordCount = new HashMap<>();
        
        for (BoundedContextGroup bc : domainBCs) {
            String text = (bc.getBcName() + " " + (bc.getDomain() != null ? bc.getDomain() : "")).toLowerCase();
            
            // 핵심 키워드 카운트
            if (text.contains("사용자") || text.contains("user") || text.contains("로그인") || text.contains("회원")) {
                keywordCount.merge("사용자/인증", 1, Integer::sum);
            }
            if (text.contains("지도") || text.contains("map") || text.contains("gis") || text.contains("tmtmap")) {
                keywordCount.merge("지도 관리", 1, Integer::sum);
            }
            if (text.contains("api") || text.contains("relay") || text.contains("연동")) {
                keywordCount.merge("API/연동", 1, Integer::sum);
            }
            if (text.contains("앱") || text.contains("app") || text.contains("sdk")) {
                keywordCount.merge("앱/SDK", 1, Integer::sum);
            }
            if (text.contains("운영") || text.contains("시스템") || text.contains("ems") || text.contains("커뮤니케이션")) {
                keywordCount.merge("운영/시스템", 1, Integer::sum);
            }
            if (text.contains("보고서") || text.contains("report") || text.contains("통계") || text.contains("게시판")) {
                keywordCount.merge("콘텐츠/보고서", 1, Integer::sum);
            }
            if (text.contains("검색") || text.contains("search") || text.contains("조회")) {
                keywordCount.merge("조회/검색", 1, Integer::sum);
            }
        }
        
        // 가장 많이 나온 키워드로 이름 생성
        String dominantKeyword = keywordCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        
        if (dominantKeyword != null && keywordCount.get(dominantKeyword) >= 2) {
            return dominantKeyword + " 서비스";
        }
        
        // 읽기 전용 BC 도메인
        long readOnlyCount = domainBCs.stream()
                .filter(bc -> bc.getTableAccessMap() == null || 
                             bc.getTableAccessMap().values().stream()
                                .noneMatch(ta -> ta.getOperations().stream()
                                    .anyMatch(op -> op == TableAccess.CrudOperation.CREATE ||
                                                  op == TableAccess.CrudOperation.UPDATE ||
                                                  op == TableAccess.CrudOperation.DELETE)))
                .count();
        
        if (readOnlyCount > domainBCs.size() / 2) {
            return "외부연동/조회 서비스";
        }
        
        // 일반 도메인: 첫 BC 도메인
        String firstDomain = domainBCs.get(0).getDomain();
        if (firstDomain != null && !firstDomain.isEmpty()) {
            return firstDomain + " 서비스";
        }
        
        return "서비스 #" + domainId;
    }
    
    // 헬퍼 메서드
    private Set<String> getSharedTables(
            BoundedContextGroup bc1, 
            BoundedContextGroup bc2,
            Set<String> significantTables) {
        
        Set<String> tables1 = bc1.getAccessedTables() != null ? 
                bc1.getAccessedTables() : Collections.emptySet();
        Set<String> tables2 = bc2.getAccessedTables() != null ? 
                bc2.getAccessedTables() : Collections.emptySet();
        
        return tables1.stream()
                .filter(tables2::contains)
                .filter(significantTables::contains)
                .collect(Collectors.toSet());
    }
    
    private boolean hasWriteOperation(BoundedContextGroup bc, String table) {
        if (bc.getTableAccessMap() == null) return false;
        
        TableAccess ta = bc.getTableAccessMap().get(table);
        if (ta == null) return false;
        
        return ta.getOperations().stream()
                .anyMatch(op -> op == TableAccess.CrudOperation.CREATE ||
                              op == TableAccess.CrudOperation.UPDATE ||
                              op == TableAccess.CrudOperation.DELETE);
    }
    
    /**
     * 도메인 서비스 (마이크로서비스 후보)
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class Domain {
        private int domainId;
        private String domainName;
        private List<String> bcNames;
        private int bcCount;
        private List<String> ownedTables;
        private List<String> internalSharedTables;
        private List<String> risks;
        private List<String> recommendations;
    }
}
