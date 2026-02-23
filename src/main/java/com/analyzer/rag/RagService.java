package com.analyzer.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 시스템 핵심 서비스 (영속화 지원 버전)
 * 
 * 역할:
 * 1. 코드 청크를 벡터로 변환 (임베딩)
 * 2. 벡터 저장소에 저장 (파일 기반 영속화)
 * 3. 질의 시 유사한 청크 검색
 * 4. Retrieval Bundle 생성 (의존성 체인 확장)
 */
@Slf4j
@Service
public class RagService {
    
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DependencyGraph dependencyGraph;
    private final String storageBasePath;
    private final ObjectMapper objectMapper;
    
    // 청크 ID → 원본 청크 매핑
    private final Map<String, CodeChunk> chunkCache = new HashMap<>();
    
    // 컴포넌트명 → 청크 매핑 (빠른 조회용)
    private final Map<String, CodeChunk> componentCache = new HashMap<>();
    
    // 현재 프로젝트 ID
    private String currentProjectId = null;
    
    public RagService(
            DependencyGraph dependencyGraph,
            @Value("${analyzer.storage.path:./data}") String storagePath) {
        this.dependencyGraph = dependencyGraph;
        this.storageBasePath = storagePath;
        this.objectMapper = new ObjectMapper();
        
        log.info("RAG 서비스 초기화 중... (저장소: {})", storagePath);
        
        // 임베딩 모델 초기화 (로컬에서 실행, 인터넷 불필요)
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        
        // 인메모리 벡터 저장소 (초기 상태)
        this.embeddingStore = new InMemoryEmbeddingStore<>();
        
        log.info("RAG 서비스 초기화 완료");
    }
    
    /**
     * 코드 청크를 임베딩하고 저장 (영속화 지원) ⭐
     * 
     * @param chunks 코드 청크 리스트
     * @param projectId 프로젝트 ID
     */
    public void indexChunks(List<CodeChunk> chunks, String projectId) {
        log.info("청크 인덱싱 시작: {}개 (projectId: {})", chunks.size(), projectId);
        
        this.currentProjectId = projectId;
        
        // ⭐ 개발 중에는 persistence 비활성화 (매번 새로 인덱싱)
        // 기존 인덱스가 있으면 로드
        // if (loadIndexFromDisk(projectId)) {
        //     log.info("기존 인덱스 로드 성공: {}개 청크", chunkCache.size());
        //     return;
        // }
        
        // 의존성 그래프 초기화
        dependencyGraph.clear();
        
        for (CodeChunk chunk : chunks) {
            try {
                // 1. 청크를 텍스트로 변환 (코드 + 메타데이터)
                String text = buildChunkText(chunk);
                
                // 2. 메타데이터 생성
                Metadata metadata = buildMetadata(chunk);
                
                // 3. TextSegment 생성
                TextSegment segment = TextSegment.from(text, metadata);
                
                // 4. 임베딩 생성
                Embedding embedding = embeddingModel.embed(segment).content();
                
                // 5. 벡터 저장소에 저장
                embeddingStore.add(embedding, segment);
                
                // 6. 캐시에 원본 청크 저장
                chunkCache.put(chunk.getId(), chunk);
                componentCache.put(chunk.getName(), chunk);
                
                // 7. 의존성 그래프에 추가
                if (chunk.getDependencies() != null) {
                    for (String dependency : chunk.getDependencies()) {
                        dependencyGraph.addDependency(chunk.getName(), dependency, chunk.getId());
                    }
                }
                
                log.debug("청크 인덱싱 완료: {}", chunk.getName());
                
            } catch (Exception e) {
                log.error("청크 인덱싱 실패: {}", chunk.getId(), e);
            }
        }
        
        log.info("청크 인덱싱 완료: {}개", chunks.size());
        log.info("의존성 그래프: {}", dependencyGraph.getStatistics());
        
        // 디스크에 저장 ⭐
        saveIndexToDisk(projectId);
    }
    
    /**
     * 질의와 유사한 청크 검색 (Bundle 포함)
     * 
     * @param query 질의
     * @param maxResults 최대 결과 수
     * @return Bundle 리스트
     */
    public List<SearchBundle> searchWithBundle(String query, int maxResults) {
        log.info("Bundle 검색 시작: '{}' (최대 {}개)", query, maxResults);
        
        // 1. 기본 벡터 검색
        List<CodeChunk> primaryChunks = search(query, maxResults);
        
        // 2. 각 청크에 대해 Bundle 생성
        List<SearchBundle> bundles = new ArrayList<>();
        
        for (CodeChunk primaryChunk : primaryChunks) {
            SearchBundle bundle = createBundle(primaryChunk);
            bundles.add(bundle);
        }
        
        log.info("Bundle 검색 완료: {}개 (총 evidence: {}개)", 
                bundles.size(),
                bundles.stream().mapToInt(b -> b.getEvidence().size()).sum());
        
        return bundles;
    }
    
