package com.analyzer.rag.ai;

import com.analyzer.rag.Evidence;
import com.analyzer.rag.SearchBundle;
import com.analyzer.service.OllamaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 기반 BC 분석 서비스 (캐싱 지원)
 * 
 * 역할:
 * - Bundle → AI 분석 → BC 후보 추천
 * - 구조화된 JSON 출력
 * - 분석 결과 캐싱 (23분 → 1~2초)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BcAnalysisService {
    
    private final OllamaService ollamaService;
    private final ObjectMapper objectMapper;
    private final BcResultPostProcessor postProcessor;
    private final BcAnalysisCacheService cacheService;
    
    // 현재 프로젝트 ID (캐싱용)
    private String currentProjectId = null;
    
    /**
     * 프로젝트 초기화 (캐시 로드)
     * 
     * @param projectId 프로젝트 ID
     */
    public void initProject(String projectId) {
        this.currentProjectId = projectId;
        cacheService.initProject(projectId);
        log.info("BC 분석 서비스 프로젝트 초기화: {}", projectId);
    }
    
    /**
     * Bundle을 분석하여 BC 추천 (캐싱 지원)
     * 
     * @param bundle 검색 Bundle
     * @return BC 추천 결과
     */
    public BcRecommendation analyzeBoundedContext(SearchBundle bundle) {
        String componentName = bundle.getPrimaryChunk().getName();
        String fileContent = bundle.getPrimaryChunk().getCode();
        
        log.info("BC 분석 시작: {}", componentName);
        
        // ⭐ 1. 캐시 확인
        if (currentProjectId != null) {
            BcRecommendation cached = cacheService.get(currentProjectId, componentName, fileContent);
            if (cached != null) {
                log.info("BC 분석 캐시 히트: {} (AI 호출 생략)", componentName);
                return cached;
            }
        }
        
        try {
            // 2. 프롬프트 생성
            String prompt = buildPrompt(bundle);
            
            log.debug("프롬프트 길이: {}자", prompt.length());
            
            // 3. AI 호출 (캐시 미스)
            BcRecommendation recommendation = ollamaService.generateJson(
                    prompt, 
                    BcRecommendation.class
            );
            
            // 4. 컴포넌트명 설정 (AI가 안 채울 수 있음)
            if (recommendation.getComponent() == null) {
                recommendation.setComponent(componentName);
            }
            
            log.info("BC 분석 완료: {} → {} (확신도: {})", 
                    recommendation.getComponent(),
                    recommendation.getSuggestedDomain(),
                    recommendation.getConfidence());
            
            // ⭐ 5. 캐시 저장
            if (currentProjectId != null) {
                cacheService.put(currentProjectId, componentName, fileContent, recommendation);
            }
            
            return recommendation;
            
        } catch (Exception e) {
            log.error("BC 분석 실패: {}", componentName, e);
            
            // Fallback: 기본 추천
            return createFallbackRecommendation(bundle);
        }
    }
    
    /**
     * 여러 Bundle 일괄 분석 (병렬 처리 + 캐싱) ⭐
     * 
     * @param bundles Bundle 리스트
     * @param query 원래 검색 질의
     */
    public List<BcRecommendation> analyzeBoundedContexts(List<SearchBundle> bundles, String query) {
        log.info("일괄 BC 분석 시작: {}개 (병렬 처리 + 캐싱)", bundles.size());
        
        long startTime = System.currentTimeMillis();
        int cacheHits = 0;
        int cacheMisses = 0;
        
        List<BcRecommendation> recommendations = bundles.parallelStream()
                .map(bundle -> {
                    try {
                        // 캐시 확인 (내부에서 자동 처리)
                        BcRecommendation rec = analyzeBoundedContext(bundle);
                        
                        return rec;
                    } catch (Exception e) {
                        log.error("Bundle 분석 중 오류: {}", bundle.getPrimaryChunk().getName(), e);
                        return createFallbackRecommendation(bundle);
                    }
                })
                .filter(rec -> rec != null)
                .toList();
        
        long elapsed = System.currentTimeMillis() - startTime;
        
        log.info("일괄 BC 분석 완료: {}개 (소요시간: {}ms, 후처리 전)", 
                recommendations.size(), elapsed);
        
        // 후처리 적용 (중복 제거, confidence 재계산, 도메인명 정규화, relevance)
        List<BcRecommendation> processed = postProcessor.process(recommendations, query);
        
        log.info("후처리 완료: {}개", processed.size());
        
        // ⭐ 캐시를 디스크에 저장 (다음 실행 시 재사용)
        if (currentProjectId != null) {
            cacheService.saveToDisk(currentProjectId);
        }
        
        return processed;
    }
    
    /**
     * BC 분석 프롬프트 생성
     */
    private String buildPrompt(SearchBundle bundle) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("다음 Spring 컴포넌트를 분석하여 Bounded Context와 도메인을 추천하세요.\n\n");
        
        // 주요 컴포넌트 정보
        prompt.append("=== 주요 컴포넌트 ===\n");
        prompt.append("이름: ").append(bundle.getPrimaryChunk().getName()).append("\n");
        prompt.append("타입: ").append(bundle.getPrimaryChunk().getComponentType()).append("\n");
        
        if (bundle.getPrimaryChunk().getDependencies() != null && 
            !bundle.getPrimaryChunk().getDependencies().isEmpty()) {
            prompt.append("의존성: ").append(String.join(", ", bundle.getPrimaryChunk().getDependencies())).append("\n");
        }
        
        if (bundle.getPrimaryChunk().getMyBatisCalls() != null && 
            !bundle.getPrimaryChunk().getMyBatisCalls().isEmpty()) {
            prompt.append("MyBatis 호출: ").append(String.join(", ", bundle.getPrimaryChunk().getMyBatisCalls())).append("\n");
        }
        
        if (bundle.getPrimaryChunk().getAccessedTables() != null && 
            !bundle.getPrimaryChunk().getAccessedTables().isEmpty()) {
            prompt.append("접근 테이블: ").append(String.join(", ", bundle.getPrimaryChunk().getAccessedTables())).append("\n");
        }
        
        prompt.append("\n");
        
        // 관련 컴포넌트 정보 (Evidence)
        if (bundle.getEvidence() != null && bundle.getEvidence().size() > 1) {
            prompt.append("=== 관련 컴포넌트 (호출 체인) ===\n");
            
            for (int i = 1; i < Math.min(bundle.getEvidence().size(), 4); i++) {
                Evidence evidence = bundle.getEvidence().get(i);
                prompt.append(String.format("%d. %s (%s)\n", 
                        i, 
                        evidence.getComponentName(), 
                        evidence.getComponentType()));
                prompt.append("   이유: ").append(evidence.getReason()).append("\n");
            }
            prompt.append("\n");
        }
        
        // 코드 스니펫 (주요 컴포넌트만)
        prompt.append("=== 코드 스니펫 ===\n");
        String code = bundle.getPrimaryChunk().getCode();
        if (code != null && code.length() > 500) {
            prompt.append(code, 0, 500).append("\n... (생략) ...\n\n");
        } else {
            prompt.append(code).append("\n\n");
        }
        
        // 출력 형식 지시
        prompt.append("=== 분석 요청 ===\n");
        prompt.append("위 정보를 바탕으로 다음을 JSON 형식으로 출력하세요:\n\n");
        prompt.append("{\n");
        prompt.append("  \"component\": \"컴포넌트명\",\n");
        prompt.append("  \"suggestedDomain\": \"도메인명 (한글)\",\n");
        prompt.append("  \"suggestedBC\": \"Bounded Context 명\",\n");
        prompt.append("  \"subdomainType\": \"CORE 또는 SUPPORTING 또는 GENERIC\",\n");
        prompt.append("  \"confidence\": \"HIGH 또는 MEDIUM 또는 LOW\",\n");
        prompt.append("  \"reasons\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"file\": \"파일명\",\n");
        prompt.append("      \"lines\": \"라인범위\",\n");
        prompt.append("      \"why\": \"판단 근거\"\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n\n");
        
        prompt.append("중요:\n");
        prompt.append("- 도메인명은 한글로 작성\n");
        prompt.append("- confidence는 코드 근거가 명확하면 HIGH, 추정이면 MEDIUM, 불확실하면 LOW\n");
        prompt.append("- reasons는 실제 파일명과 라인 번호를 포함\n");
        prompt.append("- subdomainType은 비즈니스 핵심이면 CORE, 지원이면 SUPPORTING, 범용이면 GENERIC\n");
        
        return prompt.toString();
    }
    
    /**
     * AI 실패 시 Fallback 추천
     */
    private BcRecommendation createFallbackRecommendation(SearchBundle bundle) {
        String componentName = bundle.getPrimaryChunk().getName();
        
        // 간단한 규칙 기반 추정
        String domain = extractDomainFromName(componentName);
        
        return BcRecommendation.builder()
                .component(componentName)
                .suggestedDomain(domain)
                .suggestedBC(domain + " 관리")
                .subdomainType(BcRecommendation.SubdomainType.SUPPORTING)
                .confidence(BcRecommendation.ConfidenceLevel.LOW)
                .reasons(List.of(
                        BcRecommendation.ReasonEvidence.builder()
                                .file(bundle.getPrimaryChunk().getFilePath())
                                .lines(bundle.getPrimaryChunk().getStartLine() + "-" + 
                                       bundle.getPrimaryChunk().getEndLine())
                                .why("AI 분석 실패, 이름 기반 추정")
                                .build()
                ))
                .build();
    }
    
    /**
     * 컴포넌트 이름에서 도메인 추정
     */
    private String extractDomainFromName(String componentName) {
        // 간단한 매핑
        if (componentName.toLowerCase().contains("ems")) {
            return "전자민원";
        } else if (componentName.toLowerCase().contains("user")) {
            return "사용자 관리";
        } else if (componentName.toLowerCase().contains("api")) {
            return "API 관리";
        } else if (componentName.toLowerCase().contains("auth")) {
            return "인증/인가";
        } else if (componentName.toLowerCase().contains("bbs")) {
            return "게시판";
        } else {
            return "미분류";
        }
    }
}
