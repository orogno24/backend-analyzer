package com.analyzer.controller;

import com.analyzer.dto.ProjectResponse;
import com.analyzer.dto.ProjectUploadRequest;
import com.analyzer.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 프로젝트 업로드 및 조회 REST API 컨트롤러
 */
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    /**
     * 프로젝트 zip 파일 업로드
     * POST /projects
     */
    @PostMapping
    public ResponseEntity<ProjectResponse> uploadProject(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description) {

        // 파일 검증
        if (file.isEmpty()) {
            throw new IllegalArgumentException("파일이 비어있습니다.");
        }

        // zip 파일 확인
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("zip 파일만 업로드 가능합니다.");
        }

        // 파일 크기 제한 (100MB)
        long maxSize = 100 * 1024 * 1024; // 100MB
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("파일 크기는 100MB를 초과할 수 없습니다.");
        }

        ProjectUploadRequest request = new ProjectUploadRequest();
        request.setName(name);
        request.setDescription(description);

        ProjectResponse response = projectService.uploadProject(file, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 프로젝트 목록 조회
     */
    @GetMapping
    public ResponseEntity<List<ProjectResponse>> getAllProjects() {
        List<ProjectResponse> projects = projectService.getAllProjects();
        return ResponseEntity.ok(projects);
    }

    /**
     * 특정 프로젝트 조회
     */
    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> getProject(@PathVariable String projectId) {
        ProjectResponse response = projectService.getProject(projectId);
        return ResponseEntity.ok(response);
    }

    /**
     * 프로젝트 삭제
     */
    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(@PathVariable String projectId) {
        projectService.deleteProject(projectId);
        return ResponseEntity.noContent().build();
    }
}
