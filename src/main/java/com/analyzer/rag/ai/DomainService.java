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
import java.util.stream.Stream;

/**
 * 도메인 서비스 v7
 * - BCStatus enum 도입: WRITE_CONFIRMED / READ_CONFIRMED / ANALYSIS_INCOMPLETE / IDENTITY_FLOW / UNKNOWN
 * - isReadOnlyBC = READ_CONFIRMED 전용 (IDENTITY/INCOMPLETE는 절대 Read/BFF 금지)
 * - ANALYSIS_INCOMPLETE BC → Quarantine 도메인 격리 (Read/BFF 흡수 금지)
 * - IDENTITY_FLOW BC (로그인/인증/가입) → Identity Core 우선 배치
 * - Read/BFF 하드 룰: READ_CONFIRMED BC만 허용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainService {

    // ========== 분류 키워드 상수 ==========

    private static final Set<String> WRITE_BC_KEYWORDS = Set.of(
            "관리", "등록", "수정", "삭제", "승인", "신청", "발급", "처리", "업로드",
            "생성", "변경", "배포", "발송", "저장", "편집", "추가", "운영"
    );

    /** 검색/조회 키워드: CUD=0 이면 Hard Read BC로 격리 */
    private static final Set<String> SEARCH_QUERY_KEYWORDS = Set.of(
            "검색", "search", "조회", "query", "리포트", "report", "집계", "통계", "stats",
            "인스타", "법률", "팝업검색", "현황", "보고서", "export", "statistics", "대시보드"
    );

    /** 엄격한 Push/알림 키워드만 Platform 후보 (커뮤니케이션 제외) */
    private static final Set<String> PUSH_NOTIFY_KEYWORDS = Set.of(
            "알림서비스", "notification", "push", "푸시"
    );

    /** 게시판/공지 → Platform 흡수 금지 */
    private static final Set<String> BOARD_NOTICE_KEYWORDS = Set.of(
            "게시판", "board", "bbs", "공지", "notice"
    );

    /** Onboarding → Identity 흡수 */
    private static final Set<String> ONBOARDING_KEYWORDS = Set.of(
            "가입", "신청", "승인", "등록", "join", "signup", "register",
            "apply", "request", "approval", "verify"
    );

    /**
     * Identity/Auth BC 키워드: Read/BFF 배치 절대 금지 → Identity Core 우선
     * 로그인, 인증, 가입 등 계정/신원 관련 BC
     */
    private static final Set<String> IDENTITY_FLOW_KEYWORDS = Set.of(
            "로그인", "login", "인증", "auth", "authentication",
            "가입", "signup", "회원가입", "join"
    );

    /**
     * 상태/관리 키워드: Analysis Incomplete 판정 강화
     * (WRITE_BC_KEYWORDS 에 없는 "상태" 계열 추가)
     */
    private static final Set<String> STATUS_MANAGE_KEYWORDS = Set.of(
            "상태", "status", "현황", "신청서", "처리상태", "진행"
    );

    /** 테이블 prefix → 도메인 명칭 */
    private static final Map<String, String> TABLE_PREFIX_TO_NAME;
    static {
        TABLE_PREFIX_TO_NAME = new LinkedHashMap<>();
        TABLE_PREFIX_TO_NAME.put("TGIS",  "지도/GIS 관리");
        TABLE_PREFIX_TO_NAME.put("TMAP",  "지도 관리");
        TABLE_PREFIX_TO_NAME.put("TAPP",  "앱/SDK 플랫폼");
        TABLE_PREFIX_TO_NAME.put("TRPT",  "보고서/통계");
        TABLE_PREFIX_TO_NAME.put("TREP",  "보고서/통계");
        TABLE_PREFIX_TO_NAME.put("TIFS",  "기관/연동");
        TABLE_PREFIX_TO_NAME.put("TBBS",  "게시판/콘텐츠");
        TABLE_PREFIX_TO_NAME.put("TSYS",  "시스템 관리");
        TABLE_PREFIX_TO_NAME.put("TMON",  "모니터링/운영");
    }

    // ========== 테이블 분류 ==========

    private enum TableSupportType {
        IDENTITY_SUPPORT, OPERATIONAL_SUPPORT, REFERENCE_DATA, FILE_SUPPORT, BUSINESS_DATA
    }

    private TableSupportType classifyTableType(String tableName) {
        String upper = tableName.toUpperCase();
        if (upper.contains("USER") || upper.contains("AUTH") || upper.contains("SESSION") ||
                upper.contains("TOKEN") || upper.contains("CERT") || upper.contains("ACCOUNT") ||
                upper.contains("LOGIN") || upper.contains("CERTKEY") || upper.contains("CRTFCKEY")) {
            return TableSupportType.IDENTITY_SUPPORT;
        }
        if (upper.endsWith("_HIST") || upper.endsWith("_LOG") || upper.endsWith("_HISTORY") ||
                upper.endsWith("_AUDIT") || upper.contains("TRACE")) {
            return TableSupportType.OPERATIONAL_SUPPORT;
        }
        if (upper.endsWith("_CODE") || upper.endsWith("_CD") || upper.endsWith("_TYPE") ||
                upper.endsWith("_STATUS") || upper.endsWith("_MST") || upper.contains("_CODE_")) {
            return TableSupportType.REFERENCE_DATA;
        }
        if (upper.contains("FILE") || upper.contains("IMAGE") || upper.contains("ATTACH") ||
                upper.contains("_ATCHMNFL") || upper.contains("UPLOAD") || upper.contains("MEDIA")) {
            return TableSupportType.FILE_SUPPORT;
        }
        return TableSupportType.BUSINESS_DATA;
    }

    private double getTableTypeWeight(TableSupportType type) {
        switch (type) {
            case IDENTITY_SUPPORT:    return 0.15;
            case OPERATIONAL_SUPPORT: return 0.2;
            case REFERENCE_DATA:      return 0.1;
            case FILE_SUPPORT:        return 0.4;
            default:                  return 1.0;
        }
    }

    private boolean isCommonTable(String tableName) {
        String upper = tableName.toUpperCase();
        if (upper.equals("DUAL") || upper.equals("DUMMY")) return true;
        if (upper.startsWith("TCOM_") || upper.startsWith("TIFS_") ||
                upper.startsWith("TB_COMMON_") || upper.startsWith("CMN_") || upper.startsWith("SYS_")) return true;
        if (upper.endsWith("_CODE") || upper.endsWith("_MST") || upper.endsWith("_CD") || upper.contains("_CODE_")) return true;
        if (upper.endsWith("_HIST") || upper.endsWith("_LOG") || upper.endsWith("_HISTORY") ||
                upper.endsWith("_AUDIT") || upper.contains("_ATCHMNFL") || upper.contains("_FILE")) return true;
        if (upper.contains("_AUTHOR") || upper.contains("_MENU") || upper.contains("_PROGM")) return true;
        return false;
    }

    // ========== BC 상태 분류 (BCStatus) ==========

    /**
     * BC 분석 상태
     * - WRITE_CONFIRMED  : tableAccessMap에 실제 C/U/D 존재 → 확정 쓰기
     * - READ_CONFIRMED   : CUD=0 + 검색/조회/보고서 키워드 → 확정 읽기 → Read/BFF 허용
     * - IDENTITY_FLOW    : 로그인/인증/가입 키워드 → Identity Core 우선 (Read/BFF 금지)
     * - ANALYSIS_INCOMPLETE: 쓰기 키워드 있으나 CUD=0 → Mapper 파싱 실패 의심 → Quarantine
     * - UNKNOWN          : CUD=0, 키워드 불명확 → 보수적 Core 배치
     */
    private enum BCStatus {
        WRITE_CONFIRMED, READ_CONFIRMED, IDENTITY_FLOW, ANALYSIS_INCOMPLETE, UNKNOWN
    }

    private BCStatus getBCStatus(BoundedContextGroup bc) {
        boolean hasCUD = hasCUDInTableAccessMap(bc);
        if (hasCUD) return BCStatus.WRITE_CONFIRMED;

        String lower = bc.getBcName().toLowerCase();

        // 1. Identity/Auth 키워드 → 절대 Read/BFF 금지
        if (IDENTITY_FLOW_KEYWORDS.stream().anyMatch(lower::contains))
            return BCStatus.IDENTITY_FLOW;

        // 2. 검색/조회 키워드 → 확정 Read (Read/BFF 허용)
        if (SEARCH_QUERY_KEYWORDS.stream().anyMatch(lower::contains))
            return BCStatus.READ_CONFIRMED;

        // 3. 쓰기 키워드 또는 상태/관리 키워드 + CUD=0 → 분석 불완전
        boolean hasWriteKw = WRITE_BC_KEYWORDS.stream().anyMatch(lower::contains);
        boolean hasStatusKw = STATUS_MANAGE_KEYWORDS.stream().anyMatch(lower::contains);
        if (hasWriteKw || hasStatusKw)
            return BCStatus.ANALYSIS_INCOMPLETE;

        return BCStatus.UNKNOWN;
    }

    // ========== BC 판별 메서드 ==========

    private boolean hasCUDInTableAccessMap(BoundedContextGroup bc) {
        if (bc.getTableAccessMap() == null || bc.getTableAccessMap().isEmpty()) return false;
        return bc.getTableAccessMap().values().stream()
                .flatMap(ta -> ta.getOperations() != null ? ta.getOperations().stream() : Stream.empty())
                .anyMatch(op -> op == TableAccess.CrudOperation.CREATE ||
                        op == TableAccess.CrudOperation.UPDATE ||
                        op == TableAccess.CrudOperation.DELETE);
    }

    /**
     * [하드 룰] Read/BFF 배치 가능 여부 = READ_CONFIRMED 전용.
     * IDENTITY_FLOW / ANALYSIS_INCOMPLETE / UNKNOWN → false → Read/BFF 금지
     */
    private boolean isReadOnlyBC(BoundedContextGroup bc) {
        return getBCStatus(bc) == BCStatus.READ_CONFIRMED;
    }

    /** Hard Read BC = READ_CONFIRMED → 클러스터링 전 격리 */
    private boolean isHardReadBC(BoundedContextGroup bc) {
        return getBCStatus(bc) == BCStatus.READ_CONFIRMED;
    }

    /** Quarantine BC = ANALYSIS_INCOMPLETE → 클러스터링 전 격리, Read/BFF 금지 */
    private boolean isQuarantineBC(BoundedContextGroup bc) {
        return getBCStatus(bc) == BCStatus.ANALYSIS_INCOMPLETE;
    }

    /** Identity Flow BC = IDENTITY_FLOW → Read/BFF 금지, Identity Core 우선 */
    private boolean isIdentityFlowBC(BoundedContextGroup bc) {
        return getBCStatus(bc) == BCStatus.IDENTITY_FLOW;
    }

    /** Analysis Incomplete = ANALYSIS_INCOMPLETE (buildClassificationReasons 호환) */
    private boolean isAnalysisIncomplete(BoundedContextGroup bc) {
        return getBCStatus(bc) == BCStatus.ANALYSIS_INCOMPLETE;
    }

    /** 게시판/공지 BC */
    private boolean isBoardNoticeBC(String bcName) {
        return BOARD_NOTICE_KEYWORDS.stream().anyMatch(bcName.toLowerCase()::contains);
    }

    /** Push/알림 BC (엄격, 커뮤니케이션 제외) */
    private boolean isPushNotifyBC(String bcName) {
        String lower = bcName.toLowerCase();
        return lower.contains("notification") || lower.contains("push") ||
                lower.contains("푸시") || (lower.contains("알림") && lower.contains("서비스"));
    }

    private boolean isOnboardingFlowBC(String bcName) {
        return ONBOARDING_KEYWORDS.stream().anyMatch(bcName.toLowerCase()::contains);
    }

    private boolean isMediaBC(String bcName) {
        String lower = bcName.toLowerCase();
        return lower.contains("이미지") || lower.contains("image") || lower.contains("파일") ||
                lower.contains("미디어") || lower.contains("media") || lower.contains("첨부") || lower.contains("업로드");
    }

    private boolean isSupportingBC(String bcName) {
        String lower = bcName.toLowerCase();
        return isBoardNoticeBC(bcName) || lower.contains("api") || lower.contains("admin") ||
                lower.contains("config") || lower.contains("ems") || lower.contains("커뮤니케이션");
    }

    private boolean isPlatformBC(String bcName) {
        String lower = bcName.toLowerCase();
        return lower.contains("앱") || lower.contains("app") || lower.contains("sdk") || isPushNotifyBC(bcName);
    }

    // ========== 메인 로직 ==========

    public List<Domain> createDomains(List<BoundedContextGroup> bcGroups, int targetDomainCount) {
        List<BoundedContextGroup> uniqueBCGroups = deduplicateBCGroups(bcGroups);
        log.info("도메인 생성 시작: {}개 BC → {}개 도메인 목표", uniqueBCGroups.size(), targetDomainCount);

        // [사전 격리] BC 상태별 분류
        Set<String> hardReadBCNames = new HashSet<>();   // READ_CONFIRMED → Read 클러스터
        Set<String> quarantineBCNames = new HashSet<>(); // ANALYSIS_INCOMPLETE → Quarantine
        for (BoundedContextGroup bc : uniqueBCGroups) {
            BCStatus status = getBCStatus(bc);
            if (status == BCStatus.READ_CONFIRMED) {
                hardReadBCNames.add(bc.getBcName());
                log.info("[Read 격리] '{}' (BCStatus=READ_CONFIRMED)", bc.getBcName());
            } else if (status == BCStatus.ANALYSIS_INCOMPLETE) {
                quarantineBCNames.add(bc.getBcName());
                log.info("[Quarantine 격리] '{}' (BCStatus=ANALYSIS_INCOMPLETE: 쓰기 키워드 있으나 CUD=0)", bc.getBcName());
            } else if (status == BCStatus.IDENTITY_FLOW) {
                log.info("[Identity Flow] '{}' → Core 우선 배치 (Read/BFF 금지)", bc.getBcName());
            }
        }

        Set<String> allExcluded = new HashSet<>();
        allExcluded.addAll(hardReadBCNames);
        allExcluded.addAll(quarantineBCNames);

        Set<String> significantTables = extractSignificantTables(uniqueBCGroups);
        Map<String, String> tableOwners = determineTableOwners(uniqueBCGroups, significantTables);
        Map<String, Map<String, Double>> connections = calculateBCConnections(uniqueBCGroups, significantTables, tableOwners);

        // Read/Quarantine BC를 클러스터링에서 제외
        List<Set<String>> clusters = clusterBCs(uniqueBCGroups, connections, targetDomainCount, allExcluded);
        clusters = deduplicateBCAssignments(clusters, connections);

        // Read 클러스터 추가
        if (!hardReadBCNames.isEmpty()) {
            clusters.add(new HashSet<>(hardReadBCNames));
            log.info("[Read 클러스터] {}개 BC 선배치: {}", hardReadBCNames.size(), hardReadBCNames);
        }
        // Quarantine 클러스터 추가 (Read와 별개)
        if (!quarantineBCNames.isEmpty()) {
            clusters.add(new HashSet<>(quarantineBCNames));
            log.info("[Quarantine 클러스터] {}개 BC 격리: {}", quarantineBCNames.size(), quarantineBCNames);
        }

        List<Domain> rawDomains = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            Set<String> cluster = clusters.get(i);
            if (!cluster.isEmpty()) {
                rawDomains.add(createDomain(i + 1, cluster, uniqueBCGroups, significantTables, tableOwners));
            }
        }

        // 이름 전역 중복 해소 (1차)
        enforceNameUniqueness(rawDomains);

        List<Domain> mergedDomains = mergeForInitialServices(rawDomains, uniqueBCGroups, tableOwners);
        mergedDomains = globalDeduplicateBC(mergedDomains);

        // 이름 전역 중복 해소 (2차: 병합 후)
        enforceNameUniqueness(mergedDomains);

        for (int i = 0; i < mergedDomains.size(); i++) mergedDomains.get(i).setDomainId(i + 1);

        log.info("도메인 생성 완료: 원본 {}개 → 최종 {}개", rawDomains.size(), mergedDomains.size());
        return mergedDomains;
    }

    // ========== 사전 격리 헬퍼 (레거시 호환용) ==========
    // createDomains에서 직접 getBCStatus를 사용하므로 이 메서드는 내부 참조용만

    // ========== BC 중복 제거 ==========

    private List<BoundedContextGroup> deduplicateBCGroups(List<BoundedContextGroup> bcGroups) {
        Map<String, BoundedContextGroup> seen = new LinkedHashMap<>();
        for (BoundedContextGroup bc : bcGroups) {
            if (seen.containsKey(bc.getBcName())) {
                log.warn("[BC 입력 중복] '{}' → 첫 번째만 사용", bc.getBcName());
            } else {
                seen.put(bc.getBcName(), bc);
            }
        }
        return new ArrayList<>(seen.values());
    }

    private List<Set<String>> deduplicateBCAssignments(List<Set<String>> clusters,
            Map<String, Map<String, Double>> connections) {
        Map<String, List<Integer>> bcClusterMap = new HashMap<>();
        for (int i = 0; i < clusters.size(); i++) {
            for (String bc : clusters.get(i)) bcClusterMap.computeIfAbsent(bc, k -> new ArrayList<>()).add(i);
        }
        List<Set<String>> result = clusters.stream().map(HashSet::new).collect(Collectors.toList());
        for (Map.Entry<String, List<Integer>> entry : bcClusterMap.entrySet()) {
            List<Integer> indices = entry.getValue();
            if (indices.size() <= 1) continue;
            String bcName = entry.getKey();
            log.warn("[BC 중복 클러스터] '{}' → {}개 클러스터 → 연결강도 최대 클러스터 유지", bcName, indices.size());
            int bestIdx = indices.get(0); double bestScore = -1;
            for (int idx : indices) {
                double score = result.get(idx).stream().filter(o -> !o.equals(bcName))
                        .mapToDouble(o -> { Map<String, Double> c = connections.get(bcName); return c != null ? c.getOrDefault(o, 0.0) : 0.0; }).sum();
                if (score > bestScore) { bestScore = score; bestIdx = idx; }
            }
            final int keepIdx = bestIdx;
            for (int idx : indices) {
                if (idx != keepIdx) { result.get(idx).remove(bcName); }
            }
        }
        result.removeIf(Set::isEmpty);
        return result;
    }

    private List<Domain> globalDeduplicateBC(List<Domain> domains) {
        Map<String, String> bcToDomain = new LinkedHashMap<>();
        for (Domain d : domains) {
            Iterator<String> iter = d.getBcNames().iterator();
            while (iter.hasNext()) {
                String bcName = iter.next();
                String existing = bcToDomain.get(bcName);
                if (existing != null) {
                    log.warn("[전역 중복 BC] '{}' → '{}', '{}' 동시 소속 → 현 도메인 제거", bcName, existing, d.getDomainName());
                    d.getWarnings().add(String.format("⚠️ [중복 BC 제거] '%s' 이미 '%s'에 배정 → 제거됨", bcName, existing));
                    iter.remove();
                } else {
                    bcToDomain.put(bcName, d.getDomainName());
                }
            }
            d.setBcCount(d.getBcNames().size());
        }
        return domains;
    }

    // ========== 이름 전역 중복 해소 ==========

    /**
     * 이름 전역 중복 해소 (v8 개선)
     * suffix "(API)" 방식 대신 BC 특성으로 완전한 새 이름 생성:
     * - API/relay 계열 → "API 관리 서비스"
     * - Board/Notice  → "콘텐츠/게시판 서비스"
     * - Report/통계   → "보고서/통계 서비스"
     * - 사용자/인증   → "사용자 관리 서비스"
     * - 그 외         → 기존 이름 + " (N)" 숫자 suffix
     */
    private void enforceNameUniqueness(List<Domain> domains) {
        // 충분히 안정적인 이름이 되도록 2회 패스
        for (int pass = 0; pass < 2; pass++) {
            Map<String, List<Domain>> byName = new LinkedHashMap<>();
            for (Domain d : domains) byName.computeIfAbsent(d.getDomainName(), k -> new ArrayList<>()).add(d);

            for (Map.Entry<String, List<Domain>> entry : byName.entrySet()) {
                List<Domain> dups = entry.getValue();
                if (dups.size() <= 1) continue;
                log.warn("[이름 중복] '{}' → {}개 도메인", entry.getKey(), dups.size());

                // 충돌 도메인들을 BC 특성 점수로 정렬
                // 가장 "특성 뚜렷한" 도메인부터 새 이름 부여
                Set<String> usedNames = new HashSet<>();
                usedNames.add(entry.getKey()); // 첫 번째 도메인은 현재 이름 유지
                boolean first = true;
                for (Domain dup : dups) {
                    if (first) { first = false; continue; } // 첫 번째 유지
                    String newName = generateAlternativeName(dup, usedNames);
                    dup.getWarnings().add(String.format("⚠️ [이름 중복 해소] '%s' → '%s'", entry.getKey(), newName));
                    log.info("[이름 중복 해소] '{}' → '{}'", entry.getKey(), newName);
                    dup.setDomainName(newName);
                    usedNames.add(newName);
                }
            }
        }
    }

    /**
     * 이름 충돌 시 새 이름 생성 – BC 특성 키워드 우선, 없으면 숫자 suffix
     */
    private String generateAlternativeName(Domain domain, Set<String> usedNames) {
        // BC 이름에서 대표 기능 추출 (API/Board/Report/사용자/운영 계열)
        Map<String, Integer> typeScore = new LinkedHashMap<>();
        for (String bcName : domain.getBcNames()) {
            String lc = bcName.toLowerCase();
            if (lc.contains("api") || lc.contains("relay") || lc.contains("연동") || lc.contains("인터페이스"))
                typeScore.merge("API 관리", 3, Integer::sum);
            if (lc.contains("게시판") || lc.contains("board") || lc.contains("bbs") || lc.contains("공지") || lc.contains("콘텐츠"))
                typeScore.merge("콘텐츠/게시판", 3, Integer::sum);
            if (lc.contains("보고서") || lc.contains("report") || lc.contains("통계") || lc.contains("현황"))
                typeScore.merge("보고서/통계", 3, Integer::sum);
            if (lc.contains("사용자") || lc.contains("로그인") || lc.contains("인증") || lc.contains("user"))
                typeScore.merge("사용자 관리", 2, Integer::sum);
            if (lc.contains("운영") || lc.contains("ems") || lc.contains("시스템"))
                typeScore.merge("운영/시스템", 2, Integer::sum);
            if (lc.contains("검색") || lc.contains("조회") || lc.contains("search"))
                typeScore.merge("조회/검색", 1, Integer::sum);
        }
        String bestType = typeScore.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);

        if (bestType != null) {
            String candidate = bestType + " 서비스";
            if (!usedNames.contains(candidate)) return candidate;
        }
        // fallback: 숫자 suffix
        String base = domain.getDomainName();
        for (int i = 2; i <= 9; i++) {
            String c = base + " " + i;
            if (!usedNames.contains(c)) return c;
        }
        return base + " (기타)";
    }

    // ========== 서비스 축소 병합 ==========

    private List<Domain> mergeForInitialServices(List<Domain> domains,
            List<BoundedContextGroup> allBCGroups, Map<String, String> tableOwners) {

        List<Domain> readLayers = new ArrayList<>();
        List<Domain> platformDomains = new ArrayList<>();
        List<Domain> quarantineDomains = new ArrayList<>(); // [NEW] 분석 필요 격리 도메인
        List<Domain> zeroOwnerCores = new ArrayList<>();
        List<Domain> normalDomains = new ArrayList<>();

        for (Domain d : domains) {
            switch (d.getDomainType()) {
                case "Read/BFF Layer":           readLayers.add(d); break;
                case "Platform Domain":          platformDomains.add(d); break;
                case "분석 필요 (Quarantine)":   quarantineDomains.add(d); break;
                default:
                    if (d.getOwnedTables() == null || d.getOwnedTables().isEmpty()) zeroOwnerCores.add(d);
                    else normalDomains.add(d);
                    break;
            }
        }

        // Read 통합 (READ_CONFIRMED BC만 포함됨 → 안전)
        Domain mergedReadLayer = null;
        if (!readLayers.isEmpty()) {
            mergedReadLayer = mergeDomainsInto(readLayers, "통합 조회 서비스", "Read/BFF Layer");
            mergedReadLayer.setReadOnlyConfirmed(true);
            mergedReadLayer.setPlacementReason("규칙: BCStatus=READ_CONFIRMED BC만 포함 → Read/BFF 확정");
            mergedReadLayer.getRecommendations().add("CQRS Read Model 패턴 적용");
            mergedReadLayer.getRecommendations().add("트래픽 증가 시 개별 서비스로 분리 검토");
        }

        // Platform 통합
        Domain mergedPlatform = null;
        if (!platformDomains.isEmpty()) {
            mergedPlatform = mergeDomainsInto(platformDomains, "앱 플랫폼 서비스", "Platform Domain");
            mergedPlatform.getRecommendations().add("초기에 단일 플랫폼 서비스로 운영");
        }

        // [NEW] Quarantine 통합 (Read/BFF에 절대 흡수 금지)
        Domain mergedQuarantine = null;
        if (!quarantineDomains.isEmpty()) {
            mergedQuarantine = mergeDomainsInto(quarantineDomains, "분석 필요 서비스", "분석 필요 (Quarantine)");
            mergedQuarantine.setHasAnalysisIncomplete(true);
            mergedQuarantine.setBacklog(true);  // 마이크로서비스 카운트에서 제외
            mergedQuarantine.setPlacementReason("규칙: ANALYSIS_INCOMPLETE BC 격리 (쓰기 키워드 있으나 CUD 테이블 미검출) → 분리 보류(Backlog)");
            mergedQuarantine.getWarnings().add("⚠️ [Quarantine] 이 도메인의 BC들은 Mapper 파싱 실패 또는 동적 SQL로 인해 CUD 테이블이 탐지되지 않았습니다.");
            mergedQuarantine.getWarnings().add("   → 조치: 각 BC의 MyBatis XML Mapper 파일 직접 확인 필요");
            mergedQuarantine.getWarnings().add("   → 확인 후 Core/Platform/Supporting 중 적합한 도메인으로 수동 이동");
            mergedQuarantine.getRecommendations().add("MyBatis XML Mapper 파싱 상태 점검 후 도메인 재분류");
            log.info("[Quarantine 도메인] {}개 BC 격리: {}", mergedQuarantine.getBcNames().size(), mergedQuarantine.getBcNames());
        }

        // Zero-owner Core 라우팅
        List<Domain> unabsorbed = new ArrayList<>();
        for (Domain zoc : zeroOwnerCores) {
            List<BoundedContextGroup> domainBCList = allBCGroups.stream()
                    .filter(bc -> zoc.getBcNames().contains(bc.getBcName())).collect(Collectors.toList());

            // [하드 룰] Identity BC → Read/BFF 금지
            boolean hasIdentity = domainBCList.stream().anyMatch(this::isIdentityFlowBC);
            // [하드 룰] Incomplete BC → Read/BFF 금지
            boolean hasIncomplete = domainBCList.stream().anyMatch(this::isAnalysisIncomplete);
            // 실제 READ_CONFIRMED만 allReadOnly로 판정
            boolean allReadConfirmed = !domainBCList.isEmpty() && domainBCList.stream().allMatch(this::isReadOnlyBC);
            boolean hasBoardNotice = zoc.getBcNames().stream().anyMatch(this::isBoardNoticeBC);
            boolean hasPushNotify = zoc.getBcNames().stream().anyMatch(this::isPushNotifyBC);

            if (hasIncomplete) {
                // Incomplete BC → Quarantine으로
                if (mergedQuarantine != null) absorbDomainInto(mergedQuarantine, zoc, "ANALYSIS_INCOMPLETE → Quarantine");
                else { zoc.setHasAnalysisIncomplete(true); unabsorbed.add(zoc); }
            } else if (hasIdentity) {
                // Identity BC → Identity Core로 (Read/BFF 금지)
                Domain identityTarget = findIdentityDomain(normalDomains);
                if (identityTarget != null) absorbDomainInto(identityTarget, zoc, "IDENTITY_FLOW → 사용자 관리 Core");
                else { zoc.setPlacementReason("IDENTITY_FLOW → Identity 도메인 없어 독립 유지"); unabsorbed.add(zoc); }
            } else if (allReadConfirmed && mergedReadLayer != null) {
                absorbDomainInto(mergedReadLayer, zoc, "READ_CONFIRMED → Read/BFF");
            } else if (hasBoardNotice) {
                Domain sup = findSupportingDomain(normalDomains);
                if (sup != null) absorbDomainInto(sup, zoc, "게시판/공지 → Supporting");
                else if (mergedReadLayer != null && !hasIdentity && !hasIncomplete)
                    absorbDomainInto(mergedReadLayer, zoc, "게시판/공지 → Read (last resort)");
                else unabsorbed.add(zoc);
            } else if (hasPushNotify && mergedPlatform != null) {
                absorbDomainInto(mergedPlatform, zoc, "Push/알림 → Platform");
            } else {
                Domain target = findBestAbsorptionTarget(zoc, normalDomains);
                if (target != null) absorbDomainInto(target, zoc, "의미 유사 Core");
                else { zoc.getWarnings().add("⚠️ 흡수 불가 → 독립 도메인 유지"); unabsorbed.add(zoc); }
            }
        }

        List<Domain> result = new ArrayList<>();
        if (mergedReadLayer != null) result.add(mergedReadLayer);
        if (mergedPlatform != null) result.add(mergedPlatform);
        if (mergedQuarantine != null) result.add(mergedQuarantine);
        result.addAll(normalDomains);
        result.addAll(unabsorbed);

        validateBCAssignment(result, allBCGroups);
        return result;
    }

    /** Identity 도메인 찾기 (사용자/인증 관련 Core Domain) */
    private Domain findIdentityDomain(List<Domain> candidates) {
        return candidates.stream()
                .filter(d -> d.getDomainName().contains("사용자") || d.getDomainName().contains("인증") ||
                        d.getDomainName().contains("user") || d.getBcNames().stream()
                        .anyMatch(bc -> IDENTITY_FLOW_KEYWORDS.stream().anyMatch(bc.toLowerCase()::contains)))
                .findFirst().orElse(null);
    }

    private Domain findSupportingDomain(List<Domain> candidates) {
        return candidates.stream().filter(d -> "Supporting Domain".equals(d.getDomainType())).findFirst().orElse(null);
    }

    private Domain mergeDomainsInto(List<Domain> sources, String newName, String type) {
        List<String> allBCs = new ArrayList<>(), allOwned = new ArrayList<>(), allShared = new ArrayList<>();
        List<String> allRisks = new ArrayList<>(), allRecs = new ArrayList<>(), allWarnings = new ArrayList<>();
        List<String> allReasons = new ArrayList<>();
        List<String> allReadDeps = new ArrayList<>(), allReadModels = new ArrayList<>();
        List<BcTableLink> allLinks = new ArrayList<>();
        for (Domain d : sources) {
            allBCs.addAll(d.getBcNames());
            if (d.getOwnedTables() != null) allOwned.addAll(d.getOwnedTables());
            if (d.getInternalSharedTables() != null) allShared.addAll(d.getInternalSharedTables());
            if (d.getRisks() != null) allRisks.addAll(d.getRisks());
            if (d.getWarnings() != null) allWarnings.addAll(d.getWarnings());
            if (d.getClassificationReasons() != null) allReasons.addAll(d.getClassificationReasons());
            if (d.getReadDependencyTables() != null) allReadDeps.addAll(d.getReadDependencyTables());
            if (d.getReadModelCandidates() != null) allReadModels.addAll(d.getReadModelCandidates());
            if (d.getBcTableLinks() != null) allLinks.addAll(d.getBcTableLinks());
        }
        if (sources.size() > 1) allRecs.add(String.format("통합 전 원본: %d개 도메인 (%s)", sources.size(),
                sources.stream().map(Domain::getDomainName).collect(Collectors.joining(", "))));
        boolean anyIncomplete = sources.stream().anyMatch(Domain::isHasAnalysisIncomplete);
        boolean allReadOnly = sources.stream().allMatch(Domain::isReadOnlyConfirmed);
        // 병합 후 소유 테이블 기준으로 owned 플래그 재계산
        Set<String> mergedOwnedSet = new LinkedHashSet<>(allOwned);
        List<BcTableLink> retaggedLinks = allLinks.stream()
                .map(l -> new BcTableLink(l.getBcName(), l.getTableName(), l.getOps(), mergedOwnedSet.contains(l.getTableName())))
                .collect(Collectors.toList());
        return Domain.builder().domainId(0).domainName(newName).domainType(type)
                .bcNames(allBCs).bcCount(allBCs.size())
                .ownedTables(new ArrayList<>(mergedOwnedSet))
                .internalSharedTables(new ArrayList<>(new LinkedHashSet<>(allShared)))
                .readDependencyTables(new ArrayList<>(new LinkedHashSet<>(allReadDeps)))
                .readModelCandidates(new ArrayList<>(new LinkedHashSet<>(allReadModels)))
                .risks(allRisks).recommendations(allRecs)
                .classificationReasons(allReasons).warnings(allWarnings)
                .hasAnalysisIncomplete(anyIncomplete)
                .readOnlyConfirmed(allReadOnly)
                .bcTableLinks(retaggedLinks)
                .build();
    }

    private void absorbDomainInto(Domain target, Domain source, String reason) {
        Set<String> existing = new HashSet<>(target.getBcNames());
        int skipped = 0;
        for (String bc : source.getBcNames()) {
            if (existing.contains(bc)) { skipped++; target.getWarnings().add("⚠️ [중복 BC 스킵] '" + bc + "'"); }
            else { target.getBcNames().add(bc); existing.add(bc); }
        }
        target.setBcCount(target.getBcNames().size());
        if (source.getOwnedTables() != null) target.getOwnedTables().addAll(source.getOwnedTables());
        if (source.getInternalSharedTables() != null) target.getInternalSharedTables().addAll(source.getInternalSharedTables());
        if (source.getWarnings() != null) target.getWarnings().addAll(source.getWarnings());
        // bcTableLinks 병합 + owned 플래그 재계산
        if (source.getBcTableLinks() != null) {
            if (target.getBcTableLinks() == null) target.setBcTableLinks(new ArrayList<>());
            Set<String> targetOwnedSet = new HashSet<>(target.getOwnedTables() != null ? target.getOwnedTables() : Collections.emptyList());
            source.getBcTableLinks().forEach(l ->
                target.getBcTableLinks().add(new BcTableLink(l.getBcName(), l.getTableName(), l.getOps(), targetOwnedSet.contains(l.getTableName())))
            );
        }
        target.getRecommendations().add(String.format("'%s' 흡수됨 [%s]%s → 트래픽 기반 분리 검토",
                source.getDomainName(), reason, skipped > 0 ? " (" + skipped + "개 중복 스킵)" : ""));
        log.info("[흡수] '{}' → '{}' [{}]", source.getDomainName(), target.getDomainName(), reason);
    }

    private Domain findBestAbsorptionTarget(Domain zeroOwner, List<Domain> candidates) {
        if (candidates.isEmpty()) return null;
        if (zeroOwner.getBcNames().stream().anyMatch(this::isOnboardingFlowBC)) {
            for (Domain c : candidates) {
                if (c.getDomainName().toLowerCase().contains("사용자") || c.getDomainName().toLowerCase().contains("인증")) return c;
            }
        }
        Domain best = null; int bestScore = 0;
        for (Domain c : candidates) {
            int score = computeDomainSimilarityScore(zeroOwner.getBcNames(), c.getDomainName(), c.getBcNames());
            if (score > bestScore) { bestScore = score; best = c; }
        }
        return (best != null && bestScore > 0) ? best : null;
    }

    private int computeDomainSimilarityScore(List<String> sourceBCNames, String targetDomainName, List<String> targetBCNames) {
        int score = 0;
        String tgt = targetDomainName.toLowerCase();
        Map<String, String[]> groups = new LinkedHashMap<>();
        groups.put("identity", new String[]{"사용자", "user", "회원", "인증", "로그인", "auth"});
        groups.put("geo",      new String[]{"지도", "map", "gis", "tmtmap"});
        groups.put("api",      new String[]{"api", "relay", "연동"});
        groups.put("platform", new String[]{"앱", "app", "sdk", "push", "플랫폼"});
        groups.put("ops",      new String[]{"운영", "시스템", "ems", "모니터링", "커뮤니케이션"});
        groups.put("report",   new String[]{"보고서", "report", "통계"});
        groups.put("board",    new String[]{"게시판", "bbs", "board", "공지"});
        groups.put("search",   new String[]{"검색", "search", "조회"});
        for (String bcName : sourceBCNames) {
            String lower = bcName.toLowerCase();
            for (Map.Entry<String, String[]> e : groups.entrySet()) {
                boolean bcM  = Arrays.stream(e.getValue()).anyMatch(lower::contains);
                boolean tgtM = Arrays.stream(e.getValue()).anyMatch(tgt::contains) ||
                        targetBCNames.stream().anyMatch(t -> Arrays.stream(e.getValue()).anyMatch(t.toLowerCase()::contains));
                if (bcM && tgtM) score += 10;
            }
        }
        return score;
    }

    private void validateBCAssignment(List<Domain> domains, List<BoundedContextGroup> allBCGroups) {
        Map<String, Integer> bcCount = new HashMap<>();
        for (Domain d : domains) for (String bc : d.getBcNames()) bcCount.merge(bc, 1, Integer::sum);
        long dups = bcCount.values().stream().filter(v -> v > 1).count();
        Set<String> allNames = allBCGroups.stream().map(BoundedContextGroup::getBcName).collect(Collectors.toSet());
        Set<String> missing = new HashSet<>(allNames); missing.removeAll(bcCount.keySet());
        log.info("[BC 배정 검증] 전체: {}개, 배정: {}개, 미배정: {}개, 중복: {}개", allNames.size(), bcCount.size(), missing.size(), dups);
        if (!missing.isEmpty()) log.warn("[미배정 BC] {}", missing);
        if (dups > 0) bcCount.entrySet().stream().filter(e -> e.getValue() > 1)
                .forEach(e -> log.warn("[중복 BC] '{}' → {}개 도메인", e.getKey(), e.getValue()));
    }

    // ========== 테이블 분석 ==========

    private Set<String> extractSignificantTables(List<BoundedContextGroup> bcGroups) {
        Set<String> all = new HashSet<>();
        for (BoundedContextGroup bc : bcGroups) { if (bc.getAccessedTables() != null) all.addAll(bc.getAccessedTables()); }
        Set<String> sig = all.stream().filter(t -> !isCommonTable(t)).collect(Collectors.toSet());
        log.info("유의미한 테이블: 전체 {}개 → 유의미 {}개 (공통 {}개 제외)", all.size(), sig.size(), all.size() - sig.size());
        return sig;
    }

    private Map<String, String> determineTableOwners(List<BoundedContextGroup> bcGroups, Set<String> significantTables) {
        Map<String, String> owners = new HashMap<>();
        List<String> conflicts = new ArrayList<>();
        for (String table : significantTables) {
            String owner = null; int maxScore = 0;
            boolean isId = (classifyTableType(table) == TableSupportType.IDENTITY_SUPPORT);
            for (BoundedContextGroup bc : bcGroups) {
                if (bc.getTableAccessMap() == null) continue;
                TableAccess ta = bc.getTableAccessMap().get(table);
                if (ta == null) continue;
                int score = 0;
                for (TableAccess.CrudOperation op : ta.getOperations()) {
                    switch (op) { case CREATE: score += 3; break; case UPDATE: score += 2; break; case DELETE: score += 3; break; case READ: score += 1; break; }
                }
                if (isId) {
                    String lower = bc.getBcName().toLowerCase();
                    if (lower.contains("사용자") || lower.contains("user") || lower.contains("인증") || lower.contains("auth") || lower.contains("로그인"))
                        score += 100;
                }
                if (score > maxScore) {
                    if (isId && owner != null && maxScore > 0)
                        conflicts.add(String.format("[인증 테이블 충돌] '%s': '%s'→'%s' 재배치 검토", table, owner, bc.getBcName()));
                    maxScore = score; owner = bc.getBcName();
                }
            }
            if (owner != null) owners.put(table, owner);
        }
        if (!conflicts.isEmpty()) { log.warn("소유권 충돌 {}건:", conflicts.size()); conflicts.forEach(log::warn); }
        return owners;
    }

    private Map<String, Map<String, Double>> calculateBCConnections(List<BoundedContextGroup> bcGroups,
            Set<String> significantTables, Map<String, String> tableOwners) {
        Map<String, Map<String, Double>> connections = new HashMap<>();
        for (BoundedContextGroup bc1 : bcGroups) {
            connections.putIfAbsent(bc1.getBcName(), new HashMap<>());
            for (BoundedContextGroup bc2 : bcGroups) {
                if (bc1.getBcName().equals(bc2.getBcName())) continue;
                double strength = 0.0;
                for (String table : getSharedTables(bc1, bc2, significantTables)) {
                    double w = getTableTypeWeight(classifyTableType(table));
                    boolean b1W = hasWriteOperation(bc1, table), b2W = hasWriteOperation(bc2, table);
                    if (b1W && b2W) strength += 20.0 * w;
                    else if (b1W || b2W) strength += 5.0 * w;
                    else strength += 1.0;
                    String own = tableOwners.get(table);
                    if (own != null && (own.equals(bc1.getBcName()) || own.equals(bc2.getBcName()))) strength += 5.0 * w;
                }
                strength += calculateDomainSimilarity(bc1, bc2) * 10.0;
                if (strength > 0) connections.get(bc1.getBcName()).put(bc2.getBcName(), strength);
            }
        }
        return connections;
    }

    private double calculateDomainSimilarity(BoundedContextGroup bc1, BoundedContextGroup bc2) {
        String[] kws = {"사용자","user","인증","auth","지도","map","tmtmap","gis","api","relay",
                "앱","app","sdk","push","운영","system","ems","보고서","report","게시판","bbs","검색","조회"};
        Set<String> k1 = extractKeywords(bc1.getBcName(), bc1.getDomain(), kws);
        Set<String> k2 = extractKeywords(bc2.getBcName(), bc2.getDomain(), kws);
        Set<String> inter = new HashSet<>(k1); inter.retainAll(k2);
        Set<String> union = new HashSet<>(k1); union.addAll(k2);
        return union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
    }

    private Set<String> extractKeywords(String bcName, String domain, String[] keywords) {
        Set<String> result = new HashSet<>();
        String text = (bcName + " " + (domain != null ? domain : "")).toLowerCase();
        for (String kw : keywords) { if (text.contains(kw.toLowerCase())) result.add(kw.toLowerCase()); }
        return result;
    }

    // ========== 클러스터링 (Hard Read BC 제외) ==========

    private List<Set<String>> clusterBCs(List<BoundedContextGroup> bcGroups,
            Map<String, Map<String, Double>> connections, int targetCount, Set<String> excludedBCNames) {
        List<Set<String>> clusters = new ArrayList<>();
        Set<String> assigned = new HashSet<>();
        List<String> bcNames = bcGroups.stream().map(BoundedContextGroup::getBcName).collect(Collectors.toList());

        while (clusters.size() < targetCount && assigned.size() < bcNames.size()) {
            Set<String> cluster = new HashSet<>();
            // excluded BC는 시드 후보에서 제외
            String seed = bcNames.stream().filter(bc -> !assigned.contains(bc) && !excludedBCNames.contains(bc)).findFirst().orElse(null);
            if (seed == null) break;
            cluster.add(seed); assigned.add(seed);

            while (cluster.size() < 5) {
                String best = null; double bestStr = 0.0;
                for (String bcInCluster : cluster) {
                    Map<String, Double> conns = connections.get(bcInCluster);
                    if (conns == null) continue;
                    for (Map.Entry<String, Double> e : conns.entrySet()) {
                        // excluded BC는 클러스터에 추가 불가
                        if (assigned.contains(e.getKey()) || excludedBCNames.contains(e.getKey())) continue;
                        if (e.getValue() > bestStr) { bestStr = e.getValue(); best = e.getKey(); }
                    }
                }
                if (best == null || bestStr < 10.0) break;
                cluster.add(best); assigned.add(best);
            }
            clusters.add(cluster);
        }

        // 고립 BC (excluded 제외)
        Set<String> orphans = bcNames.stream().filter(bc -> !assigned.contains(bc) && !excludedBCNames.contains(bc)).collect(Collectors.toSet());
        if (!orphans.isEmpty()) {
            log.info("고립 BC: {}개 → 키워드 기반 2차 그룹핑", orphans.size());
            List<BoundedContextGroup> orphanBCGroups = bcGroups.stream().filter(bc -> orphans.contains(bc.getBcName())).collect(Collectors.toList());
            groupByDomainKeywords(orphanBCGroups).values().forEach(g -> splitLargeGroup(g, 5).forEach(clusters::add));
        }
        return clusters;
    }

    private Map<String, Set<String>> groupByDomainKeywords(List<BoundedContextGroup> bcs) {
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        groups.put("사용자/인증", new HashSet<>());
        groups.put("앱/SDK", new HashSet<>());
        groups.put("운영/시스템", new HashSet<>()); // 커뮤니케이션도 여기에
        groups.put("조회/검색", new HashSet<>());
        groups.put("기타", new HashSet<>());

        for (BoundedContextGroup bc : bcs) {
            String text = (bc.getBcName() + " " + (bc.getDomain() != null ? bc.getDomain() : "")).toLowerCase();
            boolean c = false;
            if (isOnboardingFlowBC(bc.getBcName()) || text.contains("사용자") || text.contains("user") || text.contains("로그인") || text.contains("인증") || text.contains("회원"))
            { groups.get("사용자/인증").add(bc.getBcName()); c = true; }
            else if (text.contains("앱") || text.contains("app") || text.contains("sdk") || text.contains("push") || text.contains("팝업"))
            { groups.get("앱/SDK").add(bc.getBcName()); c = true; }
            else if (text.contains("운영") || text.contains("시스템") || text.contains("system") || text.contains("admin") ||
                    text.contains("ems") || text.contains("커뮤니케이션") || text.contains("신청서"))
            { groups.get("운영/시스템").add(bc.getBcName()); c = true; }
            else if (text.contains("조회") || text.contains("검색") || text.contains("search") || text.contains("보고서") || text.contains("report"))
            { groups.get("조회/검색").add(bc.getBcName()); c = true; }
            if (!c) groups.get("기타").add(bc.getBcName());
        }
        groups.entrySet().removeIf(e -> e.getValue().isEmpty());
        log.info("도메인 키워드 그룹핑: {}", groups.keySet());
        return groups;
    }

    private List<Set<String>> splitLargeGroup(Set<String> group, int maxSize) {
        List<Set<String>> result = new ArrayList<>();
        if (group.size() <= maxSize) { result.add(group); return result; }
        List<String> list = new ArrayList<>(group);
        for (int i = 0; i < list.size(); i += maxSize)
            result.add(new HashSet<>(list.subList(i, Math.min(i + maxSize, list.size()))));
        return result;
    }

    // ========== 도메인 타입 결정 ==========

    private String determineDomainType(List<BoundedContextGroup> domainBCs, Map<String, String> tableOwners) {
        if (domainBCs.isEmpty()) return "Core Domain";

        // [하드 룰 1] Quarantine: ANALYSIS_INCOMPLETE BC가 1개라도 있으면 격리
        // WRITE=true(키워드) && tables=0 인 BC는 Core 확정 금지
        long incompleteCount = domainBCs.stream().filter(this::isAnalysisIncomplete).count();
        if (incompleteCount > 0) return "분석 필요 (Quarantine)";

        // [하드 룰 2] Read/BFF: 모든 BC가 READ_CONFIRMED여야만 허용
        // IDENTITY_FLOW / ANALYSIS_INCOMPLETE / UNKNOWN 이 하나라도 있으면 Read/BFF 금지
        boolean allReadConfirmed = domainBCs.stream().allMatch(this::isReadOnlyBC); // isReadOnlyBC = READ_CONFIRMED only
        if (allReadConfirmed) return "Read/BFF Layer";

        // [하드 룰 3] Identity BC 존재 → Core Domain 우선
        boolean hasIdentity = domainBCs.stream().anyMatch(this::isIdentityFlowBC);
        if (hasIdentity) return "Core Domain";

        // Platform: TAPP_* 테이블 2개 이상 또는 Platform BC
        boolean isPlatform = domainBCs.stream().anyMatch(bc -> isPlatformBC(bc.getBcName()));
        long appTables = tableOwners.entrySet().stream()
                .filter(e -> domainBCs.stream().anyMatch(bc -> bc.getBcName().equals(e.getValue())))
                .filter(e -> e.getKey().toUpperCase().startsWith("TAPP_")).count();
        if (isPlatform || appTables >= 2) return "Platform Domain";

        // Supporting: 게시판/API/ems 등이 엄격 과반수 (정수 나눔 버그 방지: count*2 > total)
        long supportCount = domainBCs.stream().filter(bc -> isSupportingBC(bc.getBcName())).count();
        if (supportCount * 2 > domainBCs.size()) return "Supporting Domain";

        return "Core Domain";
    }

    // ========== 도메인 생성 ==========

    private Domain createDomain(int domainId, Set<String> bcNames, List<BoundedContextGroup> allBCs,
            Set<String> significantTables, Map<String, String> tableOwners) {

        List<BoundedContextGroup> domainBCs = allBCs.stream()
                .filter(bc -> bcNames.contains(bc.getBcName())).collect(Collectors.toList());

        // 소유 테이블 먼저 계산 (이름 결정에 사용)
        Set<String> ownedTables = new HashSet<>();
        for (BoundedContextGroup bc : domainBCs) {
            for (Map.Entry<String, String> entry : tableOwners.entrySet()) {
                if (entry.getValue().equals(bc.getBcName())) ownedTables.add(entry.getKey());
            }
        }

        String domainType = determineDomainType(domainBCs, tableOwners);
        // [3] 대표 BC 의미 우선 → Supporting 기능명 → 테이블 prefix 순서로 명명
        String domainName = generateDomainName(domainId, domainBCs, ownedTables, tableOwners, domainType);

        Set<String> internalSharedTables = new HashSet<>();
        for (String table : significantTables) {
            long cnt = domainBCs.stream().filter(bc -> bc.getAccessedTables() != null && bc.getAccessedTables().contains(table)).count();
            if (cnt >= 2) internalSharedTables.add(table);
        }

        List<String> risks = new ArrayList<>(), recommendations = new ArrayList<>();
        List<String> warnings = new ArrayList<>(), classificationReasons = buildClassificationReasons(domainBCs, domainType, ownedTables);
        List<String> readDependencyTables = new ArrayList<>();
        List<String> readModelCandidates = new ArrayList<>();

        // ===== 도메인 타입별 분석 (서로 다른 리스크/권장사항) =====
        if ("Read/BFF Layer".equals(domainType)) {
            // [1] Read/BFF: 트랜잭션 조율 대상이 아님 → Read dependency 분석
            Set<String> readDeps = computeReadDependencyTables(domainBCs, significantTables);
            Set<String> readModels = computeReadModelCandidates(domainBCs, significantTables);
            readDependencyTables.addAll(readDeps.stream().sorted().collect(Collectors.toList()));
            readModelCandidates.addAll(readModels.stream().sorted().collect(Collectors.toList()));

            if (!readDependencyTables.isEmpty())
                risks.add("Core 테이블 Read Dependency " + readDependencyTables.size() + "개 (직접 테이블 접근)");
            if (!readModelCandidates.isEmpty())
                recommendations.add("Read Model 구축 후보 (" + readModelCandidates.size() + "개): " +
                        String.join(", ", readModelCandidates));
            recommendations.add("CQRS Read Model 패턴 적용");
            recommendations.add("캐싱 전략 수립 (Redis/Caffeine)");
            recommendations.add("고빈도 조회 테이블 → Materialized View 또는 별도 Read Store 검토");
            if (domainBCs.size() > 4) recommendations.add("BFF 서비스 수 많음 → GraphQL/API Gateway 패턴 검토");

        } else if ("분석 필요 (Quarantine)".equals(domainType)) {
            // [2] Quarantine: 각 BC별 Mapper 파싱 실패 원인 리포트
            warnings.addAll(buildQuarantineReport(domainBCs));

            // Media BC 타입 다운그레이드 옵션
            List<String> mediaBCsInQ = domainBCs.stream().filter(bc -> isMediaBC(bc.getBcName()))
                    .map(BoundedContextGroup::getBcName).collect(Collectors.toList());
            if (!mediaBCsInQ.isEmpty()) {
                warnings.add("📸 [미디어 BC 감지] " + mediaBCsInQ);
                warnings.add("   → Supporting/Platform 이동 권장: 이미지/파일 관리는 객체 스토리지(S3/MinIO) 연동 별도 서비스 분리 권장");
                recommendations.add("미디어 BC(" + String.join(", ", mediaBCsInQ) + ")를 Platform 또는 Supporting으로 수동 재분류");
            }
            recommendations.add("MyBatis XML Mapper 파일 파싱 상태 점검 후 Core/Platform/Supporting 재분류");

        } else {
            // Core/Platform/Supporting: 기존 Shared Table Risk
            if (internalSharedTables.size() > 5) {
                risks.add(String.format("도메인 내부 공유 테이블 많음: %d개 (트랜잭션 조율 필요)", internalSharedTables.size()));
                recommendations.add("Saga 패턴 또는 2PC 검토");
            }
            if (domainBCs.size() > 6) recommendations.add("모듈러 모놀리스로 시작 → 점진적 분리");

            // Core + 소유 테이블 0
            if (ownedTables.isEmpty() && "Core Domain".equals(domainType)) {
                warnings.add("⚠️ [소유 테이블 없음] Core Domain이나 소유 테이블 미검출");
                warnings.add("   → 재분류 후보: Flow Layer / Supporting / Operations");
                List<String> mediaBCs = domainBCs.stream().filter(bc -> isMediaBC(bc.getBcName()))
                        .map(BoundedContextGroup::getBcName).collect(Collectors.toList());
                if (!mediaBCs.isEmpty()) warnings.add("   → 미디어 BC " + mediaBCs + " → Media 도메인 분리 검토");
                recommendations.add("[점검] MyBatis Mapper/XML 파일 파싱 상태 확인");
            } else if (ownedTables.isEmpty()) {
                risks.add("소유 테이블 없음 (읽기 전용 또는 공통 서비스)");
            }

            // 타입별 추가 추천
            switch (domainType) {
                case "Platform Domain":
                    recommendations.add("버전 관리 전략 수립"); recommendations.add("SDK 배포 자동화"); break;
                case "Supporting Domain":
                    recommendations.add("공통 모듈화 또는 라이브러리화 검토"); break;
                default: // Core Domain
                    if (domainName.contains("사용자") || domainName.contains("인증")) {
                        recommendations.add("SSO/OAuth 통합 인증 구축"); recommendations.add("GDPR 적용");
                    } else if (domainName.contains("지도") || domainName.contains("GIS")) {
                        recommendations.add("Geo/Map 전용 서비스로 독립 운영");
                    }
                    break;
            }

            // Analysis Incomplete BC 감지 (Core/Platform/Supporting에서도 표시)
            List<String> incompleteBCs = domainBCs.stream().filter(this::isAnalysisIncomplete)
                    .map(BoundedContextGroup::getBcName).collect(Collectors.toList());
            if (!incompleteBCs.isEmpty()) {
                warnings.add("⚠️ [분석 불완전] " + incompleteBCs + ": 쓰기 키워드 있으나 CUD 테이블 미검출");
                warnings.add("   → 원인: Mapper 파싱 누락, 동적 SQL(<bind>/<foreach>), ${} 변수 쿼리");
            }
        }

        boolean readOnlyConfirmed = "Read/BFF Layer".equals(domainType) && domainBCs.stream().allMatch(this::isReadOnlyBC);
        boolean hasAnalysisIncomplete = domainBCs.stream().anyMatch(this::isAnalysisIncomplete);
        String placementReason = buildPlacementReason(domainType, domainBCs);

        // BC → Table 연결 데이터 (시각화용)
        List<BcTableLink> bcTableLinks = buildBcTableLinks(domainBCs, ownedTables);

        return Domain.builder().domainId(domainId).domainName(domainName).domainType(domainType)
                .bcNames(new ArrayList<>(bcNames)).bcCount(bcNames.size())
                .ownedTables(new ArrayList<>(ownedTables)).internalSharedTables(new ArrayList<>(internalSharedTables))
                .readDependencyTables(readDependencyTables).readModelCandidates(readModelCandidates)
                .risks(risks).recommendations(recommendations)
                .classificationReasons(classificationReasons).warnings(warnings)
                .readOnlyConfirmed(readOnlyConfirmed)
                .hasAnalysisIncomplete(hasAnalysisIncomplete)
                .placementReason(placementReason)
                .bcTableLinks(bcTableLinks)
                .build();
    }

    /** BC-Table 연결 링크 목록 생성 (SVG 시각화용) */
    private List<BcTableLink> buildBcTableLinks(List<BoundedContextGroup> domainBCs, Set<String> ownedTables) {
        List<BcTableLink> links = new ArrayList<>();
        for (BoundedContextGroup bc : domainBCs) {
            if (bc.getTableAccessMap() == null) continue;
            for (Map.Entry<String, TableAccess> entry : bc.getTableAccessMap().entrySet()) {
                String table = entry.getKey();
                TableAccess ta = entry.getValue();
                List<String> ops = new ArrayList<>();
                if (ta.getOperations().contains(TableAccess.CrudOperation.CREATE)) ops.add("C");
                if (ta.getOperations().contains(TableAccess.CrudOperation.READ))   ops.add("R");
                if (ta.getOperations().contains(TableAccess.CrudOperation.UPDATE)) ops.add("U");
                if (ta.getOperations().contains(TableAccess.CrudOperation.DELETE)) ops.add("D");
                if (ops.isEmpty()) continue;
                links.add(new BcTableLink(bc.getBcName(), table, ops, ownedTables.contains(table)));
            }
        }
        return links;
    }

    // ========== [3] 도메인 이름 생성 (trace 로그 포함) ==========

    /**
     * [개선 v8] 도메인 이름 생성 규칙
     * 우선순위: ① Supporting 기능명 (API/Board/Admin 키워드) → ② 대표 BC 의미 (소유 테이블 최다 BC)
     *          → ③ BUSINESS_DATA 테이블 prefix → ④ BC 키워드 빈도 → ⑤ fallback
     * - 소유 테이블 0인 흡수 BC는 이름 후보에서 제외
     * - Supporting Domain은 테이블 prefix 무시하고 기능명 우선
     */
    private String generateDomainName(int domainId, List<BoundedContextGroup> domainBCs,
            Set<String> ownedTables, Map<String, String> tableOwners, String domainType) {

        // ① Supporting 기능명: API/Board/Admin 계열은 테이블 prefix 무시하고 기능명 우선
        String supportingName = detectSupportingFunctionName(domainBCs, domainType);
        if (supportingName != null) {
            log.info("[도메인명#{}] Supporting 기능명 감지 → '{}'", domainId, supportingName);
            return supportingName;
        }

        // ② 대표 BC (소유 테이블 최다 BC) 기반 의미 명명
        //    - 소유 테이블 0인 BC(흡수된 게시판/보고서 등)는 후보에서 제외
        String top1Name = getTop1BCSemanticName(domainBCs, tableOwners);
        if (top1Name != null) {
            log.info("[도메인명#{}] 대표 BC 의미명 → '{}'", domainId, top1Name);
            return top1Name + " 서비스";
        }

        // ③ BUSINESS_DATA 소유 테이블 prefix 빈도 (Identity/Reference/File 제외)
        Set<String> businessTables = ownedTables.stream()
                .filter(t -> classifyTableType(t) == TableSupportType.BUSINESS_DATA).collect(Collectors.toSet());
        if (!businessTables.isEmpty()) {
            Map<String, Long> prefixCount = new HashMap<>();
            for (String t : businessTables) {
                String up = t.toUpperCase(); int us = up.indexOf('_');
                String prefix = us > 0 ? up.substring(0, us) : up;
                if (TABLE_PREFIX_TO_NAME.containsKey(prefix)) prefixCount.merge(prefix, 1L, Long::sum);
            }
            if (!prefixCount.isEmpty()) {
                Map.Entry<String, Long> top = prefixCount.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
                if (top != null) {
                    String name = TABLE_PREFIX_TO_NAME.get(top.getKey()) + " 서비스";
                    log.info("[도메인명#{}] 테이블 prefix '{}' ({}개 BUSINESS 테이블) → '{}'",
                            domainId, top.getKey(), top.getValue(), name);
                    return name;
                }
            }
        }

        // ④ BC 키워드 빈도 (2개 이상)
        String bcKw = getBCKeywordName(domainBCs);
        if (bcKw != null) {
            log.info("[도메인명#{}] BC 키워드 '{}' → '{}'", domainId, bcKw, bcKw + " 서비스");
            return bcKw + " 서비스";
        }

        // ⑤ 읽기 전용 / 첫 BC / fallback
        if (!domainBCs.isEmpty() && domainBCs.stream().allMatch(this::isReadOnlyBC)) return "조회/검색 서비스";
        String firstDomain = domainBCs.isEmpty() ? null : domainBCs.get(0).getDomain();
        if (firstDomain != null && !firstDomain.isEmpty()) return firstDomain + " 서비스";

        return "서비스 #" + domainId;
    }

    /**
     * Supporting 기능명 감지 (v8 수정)
     * 규칙:
     *  - Supporting Domain이면 1개 BC라도 API/Board/Report/Admin 키워드 → 기능명 반환
     *  - 다른 타입(Core/Platform)이면 해당 키워드 BC가 엄격 과반수(>50%) 일 때만 기능명 반환
     *  - 소유 테이블 여부와 무관하게 BC 이름 기반으로만 판단 (relay 서비스는 테이블 0)
     */
    private String detectSupportingFunctionName(List<BoundedContextGroup> domainBCs, String domainType) {
        if (domainBCs.isEmpty()) return null;
        boolean isSupporting = "Supporting Domain".equals(domainType);
        int total = domainBCs.size();

        long apiCount    = domainBCs.stream().filter(bc -> {
            String n = bc.getBcName().toLowerCase();
            return n.contains("api") || n.contains("relay") || n.contains("연동") || n.contains("인터페이스");
        }).count();
        long boardCount  = domainBCs.stream().filter(bc -> {
            String n = bc.getBcName().toLowerCase();
            return n.contains("게시판") || n.contains("board") || n.contains("bbs") ||
                   n.contains("공지") || n.contains("notice") || n.contains("콘텐츠");
        }).count();
        long reportCount = domainBCs.stream().filter(bc -> {
            String n = bc.getBcName().toLowerCase();
            return n.contains("보고서") || n.contains("report") || n.contains("통계") || n.contains("현황");
        }).count();
        long adminCount  = domainBCs.stream().filter(bc -> {
            String n = bc.getBcName().toLowerCase();
            return n.contains("시스템 관리") || n.contains("admin") || n.contains("운영 관리");
        }).count();

        // Supporting Domain: 1개라도 해당 키워드면 기능명 사용 (prefix 무시)
        // 그 외: 엄격 과반수(strict >50%)일 때만 기능명 사용
        if (apiCount > 0    && (isSupporting || apiCount * 2 > total))    return "API 관리 서비스";
        if (boardCount > 0  && (isSupporting || boardCount * 2 > total))  return "콘텐츠/게시판 서비스";
        if (reportCount > 0 && (isSupporting || reportCount * 2 > total)) return "보고서/통계 서비스";
        if (adminCount > 0  && (isSupporting || adminCount * 2 > total))  return "시스템 관리 서비스";
        return null;
    }

    /**
     * 대표 BC (소유 테이블 최다 BC) 의미 명칭 반환
     * - tableOwners에서 이 도메인 BC들의 소유 테이블 수를 집계
     * - 소유 테이블 0인 BC(게시판/보고서 등 흡수된 BC)는 후보 제외
     * - BC 이름 자체를 정리해 의미명으로 사용 (예: "지도 관리 BC" → "지도 관리")
     */
    private String getTop1BCSemanticName(List<BoundedContextGroup> domainBCs, Map<String, String> tableOwners) {
        Set<String> bcNames = domainBCs.stream().map(BoundedContextGroup::getBcName).collect(Collectors.toSet());
        Map<String, Long> ownedCount = new HashMap<>();
        for (Map.Entry<String, String> e : tableOwners.entrySet()) {
            if (bcNames.contains(e.getValue())) ownedCount.merge(e.getValue(), 1L, Long::sum);
        }
        if (ownedCount.isEmpty()) return null;

        // 최다 소유 BC 선정
        String top1BC = ownedCount.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        if (top1BC == null) return null;

        // BC 이름 정리: "지도 관리 BC" → "지도 관리", "사용자 관리 BC" → "사용자 관리"
        String cleaned = top1BC.replaceAll("\\s*BC$", "").replaceAll("BC$", "").trim();
        // Supporting 계열 키워드가 결과 이름에 포함되면 skip (③ prefix 단계에서 처리)
        if (cleaned.isEmpty()) return null;
        return cleaned;
    }

    private String getBCKeywordName(List<BoundedContextGroup> domainBCs) {
        Map<String, Integer> kc = new HashMap<>();
        for (BoundedContextGroup bc : domainBCs) {
            String text = (bc.getBcName() + " " + (bc.getDomain() != null ? bc.getDomain() : "")).toLowerCase();
            if (text.contains("사용자") || text.contains("user") || text.contains("로그인") || text.contains("회원")) kc.merge("사용자/인증", 1, Integer::sum);
            if (text.contains("지도") || text.contains("map") || text.contains("gis") || text.contains("tmtmap")) kc.merge("지도 관리", 1, Integer::sum);
            if (text.contains("api") || text.contains("relay") || text.contains("연동")) kc.merge("API/연동", 1, Integer::sum);
            if (text.contains("앱") || text.contains("app") || text.contains("sdk")) kc.merge("앱/SDK", 1, Integer::sum);
            if (text.contains("운영") || text.contains("시스템") || text.contains("ems") || text.contains("커뮤니케이션")) kc.merge("운영/시스템", 1, Integer::sum);
            if (text.contains("보고서") || text.contains("report") || text.contains("통계")) kc.merge("보고서/통계", 1, Integer::sum);
            if (text.contains("게시판") || text.contains("bbs") || text.contains("board")) kc.merge("게시판/콘텐츠", 1, Integer::sum);
            if (text.contains("검색") || text.contains("search") || text.contains("조회")) kc.merge("조회/검색", 1, Integer::sum);
        }
        return kc.entrySet().stream().filter(e -> e.getValue() >= 2).max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** BC별 분류 근거 생성 (표준화) */
    private List<String> buildClassificationReasons(List<BoundedContextGroup> domainBCs,
            String domainType, Set<String> ownedTables) {
        List<String> reasons = new ArrayList<>();
        reasons.add(String.format("[타입 결정] %s", domainType));
        if (!ownedTables.isEmpty()) {
            Map<String, Long> pd = new HashMap<>();
            for (String t : ownedTables) { String up = t.toUpperCase(); int us = up.indexOf('_'); pd.merge(us > 0 ? up.substring(0, us) : up, 1L, Long::sum); }
            reasons.add(String.format("[소유 테이블] %d개, prefix 분포: %s", ownedTables.size(), pd));
        } else {
            reasons.add("[소유 테이블] 없음");
        }
        for (BoundedContextGroup bc : domainBCs) {
            boolean hasCUD = hasCUDInTableAccessMap(bc);
            boolean incomplete = isAnalysisIncomplete(bc);
            String tags = (hasCUD ? "[쓰기CUD확인] " : "[CUD없음] ") +
                    (incomplete ? "[⚠️분석불완전] " : "") +
                    (isHardReadBC(bc) ? "[HardRead] " : "") +
                    (isOnboardingFlowBC(bc.getBcName()) ? "[Onboarding] " : "") +
                    (isPlatformBC(bc.getBcName()) ? "[Platform] " : "") +
                    (isSupportingBC(bc.getBcName()) ? "[Supporting] " : "") +
                    (isBoardNoticeBC(bc.getBcName()) ? "[Board/Notice] " : "");
            reasons.add(bc.getBcName() + ": " + tags.trim());
        }
        return reasons;
    }

    // ========== Read/BFF 전용 분석 ==========

    /** Read/BFF: 의존하는 significant 테이블 집합 */
    private Set<String> computeReadDependencyTables(List<BoundedContextGroup> domainBCs, Set<String> significantTables) {
        Set<String> readDeps = new HashSet<>();
        for (BoundedContextGroup bc : domainBCs) {
            if (bc.getAccessedTables() != null)
                readDeps.addAll(bc.getAccessedTables().stream().filter(significantTables::contains).collect(Collectors.toSet()));
        }
        return readDeps;
    }

    /** Read/BFF: 2개 이상의 BC가 READ하는 테이블 → Read Model 후보 */
    private Set<String> computeReadModelCandidates(List<BoundedContextGroup> domainBCs, Set<String> significantTables) {
        Map<String, Long> freq = new HashMap<>();
        for (BoundedContextGroup bc : domainBCs) {
            if (bc.getAccessedTables() != null) {
                for (String t : bc.getAccessedTables()) {
                    if (significantTables.contains(t)) freq.merge(t, 1L, Long::sum);
                }
            }
        }
        return freq.entrySet().stream().filter(e -> e.getValue() >= 2).map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    // ========== Quarantine 원인 리포트 ==========

    /**
     * Quarantine BC별 Mapper 파싱 실패 원인 리포트 생성
     */
    private List<String> buildQuarantineReport(List<BoundedContextGroup> quarantineBCs) {
        List<String> report = new ArrayList<>();
        for (BoundedContextGroup bc : quarantineBCs) {
            report.add("━━ [" + bc.getBcName() + "] 원인 분석");

            // DAO 컴포넌트 목록
            List<BoundedContextGroup.ComponentInfo> daoComps = bc.getComponents().stream()
                    .filter(c -> isDAOComponent(c.getName())).collect(Collectors.toList());
            List<BoundedContextGroup.ComponentInfo> svcComps = bc.getComponents().stream()
                    .filter(c -> !isDAOComponent(c.getName())).collect(Collectors.toList());

            if (daoComps.isEmpty()) {
                report.add("  ⚠️ DAO/Mapper 컴포넌트 없음 (Controller/Service만 존재)");
                report.add("  → 이 BC는 공유 DAO를 사용하거나 직접 JDBC 접근 가능성");
            } else {
                String daoNames = daoComps.stream().map(BoundedContextGroup.ComponentInfo::getName).collect(Collectors.joining(", "));
                report.add("  📁 DAO 컴포넌트: " + daoNames);
                String expectedXmls = daoComps.stream()
                        .map(c -> inferExpectedXmlName(c.getName())).collect(Collectors.joining(", "));
                report.add("  🔍 예상 Mapper XML: " + expectedXmls);
                String filePaths = daoComps.stream()
                        .map(c -> c.getFilePath() != null ? c.getFilePath() : "경로 미확인")
                        .collect(Collectors.joining("\n              "));
                report.add("  📂 파일 경로: " + filePaths);
            }

            // 탐지된 테이블 현황
            int readTableCount = bc.getTableAccessMap() != null ? bc.getTableAccessMap().size() : 0;
            report.add(String.format("  📊 탐지 결과: READ=%d개 테이블, CUD=0 (write 키워드: '%s')",
                    readTableCount, extractWriteKeywords(bc.getBcName())));

            // ⭐ 실제 Mapper 스캔 로그 (DependencyAnalyzer 수집)
            if (bc.getMapperIssues() != null && !bc.getMapperIssues().isEmpty()) {
                report.add("  🔬 Mapper 스캔 진단 (" + bc.getMapperIssues().size() + "건):");
                bc.getMapperIssues().forEach(issue -> report.add("     " + issue));
            } else {
                report.add("  🔬 Mapper 스캔 진단: 진단 데이터 없음 (재인덱싱 후 확인)");
            }

            // 의심 실패 패턴 (키워드 기반 휴리스틱)
            List<String> patterns = inferFailurePatterns(bc);
            if (!patterns.isEmpty()) {
                report.add("  ⚠️ 의심 실패 패턴 (휴리스틱):");
                patterns.forEach(p -> report.add("     - " + p));
            }
        }
        return report;
    }

    private boolean isDAOComponent(String name) {
        return name.endsWith("DAO") || name.endsWith("DAOImpl") || name.endsWith("Mapper") ||
                name.endsWith("MapperImpl") || name.endsWith("Repository") || name.endsWith("RepositoryImpl");
    }

    private String inferExpectedXmlName(String daoName) {
        String base = daoName.replaceAll("(DAO|Mapper|Repository|Impl)$", "");
        return base + "_SQL.xml (또는 " + daoName + ".xml)";
    }

    private String extractWriteKeywords(String bcName) {
        String lower = bcName.toLowerCase();
        return WRITE_BC_KEYWORDS.stream().filter(lower::contains).collect(Collectors.joining(", "));
    }

    private List<String> inferFailurePatterns(BoundedContextGroup bc) {
        List<String> patterns = new ArrayList<>();
        String lower = bc.getBcName().toLowerCase();

        if (lower.contains("ems") || lower.contains("전자민원"))
            patterns.add("<include refid> 공통 SQL fragment 분리 의심");
        if (lower.contains("계약") || lower.contains("contract"))
            patterns.add("동적 테이블명 ${tableName} 또는 복합 LEFT JOIN 구조 의심");
        if (lower.contains("팝업") || lower.contains("popup"))
            patterns.add("EXECUTE 동적 쿼리 또는 공유 DAO 사용 의심");
        if (lower.contains("상태") || lower.contains("status") || lower.contains("신청서"))
            patterns.add("상태 추적 복합 WHERE 조건 또는 <foreach> 동적 IN 절 의심");
        if (lower.contains("이미지") || lower.contains("image") || lower.contains("파일"))
            patterns.add("파일 경로 String concat 또는 BLOB 메타 분리 테이블 의심");

        if (bc.getTableAccessMap() == null || bc.getTableAccessMap().isEmpty())
            patterns.add("Mapper XML 파일 자체 미탐지 → 파일명 규칙 불일치 또는 별도 경로");
        else
            patterns.add("Mapper XML 탐지됐으나 <insert>/<update>/<delete> 정규식 매칭 실패");

        return patterns;
    }

    /** 배치 근거 문자열 생성 */
    private String buildPlacementReason(String domainType, List<BoundedContextGroup> domainBCs) {
        long writeConfirmed  = domainBCs.stream().filter(bc -> getBCStatus(bc) == BCStatus.WRITE_CONFIRMED).count();
        long readConfirmed   = domainBCs.stream().filter(bc -> getBCStatus(bc) == BCStatus.READ_CONFIRMED).count();
        long identityFlow    = domainBCs.stream().filter(bc -> getBCStatus(bc) == BCStatus.IDENTITY_FLOW).count();
        long incomplete      = domainBCs.stream().filter(bc -> getBCStatus(bc) == BCStatus.ANALYSIS_INCOMPLETE).count();
        long unknown         = domainBCs.stream().filter(bc -> getBCStatus(bc) == BCStatus.UNKNOWN).count();
        return String.format("[%s] BC구성: WRITE=%d, READ=%d, IDENTITY=%d, INCOMPLETE=%d, UNKNOWN=%d",
                domainType, writeConfirmed, readConfirmed, identityFlow, incomplete, unknown);
    }

    // ========== 헬퍼 ==========

    private Set<String> getSharedTables(BoundedContextGroup bc1, BoundedContextGroup bc2, Set<String> sig) {
        Set<String> t1 = bc1.getAccessedTables() != null ? bc1.getAccessedTables() : Collections.emptySet();
        Set<String> t2 = bc2.getAccessedTables() != null ? bc2.getAccessedTables() : Collections.emptySet();
        return t1.stream().filter(t2::contains).filter(sig::contains).collect(Collectors.toSet());
    }

    private boolean hasWriteOperation(BoundedContextGroup bc, String table) {
        if (bc.getTableAccessMap() == null) return false;
        TableAccess ta = bc.getTableAccessMap().get(table);
        if (ta == null) return false;
        return ta.getOperations().stream().anyMatch(op ->
                op == TableAccess.CrudOperation.CREATE || op == TableAccess.CrudOperation.UPDATE || op == TableAccess.CrudOperation.DELETE);
    }

    // ========== DTO ==========

    @Data @Builder @AllArgsConstructor
    public static class Domain {
        private int domainId;
        private String domainName;
        /**
         * "Core Domain" | "Platform Domain" | "Supporting Domain"
         * | "Read/BFF Layer" | "분석 필요 (Quarantine)"
         */
        private String domainType;
        private List<String> bcNames;
        private int bcCount;
        private List<String> ownedTables;
        private List<String> internalSharedTables;
        private List<String> risks;
        private List<String> recommendations;
        private List<String> classificationReasons;
        private List<String> warnings;

        // ========== Read/BFF 전용 필드 ==========
        /** Read/BFF: 의존하는 Core 테이블 목록 (직접 READ) */
        private List<String> readDependencyTables;
        /** Read/BFF: 고빈도 조회 테이블 → Materialized View/CQRS 후보 */
        private List<String> readModelCandidates;

        // ========== Trace 필드 ==========
        /** true = 모든 BC가 READ_CONFIRMED (CUD=0 + 검색/조회 키워드) */
        private boolean readOnlyConfirmed;
        /** true = ANALYSIS_INCOMPLETE BC 포함 (Mapper 파싱 실패 의심) */
        private boolean hasAnalysisIncomplete;
        /** 배치 규칙 근거 (로그/UI 표시용) */
        private String placementReason;

        // ========== Backlog 필드 ==========
        /**
         * true = 마이크로서비스 후보가 아님 (분석 보류/Backlog)
         * Quarantine 도메인에만 적용. 서비스 수 계산에서 제외.
         */
        private boolean backlog;

        // ========== 시각화 필드 ==========
        /** BC → Table 연결 목록 (SVG 연결도 시각화용) */
        private List<BcTableLink> bcTableLinks;
    }

    /**
     * BC-Table 연결 링크 (시각화용)
     */
    @Data
    @AllArgsConstructor
    public static class BcTableLink {
        /** 소속 BC 이름 */
        String bcName;
        /** 접근 테이블 이름 */
        String tableName;
        /** 수행 CRUD 연산 ["C","R","U","D"] 중 해당하는 것 */
        List<String> ops;
        /** true = 이 도메인이 소유하는 테이블 (Primary owner) */
        boolean owned;
    }
}