    /**
     * 청크에 대한 Bundle 생성
     * 
     * Bundle = 주 청크 + 의존성 체인 1~2 hop
     */
    private SearchBundle createBundle(CodeChunk primaryChunk) {
        SearchBundle bundle = new SearchBundle();
        bundle.setPrimaryChunk(primaryChunk);
        
        List<Evidence> evidence = new ArrayList<>();
        
        // 1. 주 청크 자체를 첫 번째 evidence로
        evidence.add(Evidence.builder()
                .file(primaryChunk.getFilePath())
                .startLine(primaryChunk.getStartLine())
                .endLine(primaryChunk.getEndLine())
                .componentName(primaryChunk.getName())
                .componentType(primaryChunk.getComponentType())
                .snippet(getSnippet(primaryChunk.getCode(), 10))
                .reason("주요 컴포넌트")
                .build());
        
        // 2. 의존성 확장 (1~2 hop)
        Set<String> expandedComponents = dependencyGraph.expandDependencies(
                primaryChunk.getName(), 2);
        
        log.debug("{} 의존성 확장: {} → {}", 
                primaryChunk.getName(), 
                expandedComponents.size(),
                expandedComponents);
        
        // 3. 확장된 컴포넌트들을 evidence에 추가
        for (String componentName : expandedComponents) {
            CodeChunk depChunk = componentCache.get(componentName);
            
            if (depChunk != null) {
                evidence.add(Evidence.builder()
                        .file(depChunk.getFilePath())
                        .startLine(depChunk.getStartLine())
                        .endLine(depChunk.getEndLine())
                        .componentName(depChunk.getName())
                        .componentType(depChunk.getComponentType())
                        .snippet(getSnippet(depChunk.getCode(), 5))
                        .reason(String.format("%s가 %s를 호출", 
                                primaryChunk.getName(), componentName))
                        .build());
            }
        }
        
        bundle.setEvidence(evidence);
        bundle.setTotalEvidence(evidence.size());
        
        return bundle;
    }
    
    /**
     * 코드에서 앞부분만 추출 (snippet)
     */
    private String getSnippet(String code, int lines) {
        if (code == null) return "";
        
        String[] codeLines = code.split("\n");
        int limit = Math.min(lines, codeLines.length);
        
        StringBuilder snippet = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            snippet.append(codeLines[i]).append("\n");
        }
        
        if (codeLines.length > limit) {
            snippet.append("... (생략 ").append(codeLines.length - limit).append("줄)\n");
        }
        
