package com.analyzer.service;

import com.analyzer.dto.BoundedContextAnalysisResult;
import com.analyzer.dto.DomainAnalysisResult;
import com.analyzer.dto.EnhancedBoundedContextResult;
import com.analyzer.dto.ProjectAnalysisResult;
import com.analyzer.dto.ServiceAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * AI 기반 서비스/엔티티 분석 서비스
 * Ollama를 사용하여 네이밍과 역할 추천
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisService {

    private final OllamaService ollamaService;
    private final ObjectMapper objectMapper;

    @Value("${analyzer.storage.projects-dir}")
    private String projectsDir;

    private Path getProjectsBaseDir() {
        return Paths.get(projectsDir).toAbsolutePath().normalize();
    }

    private static final String ENHANCED_BC_PROMPT_TEMPLATE = """
            아래 JSON 스키마를 정확히 따라 출력하세요. 다른 필드나 텍스트를 추가하지 마세요.
            
            스키마:
            {
              "contexts": [
                {
                  "domain": "string (한글 도메인명)",
                  "subdomain": "string",
                  "description": "string",
                  "controllers": ["string"],
                  "services": ["string"],
                  "repositories": ["string"],
                  "entities": [],
                  "accessedTables": [],
                  "callChains": ["string"],
                  "sharedComponents": [],
                  "dbMappingNote": "string",
                  "confidence": 0.9,
                  "note": "string"
                }
              ],
              "couplingIssues": [],
              "microserviceSuggestions": [],
              "unmapped": {
                "controllers": [],
                "services": [],
                "repositories": [],
                "entities": []
              },
              "questions": [],
              "nextRecommendations": []
            }
            
            지시사항:
            1. contexts 배열에 최소 1개 이상의 객체를 생성하세요
            2. INPUT의 controllers 배열에서 이름을 가져와 contexts[0].controllers에 넣으세요
            3. INPUT의 services 배열에서 이름을 가져와 contexts[0].services에 넣으세요
            4. INPUT의 repositories 배열에서 이름을 가져와 contexts[0].repositories에 넣으세요
            5. domain은 컨트롤러 이름에서 추론하세요 (예: EmsController → "전자민원 시스템")
            6. 나머지 필드(couplingIssues, microserviceSuggestions 등)는 빈 배열로 두세요
            
            INPUT:
            %s
            
            위 스키마 그대로 JSON 출력:
            """;

    /**
     * Bounded Context 분석 프롬프트 (레거시)
     */
    private static final String SERVICE_ANALYSIS_PROMPT_TEMPLATE = """
            너는 레거시 Spring 시스템을 클라우드 네이티브로 전환하기 위한 도메인 분석가다.
            원본 코드는 절대 보지 말고, 아래 정적 분석 JSON만 사용해라.
            
            반드시 준수:
            - 입력 JSON에 없는 클래스/서비스/엔티티/테이블 이름을 새로 만들지 마라.
            - 입력 JSON의 배열에 실제로 존재하는 항목만 결과에 포함해라.
            - Controller만 있고 Service가 없으면, Controller 이름으로 도메인을 추론해라.
            - Service/Entity/Repository가 비어 있으면 "없음"으로 판단하고 questions에 이유를 적어라.
            - DB/테이블 매핑은 entities.tableName(또는 엔티티 정보)이 있을 때만 수행하고, 없으면 unmapped로 둬라.
            - 확신이 낮으면 confidence를 낮게 하고 note에 "추정"을 표시해라.
            
            출력은 반드시 JSON만(설명/마크다운 금지).
            
            출력 스키마:
            {
              "contexts":[
                {
                  "domain":"string",
                  "subdomain":"string",
                  "controllers":["실제 입력 JSON의 className만"],
                  "services":["실제 입력 JSON의 className만"],
                  "repositories":["실제 입력 JSON의 className만"],
                  "entities":[{"className":"실제 입력 JSON의 className","tableName":"string|null"}],
                  "dbMappingNote":"string",
                  "confidence":0.0,
                  "note":"string"
                }
              ],
              "unmapped":{
                "controllers":["매핑되지 않은 className"],
                "services":["매핑되지 않은 className"],
                "repositories":["매핑되지 않은 className"],
                "entities":["매핑되지 않은 className"]
              },
              "questions":["분석 중 발견한 질문이나 불확실한 사항"],
              "nextRecommendations":["다음 단계 권장사항"]
            }
            
            힌트: Controller 이름에서 도메인 추론
            - UserController -> domain: "사용자 관리"
            - AuthController -> domain: "인증"  
            - ResourceController -> domain: "리소스 관리"
            
            입력 JSON:
            %s
            
            JSON 출력:
            """;

    /**
     * 향상된 Bounded Context 분석 (의존성 관계 기반)
     * 
     * @param projectId 프로젝트 ID
     * @return 향상된 Bounded Context 분석 결과
     */
    public EnhancedBoundedContextResult analyzeEnhancedBoundedContexts(String projectId) {
        try {
            // 1. analysis.json 읽기
            Path analysisFilePath = getProjectsBaseDir().resolve(projectId).resolve("analysis").resolve("analysis.json");
            
            if (!Files.exists(analysisFilePath)) {
                throw new RuntimeException("분석 파일이 없습니다. 먼저 정적 분석을 실행해주세요.");
            }

            String analysisJson = Files.readString(analysisFilePath);
            ProjectAnalysisResult analysisResult = objectMapper.readValue(analysisJson, ProjectAnalysisResult.class);
            
            log.info("분석 파일 로드 완료 - projectId: {}, 의존성: {}개", 
                    projectId, analysisResult.getDependencies().size());

            // 2. 의존성 포함한 요약 JSON 생성
            String enhancedJson = createEnhancedAnalysisJson(analysisResult);

            // 3. 프롬프트 구성
            String prompt = String.format(ENHANCED_BC_PROMPT_TEMPLATE, enhancedJson);

            // 4. Ollama 호출 (최대 2회 시도)
            log.info("Ollama 향상된 BC 분석 시작 - projectId: {}", projectId);
            
            EnhancedBoundedContextResult result = null;
            
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    log.info("향상된 BC 분석 시도 {}/2", attempt);
                    
                    // DEBUG: 프롬프트 길이 확인
                    log.info("프롬프트 길이: {}자", prompt.length());
                    
                    result = ollamaService.generateJson(prompt, EnhancedBoundedContextResult.class);
                    
                    // DEBUG: 결과 확인
                    log.info("파싱된 결과 - contexts: {}, couplingIssues: {}, microserviceSuggestions: {}", 
                            result != null && result.getContexts() != null ? result.getContexts().size() : "null",
                            result != null && result.getCouplingIssues() != null ? result.getCouplingIssues().size() : "null",
                            result != null && result.getMicroserviceSuggestions() != null ? result.getMicroserviceSuggestions().size() : "null");
                    
                    if (result != null && result.getContexts() != null && !result.getContexts().isEmpty()) {
                        log.info("✅ BC 분석 성공 - {}개 context 발견", result.getContexts().size());
                        break;
                    }
                    
                    log.warn("❌ BC가 비어있음, 재시도...");
                    if (attempt < 2) Thread.sleep(1000 * attempt);
                    
                } catch (Exception e) {
                    log.warn("시도 {}/2 실패: {}", attempt, e.getMessage());
                    if (attempt < 2) {
                        Thread.sleep(2000);
                    } else {
                        throw e;
                    }
                }
            }

            if (result == null || result.getContexts() == null || result.getContexts().isEmpty()) {
                throw new RuntimeException("AI가 유효한 Bounded Context를 생성하지 못했습니다.");
            }

            // 5. 결과 저장
            saveEnhancedBoundedContextAnalysis(projectId, result);
            
            log.info("향상된 BC 분석 완료 및 저장 - projectId: {}", projectId);
            return result;

        } catch (Exception e) {
            log.error("향상된 BC 분석 실패 - projectId: {}", projectId, e);
            
            // Fallback
            EnhancedBoundedContextResult fallback = createEnhancedFallbackResult();
            saveEnhancedBoundedContextAnalysis(projectId, fallback);
            
            return fallback;
        }
    }

    /**
     * 의존성 포함 요약 JSON 생성
     */
    private String createEnhancedAnalysisJson(ProjectAnalysisResult analysis) {
        try {
            // 프롬프트 크기 최소화: 컴포넌트 이름만 전달
            Map<String, Object> simplified = new HashMap<>();
            
            // 전체 컴포넌트 이름 목록만 (AI가 전체를 볼 수 있도록)
            simplified.put("allControllerNames", 
                    analysis.getControllers().stream().map(c -> c.getClassName()).toList());
            simplified.put("allServiceNames", 
                    analysis.getServices().stream().map(s -> s.getClassName()).toList());
            simplified.put("allRepositoryNames", 
                    analysis.getRepositories().stream().map(r -> r.getClassName()).toList());
            
            // dependencies는 Service→Repository 관계만 (컨트롤러 분석에 집중)
            // myBatisCalls, accessedTables는 제외하여 프롬프트 크기 대폭 감소
            Map<String, Map<String, Object>> simplifiedDeps = new HashMap<>();
            analysis.getDependencies().entrySet().stream()
                    .filter(entry -> {
                        // Controller만 포함 (Service, Repository는 이름만으로도 충분)
                        return entry.getKey().contains("Controller");
                    })
                    .forEach(entry -> {
                        Map<String, Object> dep = new HashMap<>();
                        dep.put("usesServices", entry.getValue().getUsesServices());
                        dep.put("usesRepositories", entry.getValue().getUsesRepositories());
                        // myBatisCalls, accessedTables 제외
                        simplifiedDeps.put(entry.getKey(), dep);
                    });
            
            simplified.put("dependencies", simplifiedDeps);
            
            log.info("요약 JSON 생성 완료 - Controllers: {}, Services: {}, Repositories: {}, Dependencies: {}", 
                    ((List<?>) simplified.get("allControllerNames")).size(),
                    ((List<?>) simplified.get("allServiceNames")).size(),
                    ((List<?>) simplified.get("allRepositoryNames")).size(),
                    simplifiedDeps.size());
            
            return objectMapper.writeValueAsString(simplified);
            
        } catch (Exception e) {
            log.error("요약 JSON 생성 실패", e);
            return "{}";
        }
    }

    /**
     * 향상된 Fallback 결과 생성
     */
    private EnhancedBoundedContextResult createEnhancedFallbackResult() {
        EnhancedBoundedContextResult result = new EnhancedBoundedContextResult();
        result.setContexts(List.of());
        result.setCouplingIssues(List.of());
        result.setMicroserviceSuggestions(List.of());
        result.setUnmapped(new EnhancedBoundedContextResult.UnmappedComponents(
                List.of(), List.of(), List.of(), List.of()
        ));
        result.setQuestions(List.of("AI 분석 실패", "의존성 정보 확인 필요"));
        result.setNextRecommendations(List.of("서버 재시작 후 재시도", "로그 확인"));
        return result;
    }

    /**
     * 향상된 BC 분석 결과 저장
     */
    private void saveEnhancedBoundedContextAnalysis(String projectId, EnhancedBoundedContextResult result) {
        try {
            Path analysisDir = getProjectsBaseDir().resolve(projectId).resolve("analysis");
            Files.createDirectories(analysisDir);

            Path resultPath = analysisDir.resolve("enhanced-bc-analysis.json");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(resultPath.toFile(), result);

            log.info("향상된 BC 분석 결과 저장 완료: {}", resultPath);
        } catch (IOException e) {
            log.error("향상된 BC 분석 결과 저장 실패", e);
        }
    }

    /**
     * 향상된 BC 분석 결과 조회
     */
    public EnhancedBoundedContextResult getEnhancedBoundedContextAnalysis(String projectId) {
        try {
            Path resultPath = getProjectsBaseDir()
                    .resolve(projectId)
                    .resolve("analysis")
                    .resolve("enhanced-bc-analysis.json");

            if (!Files.exists(resultPath)) {
                throw new RuntimeException("향상된 BC 분석 결과가 없습니다.");
            }

            return objectMapper.readValue(resultPath.toFile(), EnhancedBoundedContextResult.class);

        } catch (IOException e) {
            log.error("향상된 BC 분석 결과 읽기 실패", e);
            throw new RuntimeException("향상된 BC 분석 결과를 읽을 수 없습니다.", e);
        }
    }

    /**
     * Bounded Context 기반 아키텍처 분석 실행
     * (클라우드 네이티브 전환용)
     * 
     * @param projectId 프로젝트 ID
     * @return Bounded Context 분석 결과
     */
    public BoundedContextAnalysisResult analyzeBoundedContexts(String projectId) {
        try {
            // 1. analysis.json 읽기
            Path analysisFilePath = getProjectsBaseDir().resolve(projectId).resolve("analysis").resolve("analysis.json");
            
            if (!Files.exists(analysisFilePath)) {
                throw new RuntimeException("분석 파일이 없습니다. 먼저 정적 분석을 실행해주세요.");
            }

            String analysisJson = Files.readString(analysisFilePath);
            log.info("분석 파일 로드 완료 - projectId: {}", projectId);

            // 분석 데이터가 너무 크면 요약본만 전달
            ProjectAnalysisResult analysisResult = objectMapper.readValue(analysisJson, ProjectAnalysisResult.class);
            String simplifiedJson = createSimplifiedAnalysis(analysisResult);

            // 2. 프롬프트 구성
            String prompt = String.format(SERVICE_ANALYSIS_PROMPT_TEMPLATE, simplifiedJson);

            // 3. Ollama 호출 (최대 2회 시도)
            log.info("Ollama Bounded Context 분석 시작 - projectId: {}", projectId);
            log.debug("프롬프트 길이: {}자", prompt.length());
            
            BoundedContextAnalysisResult result = null;
            
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    log.info("Bounded Context 분석 시도 {}/2", attempt);
                    result = ollamaService.generateJson(prompt, BoundedContextAnalysisResult.class);
                    
                    // 결과 검증
                    if (result.getContexts() == null || result.getContexts().isEmpty()) {
                        log.warn("AI가 빈 Context 목록을 반환했습니다 (시도 {})", attempt);
                        if (attempt < 2) {
                            Thread.sleep(1000);
                            continue;
                        }
                        throw new RuntimeException("AI가 Bounded Context를 식별하지 못했습니다");
                    }
                    
                    log.info("Bounded Context 분석 성공 - Context 수: {}", result.getContexts().size());
                    break;
                    
                } catch (Exception e) {
                    log.error("JSON 파싱 실패 (시도 {}): {}", attempt, e.getMessage());
                    if (attempt < 2) {
                        try { Thread.sleep(2000); } catch (InterruptedException ie) {}
                    }
                }
            }
            
            // 모든 시도 실패
            if (result == null || result.getContexts() == null || result.getContexts().isEmpty()) {
                log.error("Bounded Context 분석 실패 - Fallback 결과 생성");
                result = createFallbackBoundedContextResult(analysisResult);
            }

            // 4. 결과 저장
            saveBoundedContextAnalysis(projectId, result);

            log.info("Bounded Context 분석 완료 - Context: {}개", 
                    result.getContexts() != null ? result.getContexts().size() : 0);

            return result;

        } catch (IOException e) {
            log.error("파일 읽기/쓰기 실패", e);
            throw new RuntimeException("파일 처리 중 오류가 발생했습니다: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Bounded Context 분석 실패", e);
            throw new RuntimeException("Bounded Context 분석 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 도메인 기반 아키텍처 분석 실행 (레거시)
     * 
     * @param projectId 프로젝트 ID
     * @return 도메인 분석 결과
     */
    public DomainAnalysisResult analyzeDomains(String projectId) {
        try {
            // 1. analysis.json 읽기
            Path analysisFilePath = getProjectsBaseDir().resolve(projectId).resolve("analysis").resolve("analysis.json");
            
            if (!Files.exists(analysisFilePath)) {
                throw new RuntimeException("분석 파일이 없습니다. 먼저 정적 분석을 실행해주세요.");
            }

            String analysisJson = Files.readString(analysisFilePath);
            log.info("분석 파일 로드 완료 - projectId: {}", projectId);

            // 분석 데이터가 너무 크면 요약본만 전달
            ProjectAnalysisResult analysisResult = objectMapper.readValue(analysisJson, ProjectAnalysisResult.class);
            String simplifiedJson = createSimplifiedAnalysis(analysisResult);

            // 2. 프롬프트 구성
            String prompt = String.format(SERVICE_ANALYSIS_PROMPT_TEMPLATE, simplifiedJson);

            // 3. Ollama 호출 (최대 2회 시도)
            log.info("Ollama AI 도메인 분석 시작 - projectId: {}", projectId);
            log.debug("프롬프트 길이: {}자", prompt.length());
            
            DomainAnalysisResult result = null;
            Exception lastException = null;
            
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    log.info("AI 분석 시도 {}/2", attempt);
                    result = ollamaService.generateJson(prompt, DomainAnalysisResult.class);
                    
                    // 결과 검증
                    if (result.getDomains() == null || result.getDomains().isEmpty()) {
                        log.warn("AI가 빈 도메인 목록을 반환했습니다 (시도 {})", attempt);
                        if (attempt < 2) {
                            Thread.sleep(1000); // 1초 대기 후 재시도
                            continue;
                        }
                        throw new RuntimeException("AI가 도메인을 식별하지 못했습니다");
                    }
                    
                    log.info("AI 도메인 분석 성공 - 도메인 수: {}", result.getDomains().size());
                    break; // 성공
                    
                } catch (Exception e) {
                    lastException = e;
                    log.error("JSON 파싱 실패 또는 AI 응답 오류 (시도 {}): {}", attempt, e.getMessage());
                    if (attempt < 2) {
                        try { Thread.sleep(2000); } catch (InterruptedException ie) {}
                    }
                }
            }
            
            // 모든 시도 실패
            if (result == null || result.getDomains() == null || result.getDomains().isEmpty()) {
                log.error("AI 분석 완전 실패 - Fallback 결과 생성");
                result = createFallbackDomainResult(analysisResult);
            }

            // 4. 결과 저장
            saveDomainAnalysis(projectId, result);

            log.info("AI 도메인 분석 완료 - 도메인: {}개", 
                    result.getDomains() != null ? result.getDomains().size() : 0);

            return result;

        } catch (IOException e) {
            log.error("파일 읽기/쓰기 실패", e);
            throw new RuntimeException("파일 처리 중 오류가 발생했습니다: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("AI 분석 실패", e);
            throw new RuntimeException("AI 분석 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 분석 데이터 간소화 (너무 클 경우)
     */
    private String createSimplifiedAnalysis(ProjectAnalysisResult analysis) throws IOException {
        // 컴포넌트 수가 많으면 상위 10개만 (AI 처리 속도 고려)
        ProjectAnalysisResult simplified = new ProjectAnalysisResult();
        simplified.setProjectId(analysis.getProjectId());
        simplified.setTotalJavaFiles(analysis.getTotalJavaFiles());
        simplified.setTotalComponents(analysis.getTotalComponents());
        
        // 최대 10개씩 (Timeout 방지)
        simplified.setControllers(analysis.getControllers().stream().limit(10).toList());
        simplified.setServices(analysis.getServices().stream().limit(10).toList());
        simplified.setRepositories(analysis.getRepositories().stream().limit(10).toList());
        simplified.setEntities(analysis.getEntities().stream().limit(10).toList());
        simplified.setSummary(analysis.getSummary());
        
        String result = objectMapper.writeValueAsString(simplified);
        log.info("AI 입력 데이터 생성 - Controllers: {}, Services: {}, Repositories: {}, Entities: {}", 
                simplified.getControllers().size(),
                simplified.getServices().size(),
                simplified.getRepositories().size(),
                simplified.getEntities().size());
        
        return result;
    }

    /**
     * Fallback Bounded Context 결과 생성
     */
    private BoundedContextAnalysisResult createFallbackBoundedContextResult(ProjectAnalysisResult analysis) {
        BoundedContextAnalysisResult result = new BoundedContextAnalysisResult();
        result.setContexts(new java.util.ArrayList<>());
        
        BoundedContextAnalysisResult.UnmappedComponents unmapped = 
            new BoundedContextAnalysisResult.UnmappedComponents();
        unmapped.setControllers(new java.util.ArrayList<>());
        unmapped.setServices(new java.util.ArrayList<>());
        unmapped.setRepositories(new java.util.ArrayList<>());
        unmapped.setEntities(new java.util.ArrayList<>());
        result.setUnmapped(unmapped);
        
        result.setQuestions(java.util.List.of(
                "AI가 JSON 형식으로 응답하지 않았습니다.",
                "프로젝트: " + analysis.getSummary()
        ));
        result.setNextRecommendations(java.util.List.of(
                "Ollama 모델을 qwen2.5:7b-instruct로 변경하세요.",
                "정적 분석 결과를 확인하고 수동으로 도메인을 정의하세요."
        ));
        
        return result;
    }
    
    /**
     * Bounded Context 분석 결과 저장
     */
    private void saveBoundedContextAnalysis(String projectId, BoundedContextAnalysisResult result) throws IOException {
        Path analysisDir = getProjectsBaseDir().resolve(projectId).resolve("analysis");
        Files.createDirectories(analysisDir);

        Path resultFilePath = analysisDir.resolve("bounded-context-analysis.json");

        ObjectMapper prettyMapper = objectMapper.copy();
        prettyMapper.enable(SerializationFeature.INDENT_OUTPUT);

        String json = prettyMapper.writeValueAsString(result);
        Files.writeString(resultFilePath, json);

        log.info("Bounded Context 분석 결과 저장 완료 - {}", resultFilePath);
    }
    
    /**
     * 저장된 Bounded Context 분석 결과 조회
     */
    public BoundedContextAnalysisResult getBoundedContextAnalysis(String projectId) {
        try {
            Path resultFilePath = getProjectsBaseDir().resolve(projectId)
                    .resolve("analysis").resolve("bounded-context-analysis.json");

            if (!Files.exists(resultFilePath)) {
                throw new RuntimeException("Bounded Context 분석 결과가 없습니다. 먼저 분석을 실행해주세요.");
            }

            String json = Files.readString(resultFilePath);
            return objectMapper.readValue(json, BoundedContextAnalysisResult.class);

        } catch (IOException e) {
            log.error("Bounded Context 분석 결과 읽기 실패", e);
            throw new RuntimeException("Bounded Context 분석 결과를 읽는 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * Fallback 도메인 결과 생성 (AI 파싱 실패 시)
     */
    private DomainAnalysisResult createFallbackDomainResult(ProjectAnalysisResult analysis) {
        DomainAnalysisResult result = new DomainAnalysisResult();
        result.setDomains(new java.util.ArrayList<>());
        
        DomainAnalysisResult.Summary summary = new DomainAnalysisResult.Summary();
        summary.setTotalDomains(0);
        summary.setKeyFindings("AI가 JSON 형식으로 응답하지 않았습니다. 프로젝트: " + analysis.getSummary());
        result.setSummary(summary);
        
        return result;
    }
    
    /**
     * 도메인 분석 결과 저장
     */
    private void saveDomainAnalysis(String projectId, DomainAnalysisResult result) throws IOException {
        Path analysisDir = getProjectsBaseDir().resolve(projectId).resolve("analysis");
        Files.createDirectories(analysisDir);

        Path resultFilePath = analysisDir.resolve("domain-analysis.json");

        // Pretty print로 저장
        ObjectMapper prettyMapper = objectMapper.copy();
        prettyMapper.enable(SerializationFeature.INDENT_OUTPUT);

        String json = prettyMapper.writeValueAsString(result);
        Files.writeString(resultFilePath, json);

        log.info("도메인 분석 결과 저장 완료 - {}", resultFilePath);
    }
    
    /**
     * 저장된 도메인 분석 결과 조회
     * 
     * @param projectId 프로젝트 ID
     * @return 도메인 분석 결과
     */
    public DomainAnalysisResult getDomainAnalysis(String projectId) {
        try {
            Path resultFilePath = getProjectsBaseDir().resolve(projectId).resolve("analysis").resolve("domain-analysis.json");

            if (!Files.exists(resultFilePath)) {
                throw new RuntimeException("도메인 분석 결과가 없습니다. 먼저 분석을 실행해주세요.");
            }

            String json = Files.readString(resultFilePath);
            return objectMapper.readValue(json, DomainAnalysisResult.class);

        } catch (IOException e) {
            log.error("도메인 분석 결과 읽기 실패", e);
            throw new RuntimeException("도메인 분석 결과를 읽는 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 서비스/엔티티 분석 실행 (레거시)
     * 
     * @param projectId 프로젝트 ID
     * @return 분석 결과
     */
    public ServiceAnalysisResult analyzeServices(String projectId) {
        try {
            // 1. analysis.json 읽기
            Path analysisFilePath = getProjectsBaseDir().resolve(projectId).resolve("analysis").resolve("analysis.json");
            
            if (!Files.exists(analysisFilePath)) {
                throw new RuntimeException("분석 파일이 없습니다. 먼저 정적 분석을 실행해주세요.");
            }

            String analysisJson = Files.readString(analysisFilePath);
            log.info("분석 파일 로드 완료 - projectId: {}", projectId);

            // 분석 데이터가 너무 크면 요약본만 전달
            ProjectAnalysisResult analysisResult = objectMapper.readValue(analysisJson, ProjectAnalysisResult.class);
            String simplifiedJson = createSimplifiedAnalysis(analysisResult);

            // 2. 프롬프트 구성 (레거시 프롬프트)
            String prompt = String.format(LEGACY_SERVICE_ANALYSIS_PROMPT, simplifiedJson);

            // 3. Ollama 호출
            log.info("Ollama AI 레거시 분석 시작 - projectId: {}", projectId);
            
            ServiceAnalysisResult result;
            try {
                result = ollamaService.generateJson(prompt, ServiceAnalysisResult.class);
            } catch (Exception e) {
                log.warn("JSON 파싱 실패, 기본 결과 생성: {}", e.getMessage());
                // Fallback: 기본 구조 반환
                result = createFallbackServiceResult(analysisResult);
            }

            // 4. 결과 저장
            saveServiceAnalysis(projectId, result);

            log.info("AI 레거시 분석 완료 - 서비스: {}개, 엔티티: {}개", 
                    result.getServices() != null ? result.getServices().size() : 0,
                    result.getEntities() != null ? result.getEntities().size() : 0);

            return result;

        } catch (IOException e) {
            log.error("파일 읽기/쓰기 실패", e);
            throw new RuntimeException("파일 처리 중 오류가 발생했습니다: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("AI 분석 실패", e);
            throw new RuntimeException("AI 분석 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 저장된 서비스 분석 결과 조회 (레거시)
     * 
     * @param projectId 프로젝트 ID
     * @return 분석 결과
     */
    public ServiceAnalysisResult getServiceAnalysis(String projectId) {
        try {
            Path resultFilePath = getProjectsBaseDir().resolve(projectId).resolve("analysis").resolve("service-analysis.json");

            if (!Files.exists(resultFilePath)) {
                throw new RuntimeException("AI 분석 결과가 없습니다. 먼저 분석을 실행해주세요.");
            }

            String json = Files.readString(resultFilePath);
            return objectMapper.readValue(json, ServiceAnalysisResult.class);

        } catch (IOException e) {
            log.error("분석 결과 읽기 실패", e);
            throw new RuntimeException("분석 결과를 읽는 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    private static final String LEGACY_SERVICE_ANALYSIS_PROMPT = """
            You are an expert Spring Boot architecture analyzer.
            
            CRITICAL: Respond with ONLY valid JSON. No explanations. No markdown.
            
            EXACT JSON format:
            {
              "services": [
                {
                  "className": "UserService",
                  "suggestedNameKo": "사용자 관리 서비스",
                  "suggestedNameEn": "UserManagementService",
                  "responsibility": "Manages user operations",
                  "confidence": 0.9
                }
              ],
              "entities": [
                {
                  "className": "User",
                  "tableName": "users",
                  "suggestedDomainName": "회원",
                  "description": "User information",
                  "confidence": 0.95
                }
              ],
              "questions": ["Recommendations or questions"]
            }
            
            Project data:
            %s
            
            JSON:
            """;

    /**
     * Fallback 서비스 결과 생성 (AI 파싱 실패 시)
     */
    private ServiceAnalysisResult createFallbackServiceResult(ProjectAnalysisResult analysis) {
        ServiceAnalysisResult result = new ServiceAnalysisResult();
        result.setServices(new java.util.ArrayList<>());
        result.setEntities(new java.util.ArrayList<>());
        result.setQuestions(java.util.List.of(
                "AI가 JSON 형식으로 응답하지 않았습니다.",
                "현재 프로젝트: " + analysis.getSummary()
        ));
        return result;
    }

    /**
     * 서비스 분석 결과 저장
     */
    private void saveServiceAnalysis(String projectId, ServiceAnalysisResult result) throws IOException {
        Path analysisDir = getProjectsBaseDir().resolve(projectId).resolve("analysis");
        Files.createDirectories(analysisDir);

        Path resultFilePath = analysisDir.resolve("service-analysis.json");

        // Pretty print로 저장
        ObjectMapper prettyMapper = objectMapper.copy();
        prettyMapper.enable(SerializationFeature.INDENT_OUTPUT);

        String json = prettyMapper.writeValueAsString(result);
        Files.writeString(resultFilePath, json);

        log.info("서비스 분석 결과 저장 완료 - {}", resultFilePath);
    }

    /**
     * Ollama 서버 상태 확인
     */
    public boolean checkOllamaAvailability() {
        return ollamaService.isAvailable();
    }
}
