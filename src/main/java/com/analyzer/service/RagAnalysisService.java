package com.analyzer.service;

import com.analyzer.rag.CodeChunk;
import com.analyzer.rag.CodeChunker;
import com.analyzer.rag.RagService;
import com.analyzer.rag.SearchBundle;
import com.analyzer.rag.ai.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * RAG 기반 프로젝트 분석 서비스
 * 
 * 역할:
 * 1. 프로젝트 코드를 청크로 분할
 * 2. 청크를 벡터 DB에 인덱싱
 * 3. RAG 기반 질의응답
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagAnalysisService {
    
    private final FileScanService fileScanService;
    private final CodeChunker codeChunker;
    private final RagService ragService;
    private final BcAnalysisService bcAnalysisService;
    private final BcGroupingService bcGroupingService;
    private final CrudMatrixService crudMatrixService;
    private final RecommendationService recommendationService;
    private final DomainService domainService;
    private final DomainInteractionMatrixService domainMatrixService;
    
    @Value("${analyzer.storage.projects-dir}")
    private String projectsDir;
    
    // ⭐ BC 그룹 캐시 (CRUD Matrix 용)
    private List<BoundedContextGroup> cachedBcGroupsForCrudMatrix = null;
    
    private Path getProjectsBaseDir() {
        return Paths.get(projectsDir).toAbsolutePath().normalize();
    }
    
    /**
     * BC 분석 캐시 삭제 ⭐⭐⭐
     * 
     * @param projectId 프로젝트 ID
     * @return 삭제 성공 여부
     */
    public boolean deleteBcAnalysisCache(String projectId) {
        log.info("BC 분석 캐시 삭제 - projectId: {}", projectId);
        
        // BcAnalysisService를 통해 캐시 삭제
        return bcAnalysisService.deleteBcAnalysisCache(projectId);
    }
    
    /**
     * 프로젝트를 RAG 시스템에 인덱싱
     * 
     * @param projectId 프로젝트 ID
     * @return 인덱싱된 청크 수
     */
    public int indexProject(String projectId) {
        log.info("RAG 인덱싱 시작 - projectId: {}", projectId);
        
        try {
            // 1. 프로젝트 경로
            Path repoPath = getProjectsBaseDir()
                    .resolve(projectId)
                    .resolve("repo");
            
            if (!repoPath.toFile().exists()) {
                throw new RuntimeException("프로젝트 경로가 없습니다: " + repoPath);
            }
            
            // 2. Java 파일 스캔
            List<Path> javaFiles = fileScanService.scanJavaFiles(repoPath.toString());
            log.info("Java 파일 {}개 발견", javaFiles.size());
            
            // 3. 파일을 청크로 분할
            List<CodeChunk> chunks = codeChunker.chunkFiles(javaFiles);
            log.info("청크 {}개 생성", chunks.size());
            
            // 4. RAG 시스템에 인덱싱 (projectId 전달) ⭐
            ragService.indexChunks(chunks, projectId);
            
            // 5. BC 분석 서비스 프로젝트 초기화 (캐시 로드) ⭐
            bcAnalysisService.initProject(projectId);
            
            // 6. BC 그룹 캐시 초기화 ⭐
            cachedBcGroupsForCrudMatrix = null;
            
            log.info("RAG 인덱싱 완료 - projectId: {}, 청크: {}개", projectId, chunks.size());
            
            return chunks.size();
            
        } catch (Exception e) {
            log.error("RAG 인덱싱 실패", e);
            throw new RuntimeException("RAG 인덱싱 중 오류 발생: " + e.getMessage(), e);
        }
    }
    
    /**
     * RAG + AI: BC 자동 분석
     * 
     * @param query 질의
     * @param maxResults 최대 결과 수
     * @return BC 추천 리스트
     */
    public List<BcRecommendation> analyzeBcWithAi(String query, int maxResults) {
        log.info("RAG + AI BC 분석 시작: '{}'", query);
        
        // 1. Bundle 검색
        List<SearchBundle> bundles = queryWithBundle(query, maxResults);
        
        // 2. AI 분석 (query 전달)
        List<BcRecommendation> recommendations = bcAnalysisService.analyzeBoundedContexts(bundles, query);
        
        log.info("BC 분석 완료: {}개", recommendations.size());
        
        return recommendations;
    }
    
    /**
     * 단일 Bundle AI 분석
     */
    public BcRecommendation analyzeSingleBc(SearchBundle bundle) {
        return bcAnalysisService.analyzeBoundedContext(bundle);
    }
    
    /**
     * BC 그룹핑 (개별 컴포넌트 → BC 단위로 묶기)
     * 
     * @param projectId 프로젝트 ID (캐싱용) ⭐
     * @param query 질의
     * @param maxResults 최대 결과 수
     * @return BC 그룹 리스트
     */
    public List<BoundedContextGroup> analyzeBcGroupsWithAi(String projectId, String query, int maxResults) {
        log.info("RAG + AI BC 그룹 분석 시작: '{}' (projectId: {})", query, projectId);
        
        // ⭐ 프로젝트 초기화 (캐시 로드)
        bcAnalysisService.initProject(projectId);
        
        // 1. 개별 컴포넌트 분석
        List<BcRecommendation> recommendations = analyzeBcWithAi(query, maxResults);
        
        // 2. BC로 그룹핑
        List<BoundedContextGroup> bcGroups = bcGroupingService.groupByBoundedContext(recommendations);
        
        log.info("BC 그룹 분석 완료: {}개 BC", bcGroups.size());
        
        return bcGroups;
    }
    
    /**
     * RAG 기반 질의 (Bundle 포함)
     * 
     * @param query 질의
     * @param maxResults 최대 결과 수
     * @return Bundle 리스트 (근거 포함)
     */
    public List<SearchBundle> queryWithBundle(String query, int maxResults) {
        log.info("RAG Bundle 질의: '{}'", query);
        
        List<SearchBundle> bundles = ragService.searchWithBundle(query, maxResults);
        
        log.info("질의 결과: {}개 Bundle, 총 근거: {}개", 
                bundles.size(),
                bundles.stream().mapToInt(SearchBundle::getTotalEvidence).sum());
        
        return bundles;
    }
    
    /**
     * RAG 기반 질의
     * 
     * @param query 질의 (예: "EmsController는 어떤 도메인인가?")
     * @param maxResults 최대 결과 수
     * @return 관련 코드 청크 리스트
     */
    public List<CodeChunk> query(String query, int maxResults) {
        log.info("RAG 질의: '{}'", query);
        
        List<CodeChunk> results = ragService.search(query, maxResults);
        
        log.info("질의 결과: {}개", results.size());
        
        return results;
    }
    
    /**
     * 인덱싱된 청크 수 조회
     */
    public int getIndexedCount() {
        return ragService.getIndexedCount();
    }
    
    /**
     * 특정 컴포넌트의 CodeChunk 조회 (디버깅용)
     */
    public CodeChunk getChunk(String componentName) {
        return ragService.getChunkByComponentName(componentName);
    }
    
    /**
     * CRUD Matrix 생성 (BC 독립, 빠름) ⭐⭐⭐
     */
    public CrudMatrix getCrudMatrix() {
        log.info("CRUD Matrix 조회 (BC 독립 방식)");
        
        // ⭐ CodeChunk에서 직접 CRUD Matrix 생성 (1~2초)
        return crudMatrixService.createMatrixFromChunks();
    }
    
    /**
     * CRUD Matrix 생성 (BC 그룹 기반, 느림, deprecated)
     * 
     * @deprecated BC 독립화 후 getCrudMatrix() 사용 권장
     */
    @Deprecated
    public CrudMatrix getCrudMatrixWithBc(String projectId) {
        log.info("CRUD Matrix 조회 (BC 기반 방식, deprecated) - projectId: {}", projectId);
        
        // 캐시된 BC 그룹이 없으면 새로 분석 (23분)
        if (cachedBcGroupsForCrudMatrix == null || cachedBcGroupsForCrudMatrix.isEmpty()) {
            log.info("CRUD Matrix용 BC 그룹 캐시 미스 - 새로 분석 시작");
            cachedBcGroupsForCrudMatrix = analyzeBcGroupsWithAi(projectId, "Controller", 100);
            log.info("BC 그룹 분석 완료: {}개 그룹 캐시됨", cachedBcGroupsForCrudMatrix.size());
        } else {
            log.info("CRUD Matrix용 BC 그룹 캐시 히트 - 캐시된 {}개 그룹 사용", 
                    cachedBcGroupsForCrudMatrix.size());
        }
        
        return crudMatrixService.createMatrix(cachedBcGroupsForCrudMatrix);
    }
    
    /**
     * CRUD Matrix 생성 (외부 BC 그룹 제공)
     */
    public CrudMatrix createCrudMatrix(List<BoundedContextGroup> bcGroups) {
        return crudMatrixService.createMatrix(bcGroups);
    }
    
    /**
     * 마이크로서비스 분리 추천 생성 ⭐⭐⭐
     */
    public List<com.analyzer.rag.ai.MicroserviceRecommendation> getRecommendations(String projectId) {
        log.info("마이크로서비스 분리 추천 생성 (projectId: {})", projectId);
        
        // 1. BC 그룹 가져오기 (캐시 있으면 1~2초)
        if (cachedBcGroupsForCrudMatrix == null || cachedBcGroupsForCrudMatrix.isEmpty()) {
            log.info("추천용 BC 그룹 캐시 미스 - 새로 분석");
            cachedBcGroupsForCrudMatrix = analyzeBcGroupsWithAi(projectId, "Controller", 100);
        }
        
        // 2. CRUD Matrix 가져오기
        CrudMatrix crudMatrix = crudMatrixService.createMatrixFromChunks();
        
        // 3. 추천 생성
        return recommendationService.generateRecommendations(
                cachedBcGroupsForCrudMatrix, 
                crudMatrix);
    }
    
    /**
     * ⭐⭐⭐ 도메인 서비스 추천 (BC → 도메인)
     */
    public List<DomainService.Domain> getDomains(String projectId) {
        log.info("도메인 서비스 추천 생성 (projectId: {})", projectId);
        
        // 1. BC 그룹 가져오기
        if (cachedBcGroupsForCrudMatrix == null || cachedBcGroupsForCrudMatrix.isEmpty()) {
            log.info("도메인 생성용 BC 그룹 캐시 미스 - 새로 분석");
            cachedBcGroupsForCrudMatrix = analyzeBcGroupsWithAi(projectId, "Controller", 100);
        }
        
        // 2. 도메인 생성 (목표: 6개)
        return domainService.createDomains(cachedBcGroupsForCrudMatrix, 6);
    }
    
    /**
     * ⭐⭐⭐ 도메인 간 호출 관계 매트릭스
     */
    public DomainInteractionMatrixService.DomainInteractionMatrix getDomainMatrix(String projectId) {
        log.info("도메인 간 호출 관계 매트릭스 생성 (projectId: {})", projectId);
        
        // 1. 도메인 목록 가져오기
        List<DomainService.Domain> domains = getDomains(projectId);
        
        // 2. BC 그룹 (캐시됨)
        if (cachedBcGroupsForCrudMatrix == null || cachedBcGroupsForCrudMatrix.isEmpty()) {
            log.info("BC 그룹 캐시 미스");
            cachedBcGroupsForCrudMatrix = analyzeBcGroupsWithAi(projectId, "Controller", 100);
        }
        
        // 3. 매트릭스 생성
        return domainMatrixService.createMatrix(domains, cachedBcGroupsForCrudMatrix);
    }
}
