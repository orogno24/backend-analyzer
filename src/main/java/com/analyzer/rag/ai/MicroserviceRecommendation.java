package com.analyzer.rag.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 마이크로서비스 분리 추천 결과
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MicroserviceRecommendation {
    
    /**
     * BC 이름
     */
    private String bcName;
    
    /**
     * 분리 점수 (0-100)
     */
    private int score;
    
    /**
     * 등급 (A/B/C)
     */
    private String grade;
    
    /**
     * 추천 메시지
     */
    private String recommendation;
    
    /**
     * 주요 소유 테이블 (CRUD 강도 >= 5)
     */
    private List<TableOwnership> ownedTables;
    
    /**
     * 공유 테이블 (다른 BC가 같이 접근)
     */
    private List<SharedTableInfo> sharedTables;
    
    /**
     * 컴포넌트 수
     */
    private int componentCount;
    
    /**
     * 리스크 목록
     */
    private List<String> risks;
    
    /**
     * 필수 작업 (분리 전 해야 할 일)
     */
    private List<String> requiredActions;
    
    /**
     * 예상 공수
     */
    private String estimatedEffort;
    
    /**
     * 신뢰도 (LOW/MEDIUM/HIGH)
     */
    private String confidence;
    
    /**
     * ⭐ 코드 의존성 정보 (v2)
     */
    private Integer internalDependencies;   // 내부 의존 수
    private Integer externalDependencies;   // 외부 의존 수
    private Double cohesionScore;           // 응집도 (0.0~1.0)
    private Map<String, Integer> topExternalBCs;  // 상위 외부 BC 의존
    
    /**
     * 테이블 소유 정보
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableOwnership {
        private String tableName;
        private int crudScore;  // CREATE=3, UPDATE=2, DELETE=3, READ=1
        private String ownership;  // STRONG(>=5), WEAK(<5)
        private List<String> operations;  // [C, R, U, D]
    }
    
    /**
     * 공유 테이블 정보
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SharedTableInfo {
        private String tableName;
        private int sharedByCount;  // 몇 개 BC가 접근하는가
        private String riskLevel;  // HIGH(>=5), MEDIUM(3-4), LOW(2), REFERENCE(공통 테이블)
        private String tableType;  // REFERENCE(공통), TRANSACTION(트랜잭션), DUMMY(더미), UNKNOWN
        private List<String> sharedWith;  // 같이 접근하는 BC 목록
    }
}
