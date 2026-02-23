package com.analyzer.controller;

import com.analyzer.dto.BoundedContextAnalysisResult;
import com.analyzer.dto.DomainAnalysisResult;
import com.analyzer.dto.EnhancedBoundedContextResult;
import com.analyzer.dto.ServiceAnalysisResult;
import com.analyzer.service.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 분석 REST API 컨트롤러
 * Ollama 기반 도메인/아키텍처 분석
 */
@RestController
@RequestMapping("/projects/{projectId}/ai")
@RequiredArgsConstructor
public class AiAnalysisController {

    private final AiAnalysisService aiAnalysisService;

    /**
     * 향상된 Bounded Context 분석 실행 (의존성 관계 기반)
     * POST /projects/{projectId}/ai/enhanced-bc-analysis
     */
    @PostMapping("/enhanced-bc-analysis")
    public ResponseEntity<EnhancedBoundedContextResult> analyzeEnhancedBoundedContexts(@PathVariable String projectId) {
        EnhancedBoundedContextResult result = aiAnalysisService.analyzeEnhancedBoundedContexts(projectId);
        return ResponseEntity.ok(result);
    }

    /**
     * 저장된 향상된 BC 분석 결과 조회
     * GET /projects/{projectId}/ai/enhanced-bc-analysis
     */
    @GetMapping("/enhanced-bc-analysis")
    public ResponseEntity<EnhancedBoundedContextResult> getEnhancedBoundedContextAnalysis(@PathVariable String projectId) {
        EnhancedBoundedContextResult result = aiAnalysisService.getEnhancedBoundedContextAnalysis(projectId);
        return ResponseEntity.ok(result);
    }

    /**
     * Bounded Context 기반 아키텍처 분석 실행 (클라우드 네이티브 전환용)
     * POST /projects/{projectId}/ai/bounded-context-analysis
     */
    @PostMapping("/bounded-context-analysis")
    public ResponseEntity<BoundedContextAnalysisResult> analyzeBoundedContexts(@PathVariable String projectId) {
        BoundedContextAnalysisResult result = aiAnalysisService.analyzeBoundedContexts(projectId);
        return ResponseEntity.ok(result);
    }

    /**
     * 저장된 Bounded Context 분석 결과 조회
     * GET /projects/{projectId}/ai/bounded-context-analysis
     */
    @GetMapping("/bounded-context-analysis")
    public ResponseEntity<BoundedContextAnalysisResult> getBoundedContextAnalysis(@PathVariable String projectId) {
        BoundedContextAnalysisResult result = aiAnalysisService.getBoundedContextAnalysis(projectId);
        return ResponseEntity.ok(result);
    }

    /**
     * 도메인 기반 아키텍처 분석 실행 (레거시)
     * POST /projects/{projectId}/ai/domain-analysis
     */
    @PostMapping("/domain-analysis")
    public ResponseEntity<DomainAnalysisResult> analyzeDomains(@PathVariable String projectId) {
        DomainAnalysisResult result = aiAnalysisService.analyzeDomains(projectId);
        return ResponseEntity.ok(result);
    }

    /**
     * 저장된 도메인 분석 결과 조회 (레거시)
     * GET /projects/{projectId}/ai/domain-analysis
     */
    @GetMapping("/domain-analysis")
    public ResponseEntity<DomainAnalysisResult> getDomainAnalysis(@PathVariable String projectId) {
        DomainAnalysisResult result = aiAnalysisService.getDomainAnalysis(projectId);
        return ResponseEntity.ok(result);
    }

    /**
     * 서비스/엔티티 네이밍 및 역할 분석 실행 (레거시)
     * POST /projects/{projectId}/ai/service-analysis
     */
    @PostMapping("/service-analysis")
    public ResponseEntity<ServiceAnalysisResult> analyzeServices(@PathVariable String projectId) {
        ServiceAnalysisResult result = aiAnalysisService.analyzeServices(projectId);
        return ResponseEntity.ok(result);
    }

    /**
     * 저장된 서비스 분석 결과 조회 (레거시)
     * GET /projects/{projectId}/ai/service-analysis
     */
    @GetMapping("/service-analysis")
    public ResponseEntity<ServiceAnalysisResult> getServiceAnalysis(@PathVariable String projectId) {
        ServiceAnalysisResult result = aiAnalysisService.getServiceAnalysis(projectId);
        return ResponseEntity.ok(result);
    }

    /**
     * Ollama 서버 상태 확인
     * GET /projects/{projectId}/ai/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> checkHealth() {
        boolean available = aiAnalysisService.checkOllamaAvailability();
        
        return ResponseEntity.ok(Map.of(
                "ollama", available ? "available" : "unavailable",
                "status", available ? "healthy" : "unhealthy",
                "message", available 
                        ? "Ollama 서버가 정상적으로 실행 중입니다" 
                        : "Ollama 서버에 접근할 수 없습니다. 'ollama serve' 명령으로 서버를 시작해주세요."
        ));
    }
}
