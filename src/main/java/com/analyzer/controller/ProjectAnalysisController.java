package com.analyzer.controller;

import com.analyzer.dto.ProjectAnalysisResult;
import com.analyzer.dto.ProjectResponse;
import com.analyzer.service.ProjectAnalysisService;
import com.analyzer.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Paths;

/**
 * 프로젝트 정적 분석 REST API 컨트롤러
 */
@RestController
@RequestMapping("/projects/{projectId}/analysis")
@RequiredArgsConstructor
public class ProjectAnalysisController {

    private final ProjectService projectService;
    private final ProjectAnalysisService projectAnalysisService;

    /**
     * 프로젝트 정적 분석 실행
     * POST /projects/{projectId}/analysis
     */
    @PostMapping
    public ResponseEntity<ProjectAnalysisResult> analyzeProject(@PathVariable String projectId) {
        // 프로젝트 조회
        ProjectResponse project = projectService.getProject(projectId);

        // repo 경로 추출
        String repoPath = Paths.get(project.getZipFilePath())
                .getParent()
                .resolve("repo")
                .toString();

        // 분석 실행
        ProjectAnalysisResult result = projectAnalysisService.analyzeProject(projectId, repoPath);

        // 결과 저장
        projectAnalysisService.saveAnalysisResult(projectId, result);

        return ResponseEntity.ok(result);
    }

    /**
     * 분석 결과 조회
     * GET /projects/{projectId}/analysis
     */
    @GetMapping
    public ResponseEntity<ProjectAnalysisResult> getAnalysisResult(@PathVariable String projectId) {
        ProjectAnalysisResult result = projectAnalysisService.loadAnalysisResult(projectId);
        return ResponseEntity.ok(result);
    }

    /**
     * Controller 목록만 조회
     * GET /projects/{projectId}/analysis/controllers
     */
    @GetMapping("/controllers")
    public ResponseEntity<?> getControllers(@PathVariable String projectId) {
        ProjectAnalysisResult result = projectAnalysisService.loadAnalysisResult(projectId);
        return ResponseEntity.ok(result.getControllers());
    }

    /**
     * Service 목록만 조회
     * GET /projects/{projectId}/analysis/services
     */
    @GetMapping("/services")
    public ResponseEntity<?> getServices(@PathVariable String projectId) {
        ProjectAnalysisResult result = projectAnalysisService.loadAnalysisResult(projectId);
        return ResponseEntity.ok(result.getServices());
    }

    /**
     * Entity 목록만 조회
     * GET /projects/{projectId}/analysis/entities
     */
    @GetMapping("/entities")
    public ResponseEntity<?> getEntities(@PathVariable String projectId) {
        ProjectAnalysisResult result = projectAnalysisService.loadAnalysisResult(projectId);
        return ResponseEntity.ok(result.getEntities());
    }
}
