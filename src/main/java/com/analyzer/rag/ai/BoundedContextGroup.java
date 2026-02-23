package com.analyzer.rag.ai;

import com.analyzer.model.TableAccess;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded Context 그룹 (여러 컴포넌트를 하나의 BC로)
 * 
 * 예: "EMS 관리 BC"
 *   - EmsController
 *   - EmsService
 *   - EmsDAO
 *   - TB_EMS, TB_EMS_HIST
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoundedContextGroup {
    
    /**
     * BC 이름
     */
    private String bcName;
    
    /**
     * 도메인명
     */
    private String domain;
    
    /**
     * 서브도메인 타입
     */
    private BcRecommendation.SubdomainType subdomainType;
    
    /**
     * 포함된 컴포넌트 리스트
     */
    private List<ComponentInfo> components;
    
    /**
     * 접근하는 테이블 (중복 제거) - 테이블명만
     */
    private Set<String> accessedTables;
    
    /**
     * 테이블 접근 정보 (CRUD 작업 포함) ⭐ NEW
     * Map<테이블명, TableAccess>
     */
    private Map<String, TableAccess> tableAccessMap;
    
    /**
     * API 엔드포인트 (선택)
     */
    private Set<String> apiEndpoints;
    
    /**
     * 전체 확신도 (평균)
     */
    private BcRecommendation.ConfidenceLevel confidence;
    
    /**
     * 컴포넌트 수
     */
    private int componentCount;
    
    /**
     * 설명
     */
    private String description;
    
    /**
     * 마이크로서비스 분리 제안
     */
    private String microserviceSuggestion;
    
    /**
     * 컴포넌트 정보 (간략)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComponentInfo {
        private String name;
        private String type;  // Controller, Service, Repository
        private String filePath;
    }
}
