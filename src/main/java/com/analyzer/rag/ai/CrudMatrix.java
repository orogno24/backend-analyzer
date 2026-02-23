package com.analyzer.rag.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CRUD Matrix (서비스 ↔ 테이블 관계)
 * 
 * 예시:
 *          TB_USER  TB_API  RT_SMS_LIST
 * EmsService   -      -         R
 * ApiService   R     CRUD       -
 * MapService   R      -         -
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrudMatrix {
    
    /**
     * 서비스 목록 (행)
     */
    private List<String> services;
    
    /**
     * 테이블 목록 (열)
     */
    private List<String> tables;
    
    /**
     * Matrix 데이터
     * Map<서비스명, Map<테이블명, CRUD 작업 목록>>
     * 
     * 예: {
     *   "EmsService": {
     *     "RT_SMS_LIST": ["R"],
     *     "EMS_CPU_LIST": ["R"]
     *   },
     *   "ApiService": {
     *     "TGIS_RLAYAPI_LIST": ["C", "R", "U", "D"]
     *   }
     * }
     */
    private Map<String, Map<String, Set<CrudOperation>>> matrix;
    
    /**
     * 통계 정보
     */
    private Statistics statistics;
    
    /**
     * CRUD 작업 타입
     */
    public enum CrudOperation {
        CREATE("C", "생성"),
        READ("R", "조회"),
        UPDATE("U", "수정"),
        DELETE("D", "삭제");
        
        private final String code;
        private final String description;
        
        CrudOperation(String code, String description) {
            this.code = code;
            this.description = description;
        }
        
        public String getCode() {
            return code;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 통계 정보
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Statistics {
        /**
         * 전체 서비스 수
         */
        private int totalServices;
        
        /**
         * 전체 테이블 수
         */
        private int totalTables;
        
        /**
         * 전체 관계 수
         */
        private int totalRelations;
        
        /**
         * 공유 테이블 (2개 이상 서비스가 접근)
         */
        private List<SharedTable> sharedTables;
        
        /**
         * 고립 테이블 (어떤 서비스도 접근 안 함)
         */
        private List<String> orphanTables;
    }
    
    /**
     * 공유 테이블 정보
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SharedTable {
        /**
         * 테이블명
         */
        private String tableName;
        
        /**
         * 접근하는 서비스 목록
         */
        private List<String> accessedByServices;
        
        /**
         * 접근 서비스 수
         */
        private int accessCount;
        
        /**
         * 커플링 위험도 (HIGH, MEDIUM, LOW)
         */
        private String couplingRisk;
    }
}
