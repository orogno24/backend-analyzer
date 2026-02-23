package com.analyzer.controller;

import com.analyzer.rag.CodeChunk;
import com.analyzer.rag.SearchBundle;
import com.analyzer.rag.ai.BcRecommendation;
import com.analyzer.rag.ai.BoundedContextGroup;
import com.analyzer.rag.ai.CrudMatrix;
import com.analyzer.rag.ai.DomainService;
import com.analyzer.rag.ai.DomainInteractionMatrixService;
import com.analyzer.service.RagAnalysisService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 기반 분석 REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/projects/{projectId}/rag")
@RequiredArgsConstructor
public class RagAnalysisController {
    
    private final RagAnalysisService ragAnalysisService;
    
    /**
     * 프로젝트를 RAG 시스템에 인덱싱
     * POST /api/projects/{projectId}/rag/index
     */
    @PostMapping("/index")
    public ResponseEntity<Map<String, Object>> indexProject(@PathVariable String projectId) {
        log.info("RAG 인덱싱 요청 - projectId: {}", projectId);
        
        int chunkCount = ragAnalysisService.indexProject(projectId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("projectId", projectId);
        response.put("chunkCount", chunkCount);
        response.put("message", "인덱싱 완료");
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * RAG + AI: BC 자동 분석 (추천!) ⭐
     * POST /api/projects/{projectId}/rag/analyze-bc
     */
    @PostMapping("/analyze-bc")
    public ResponseEntity<BcAnalysisResponse> analyzeBc(
            @PathVariable String projectId,
            @RequestBody QueryRequest request) {
        
        log.info("RAG + AI BC 분석 - projectId: {}, query: '{}'", projectId, request.getQuery());
        
        // BC 분석 실행
        List<BcRecommendation> recommendations = ragAnalysisService.analyzeBcWithAi(
                request.getQuery(),
                request.getMaxResults() != null ? request.getMaxResults() : 5
        );
        
        // 응답 생성
        BcAnalysisResponse response = new BcAnalysisResponse();
        response.setQuery(request.getQuery());
        response.setRecommendationCount(recommendations.size());
        response.setRecommendations(recommendations);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * RAG + AI: BC 그룹 분석 (개별 컴포넌트 → BC 단위로 묶기) ⭐⭐⭐
     * POST /api/projects/{projectId}/rag/analyze-bc-groups
     */
    @PostMapping("/analyze-bc-groups")
    public ResponseEntity<BcGroupAnalysisResponse> analyzeBcGroups(
            @PathVariable String projectId,
            @RequestBody QueryRequest request) {
        
        log.info("RAG + AI BC 그룹 분석 - projectId: {}, query: '{}'", projectId, request.getQuery());
        
        // BC 그룹 분석 실행 (⭐ projectId 전달)
        List<BoundedContextGroup> bcGroups = ragAnalysisService.analyzeBcGroupsWithAi(
                projectId,
                request.getQuery(),
                request.getMaxResults() != null ? request.getMaxResults() : 20
        );
        
        // 응답 생성
        BcGroupAnalysisResponse response = new BcGroupAnalysisResponse();
        response.setQuery(request.getQuery());
        response.setBcCount(bcGroups.size());
        response.setTotalComponents(bcGroups.stream()
                .mapToInt(BoundedContextGroup::getComponentCount)
                .sum());
        response.setBcGroups(bcGroups);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * RAG 기반 질의 (Bundle 포함) - 추천!
     * POST /api/projects/{projectId}/rag/query-bundle
     */
    @PostMapping("/query-bundle")
    public ResponseEntity<BundleQueryResponse> queryWithBundle(
            @PathVariable String projectId,
            @RequestBody QueryRequest request) {
        
        log.info("RAG Bundle 질의 - projectId: {}, query: '{}'", projectId, request.getQuery());
        
        // Bundle 검색 실행
        List<SearchBundle> bundles = ragAnalysisService.queryWithBundle(
                request.getQuery(), 
                request.getMaxResults() != null ? request.getMaxResults() : 5
        );
        
        // 응답 생성
        BundleQueryResponse response = new BundleQueryResponse();
        response.setQuery(request.getQuery());
        response.setResultCount(bundles.size());
        response.setBundles(bundles);
        response.setTotalEvidence(bundles.stream()
                .mapToInt(SearchBundle::getTotalEvidence)
                .sum());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * RAG 기반 질의
     * POST /api/projects/{projectId}/rag/query
     */
    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(
            @PathVariable String projectId,
            @RequestBody QueryRequest request) {
        
        log.info("RAG 질의 - projectId: {}, query: '{}'", projectId, request.getQuery());
        
        // 질의 실행
        List<CodeChunk> results = ragAnalysisService.query(
                request.getQuery(), 
                request.getMaxResults() != null ? request.getMaxResults() : 10
        );
        
        // 응답 생성
        QueryResponse response = new QueryResponse();
        response.setQuery(request.getQuery());
        response.setResultCount(results.size());
        response.setResults(results);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 인덱싱 상태 조회
     * GET /api/projects/{projectId}/rag/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String projectId) {
        int indexedCount = ragAnalysisService.getIndexedCount();
        
        Map<String, Object> response = new HashMap<>();
        response.put("projectId", projectId);
        response.put("indexedChunks", indexedCount);
        response.put("status", indexedCount > 0 ? "INDEXED" : "NOT_INDEXED");
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 특정 컴포넌트의 chunk 상세 조회 (디버깅용) 🔍
     * GET /api/projects/{projectId}/rag/chunk/{componentName}
     */
    @GetMapping("/chunk/{componentName}")
    public ResponseEntity<Map<String, Object>> getChunkDetail(
            @PathVariable String projectId,
            @PathVariable String componentName) {
        
        CodeChunk chunk = ragAnalysisService.getChunk(componentName);
        
        Map<String, Object> response = new HashMap<>();
        if (chunk != null) {
            response.put("name", chunk.getName());
            response.put("type", chunk.getType());
            response.put("componentType", chunk.getComponentType());
            response.put("dependencies", chunk.getDependencies());
            response.put("myBatisCalls", chunk.getMyBatisCalls());
            response.put("accessedTables", chunk.getAccessedTables());
            response.put("filePath", chunk.getFilePath());
        } else {
            response.put("error", "Chunk not found: " + componentName);
        }
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 질의 요청
     */
    @Data
    public static class QueryRequest {
        private String query;
        private Integer maxResults;
    }
    
    /**
     * 질의 응답
     */
    @Data
    public static class QueryResponse {
        private String query;
        private int resultCount;
        private List<CodeChunk> results;
    }
    
    /**
     * Bundle 질의 응답
     */
    @Data
    public static class BundleQueryResponse {
        private String query;
        private int resultCount;
        private int totalEvidence;
        private List<SearchBundle> bundles;
    }
    
    /**
     * BC 분석 응답
     */
    @Data
    public static class BcAnalysisResponse {
        private String query;
        private int recommendationCount;
        private List<BcRecommendation> recommendations;
    }
    
    /**
     * BC 그룹 분석 응답
     */
    @Data
    public static class BcGroupAnalysisResponse {
        private String query;
        private int bcCount;
        private int totalComponents;
        private List<BoundedContextGroup> bcGroups;
    }
    
    /**
     * CRUD Matrix 조회 (서비스 ↔ 테이블 관계) ⭐⭐⭐
     * GET /api/projects/{projectId}/rag/crud-matrix
     * 
     * ⭐ v2: BC 독립 - CodeChunk에서 직접 생성 (빠름, 1~2초)
     */
    @GetMapping("/crud-matrix")
    public ResponseEntity<CrudMatrix> getCrudMatrix(@PathVariable String projectId) {
        log.info("CRUD Matrix 조회 - projectId: {}", projectId);
        
        // ⭐ BC 분석 없이 CRUD Matrix 생성 (1~2초)
        CrudMatrix matrix = ragAnalysisService.getCrudMatrix();
        
        return ResponseEntity.ok(matrix);
    }
    
    /**
     * CRUD Matrix 조회 (BC 기반, 느림, deprecated)
     * GET /api/projects/{projectId}/rag/crud-matrix-with-bc
     * 
     * @deprecated BC 독립화 후 /crud-matrix 사용 권장
     */
    @Deprecated
    @GetMapping("/crud-matrix-with-bc")
    public ResponseEntity<CrudMatrix> getCrudMatrixWithBc(@PathVariable String projectId) {
        log.info("CRUD Matrix 조회 (BC 기반, deprecated) - projectId: {}", projectId);
        
        // 기존 방식: BC 그룹 캐시 사용 (최초 1회 23분)
        CrudMatrix matrix = ragAnalysisService.getCrudMatrixWithBc(projectId);
        
        return ResponseEntity.ok(matrix);
    }
    
    /**
     * 마이크로서비스 분리 추천 조회 ⭐⭐⭐
     * GET /api/projects/{projectId}/rag/recommendations
     */
    @GetMapping("/recommendations")
    public ResponseEntity<RecommendationResponse> getRecommendations(
            @PathVariable String projectId) {
        
        log.info("마이크로서비스 분리 추천 조회 - projectId: {}", projectId);
        
        List<com.analyzer.rag.ai.MicroserviceRecommendation> recommendations = 
                ragAnalysisService.getRecommendations(projectId);
        
        RecommendationResponse response = new RecommendationResponse();
        response.setProjectId(projectId);
        response.setTotalBC(recommendations.size());
        response.setRecommendations(recommendations);
        
        // 통계 계산
        long gradeA = recommendations.stream().filter(r -> "A".equals(r.getGrade())).count();
        long gradeB = recommendations.stream().filter(r -> "B".equals(r.getGrade())).count();
        long gradeC = recommendations.stream().filter(r -> "C".equals(r.getGrade())).count();
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("gradeA", gradeA);
        summary.put("gradeB", gradeB);
        summary.put("gradeC", gradeC);
        summary.put("immediatelySeparable", gradeA);
        summary.put("needsRefactoring", gradeB);
        summary.put("notRecommended", gradeC);
        
        response.setSummary(summary);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * ⭐⭐⭐ 도메인 서비스 추천 (BC → 도메인)
     * GET /api/projects/{projectId}/rag/domains
     */
    @GetMapping("/domains")
    public ResponseEntity<List<DomainService.Domain>> getDomains(
            @PathVariable String projectId) {
        
        log.info("도메인 서비스 추천 조회 - projectId: {}", projectId);
        
        List<DomainService.Domain> domains = 
                ragAnalysisService.getDomains(projectId);
        
        return ResponseEntity.ok(domains);
    }
    
    /**
     * ⭐⭐⭐ 도메인 간 호출 관계 매트릭스
     * GET /api/projects/{projectId}/rag/domain-matrix
     */
    @GetMapping("/domain-matrix")
    public ResponseEntity<DomainInteractionMatrixService.DomainInteractionMatrix> getDomainMatrix(
            @PathVariable String projectId) {
        
        log.info("도메인 간 호출 관계 매트릭스 조회 - projectId: {}", projectId);
        
        DomainInteractionMatrixService.DomainInteractionMatrix matrix = 
                ragAnalysisService.getDomainMatrix(projectId);
        
        return ResponseEntity.ok(matrix);
    }
    
    /**
     * 추천 응답
     */
    @Data
    public static class RecommendationResponse {
        private String projectId;
        private int totalBC;
        private Map<String, Object> summary;
        private List<com.analyzer.rag.ai.MicroserviceRecommendation> recommendations;
    }
}