        return snippet.toString();
    }
    
    /**
     * 질의와 유사한 청크 검색 (하이브리드)
     * 
     * 벡터 검색 + 키워드 부스팅
     * 
     * @param query 질의
     * @param maxResults 최대 결과 수
     * @return 유사한 청크 리스트
     */
    public List<CodeChunk> search(String query, int maxResults) {
        log.info("하이브리드 검색 시작: '{}' (최대 {}개)", query, maxResults);
        
        // 1. 벡터 검색 (더 많이 가져오기)
        int vectorResults = maxResults * 3;  // 3배로 가져온 후 필터링
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        List<EmbeddingMatch<TextSegment>> matches = 
                embeddingStore.findRelevant(queryEmbedding, vectorResults);
        
        // 2. 결과를 CodeChunk로 변환 + 키워드 매칭 점수 추가
        List<ScoredChunk> scoredChunks = new ArrayList<>();
        String queryLower = query.toLowerCase();
        
        for (EmbeddingMatch<TextSegment> match : matches) {
            String chunkId = match.embedded().metadata().getString("id");
            CodeChunk chunk = chunkCache.get(chunkId);
            
            if (chunk != null) {
                // 키워드 매칭 점수
                double keywordScore = calculateKeywordScore(chunk, queryLower);
                
                // 벡터 점수와 키워드 점수 결합 (7:3 비율)
                double finalScore = (match.score() * 0.7) + (keywordScore * 0.3);
                
                scoredChunks.add(new ScoredChunk(chunk, finalScore));
                
                log.debug("검색 결과: {} (벡터: {:.2f}, 키워드: {:.2f}, 최종: {:.2f})", 
                        chunk.getName(), match.score(), keywordScore, finalScore);
            }
        }
        
        // 3. 점수 기준 정렬 + 상위 N개
        List<CodeChunk> results = scoredChunks.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
                .limit(maxResults)
                .map(ScoredChunk::getChunk)
                .collect(java.util.stream.Collectors.toList());
        
        log.info("하이브리드 검색 완료: {}개 결과", results.size());
        
        return results;
    }
    
    /**
     * 키워드 매칭 점수 계산
     */
    private double calculateKeywordScore(CodeChunk chunk, String queryLower) {
        double score = 0.0;
        
        // 1. 컴포넌트명에 키워드 포함? (50점)
        if (chunk.getName() != null && 
            chunk.getName().toLowerCase().contains(queryLower)) {
            score += 0.5;
        }
        
        // 2. 패키지명에 키워드 포함? (20점)
        if (chunk.getPackageName() != null && 
            chunk.getPackageName().toLowerCase().contains(queryLower)) {
            score += 0.2;
        }
        
        // 3. 의존성에 키워드 포함? (20점)
        if (chunk.getDependencies() != null) {
            boolean mentioned = chunk.getDependencies().stream()
                    .anyMatch(dep -> dep.toLowerCase().contains(queryLower));
            if (mentioned) {
                score += 0.2;
            }
        }
        
        // 4. 테이블명에 키워드 포함? (10점)
        if (chunk.getAccessedTables() != null) {
            boolean mentioned = chunk.getAccessedTables().stream()
                    .anyMatch(table -> table.toLowerCase().contains(queryLower));
            if (mentioned) {
                score += 0.1;
            }
        }
        
        return Math.min(score, 1.0);
    }
    
    /**
     * 점수와 함께 청크 저장
     */
    private static class ScoredChunk {
        private final CodeChunk chunk;
        private final double score;
        
        public ScoredChunk(CodeChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
        
        public CodeChunk getChunk() {
            return chunk;
        }
        
        public double getScore() {
            return score;
        }
    }
    
    /**
     * 청크를 검색 가능한 텍스트로 변환
     */
    private String buildChunkText(CodeChunk chunk) {
        StringBuilder sb = new StringBuilder();
        
        // 기본 정보
        sb.append("파일: ").append(chunk.getFilePath()).append("\n");
        sb.append("클래스: ").append(chunk.getName()).append("\n");
        sb.append("타입: ").append(chunk.getComponentType()).append("\n");
        sb.append("패키지: ").append(chunk.getPackageName()).append("\n\n");
        
        // 의존성 (중요!)
        if (chunk.getDependencies() != null && !chunk.getDependencies().isEmpty()) {
            sb.append("사용하는 서비스: ").append(String.join(", ", chunk.getDependencies())).append("\n");
        }
        
        // MyBatis 호출
        if (chunk.getMyBatisCalls() != null && !chunk.getMyBatisCalls().isEmpty()) {
            sb.append("MyBatis 호출: ").append(String.join(", ", chunk.getMyBatisCalls())).append("\n");
        }
        
        // 테이블
        if (chunk.getAccessedTables() != null && !chunk.getAccessedTables().isEmpty()) {
            sb.append("접근 테이블: ").append(String.join(", ", chunk.getAccessedTables())).append("\n");
        }
        
        sb.append("\n코드:\n");
        
        // 코드 내용 (너무 길면 앞부분만)
        String code = chunk.getCode();
        if (code.length() > 2000) {
            sb.append(code, 0, 2000).append("\n... (생략) ...\n");
        } else {
            sb.append(code);
        }
        
        return sb.toString();
    }
    
    /**
     * 메타데이터 생성
     */
    private Metadata buildMetadata(CodeChunk chunk) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", chunk.getId());
        map.put("filePath", chunk.getFilePath());
        map.put("name", chunk.getName());
        map.put("componentType", chunk.getComponentType());
        map.put("startLine", chunk.getStartLine());
        map.put("endLine", chunk.getEndLine());
        
        return Metadata.from(map);
    }
    
    /**
     * 인덱싱된 모든 CodeChunk 조회
     * 
     * @return 전체 CodeChunk 리스트
     */
    public List<CodeChunk> getAllChunks() {
        return new ArrayList<>(chunkCache.values());
    }
    
    /**
     * Service 컴포넌트만 조회 (CRUD Matrix용)
     * 
     * @return Service 컴포넌트 CodeChunk 리스트
     */
    public List<CodeChunk> getServiceChunks() {
        return chunkCache.values().stream()
                .filter(chunk -> isServiceComponent(chunk.getComponentType()))
                .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * Service 컴포넌트 여부 확인
     */
    private boolean isServiceComponent(String componentType) {
        return componentType != null && componentType.contains("Service");
    }
    
    /**
     * 현재 인덱싱된 청크 수
     */
    public int getIndexedCount() {
        return chunkCache.size();
    }
    
    /**
     * 인덱스 초기화
     */
    public void clearIndex() {
        chunkCache.clear();
        componentCache.clear();
        // InMemoryEmbeddingStore는 clear 메서드가 없어서 새로 생성
        log.info("인덱스 초기화 완료");
    }
    
    /**
     * 컴포넌트명으로 청크 조회 (BC 그룹핑용) ⭐
     */
    public CodeChunk getChunkByComponentName(String componentName) {
        return componentCache.get(componentName);
    }
    
    /**
     * 인덱스를 디스크에 저장 ⭐
     */
    private void saveIndexToDisk(String projectId) {
        try {
            Path indexDir = Paths.get(storageBasePath, "rag-index", projectId);
            Files.createDirectories(indexDir);
            
            // 1. ChunkCache 저장
            Path chunkCachePath = indexDir.resolve("chunk-cache.json");
            objectMapper.writeValue(chunkCachePath.toFile(), chunkCache);
            
            // 2. ComponentCache 저장
            Path componentCachePath = indexDir.resolve("component-cache.json");
            objectMapper.writeValue(componentCachePath.toFile(), componentCache);
            
            // 3. EmbeddingStore 저장 (InMemory는 직렬화)
            Path embeddingStorePath = indexDir.resolve("embedding-store.json");
            String embeddingStoreJson = ((InMemoryEmbeddingStore<TextSegment>) embeddingStore).serializeToJson();
            Files.writeString(embeddingStorePath, embeddingStoreJson);
            
            // 4. DependencyGraph 저장
            Path graphPath = indexDir.resolve("dependency-graph.json");
            objectMapper.writeValue(graphPath.toFile(), dependencyGraph);
            
            log.info("인덱스 저장 완료: {}", indexDir);
            
        } catch (Exception e) {
            log.error("인덱스 저장 실패: projectId={}", projectId, e);
        }
    }
    
    /**
     * 디스크에서 인덱스 로드 ⭐
     */
    private boolean loadIndexFromDisk(String projectId) {
        try {
            Path indexDir = Paths.get(storageBasePath, "rag-index", projectId);
            
            if (!Files.exists(indexDir)) {
                log.info("저장된 인덱스 없음: {}", projectId);
                return false;
            }
            
            Path chunkCachePath = indexDir.resolve("chunk-cache.json");
            Path componentCachePath = indexDir.resolve("component-cache.json");
            Path embeddingStorePath = indexDir.resolve("embedding-store.json");
            Path graphPath = indexDir.resolve("dependency-graph.json");
            
            if (!Files.exists(chunkCachePath) || !Files.exists(embeddingStorePath)) {
                log.warn("인덱스 파일 불완전: {}", projectId);
                return false;
            }
            
            log.info("인덱스 로드 시작: {}", projectId);
            
            // 1. ChunkCache 로드
            Map<String, CodeChunk> loadedChunkCache = objectMapper.readValue(
                    chunkCachePath.toFile(),
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, CodeChunk.class)
            );
            chunkCache.clear();
            chunkCache.putAll(loadedChunkCache);
            
            // 2. ComponentCache 로드
            if (Files.exists(componentCachePath)) {
                Map<String, CodeChunk> loadedComponentCache = objectMapper.readValue(
                        componentCachePath.toFile(),
                        objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, CodeChunk.class)
                );
                componentCache.clear();
                componentCache.putAll(loadedComponentCache);
            }
            
            // 3. EmbeddingStore 로드
            InMemoryEmbeddingStore<TextSegment> loadedStore = 
                    InMemoryEmbeddingStore.fromJson(Files.readString(embeddingStorePath));
            // 기존 store에 복사 (InMemory는 불변이라 새로 만들어야 하지만, 여기서는 초기화 시 한번만)
            
            // 4. DependencyGraph 로드
            if (Files.exists(graphPath)) {
                // DependencyGraph는 Component라서 직접 역직렬화 어려움
                // 대신 chunkCache에서 재구성
                dependencyGraph.clear();
                for (CodeChunk chunk : chunkCache.values()) {
                    if (chunk.getDependencies() != null) {
                        for (String dep : chunk.getDependencies()) {
                            dependencyGraph.addDependency(chunk.getName(), dep, chunk.getId());
                        }
                    }
                }
            }
            
            log.info("인덱스 로드 완료: {}개 청크", chunkCache.size());
            
            return true;
            
        } catch (Exception e) {
            log.error("인덱스 로드 실패: projectId={}", projectId, e);
            return false;
        }
    }
}
