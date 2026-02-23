package com.analyzer.controller;

import com.analyzer.dto.FileScanResponse;
import com.analyzer.service.FileScanService;
import com.analyzer.service.ProjectService;
import com.analyzer.dto.ProjectResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 파일 스캔 REST API 컨트롤러
 */
@RestController
@RequestMapping("/projects/{projectId}/scan")
@RequiredArgsConstructor
public class FileScanController {

    private final ProjectService projectService;
    private final FileScanService fileScanService;

    /**
     * 프로젝트의 Java 파일 스캔
     * GET /projects/{projectId}/scan
     */
    @GetMapping
    public ResponseEntity<FileScanResponse> scanProject(@PathVariable String projectId) {
        // 프로젝트 조회
        ProjectResponse project = projectService.getProject(projectId);

        // 스캔 실행
        FileScanService.ScanResult scanResult = 
                fileScanService.getScanStatistics(project.getZipFilePath().replace("/source.zip", "/repo"));

        // 상대 경로로 변환 (repo 디렉토리 기준)
        Path repoPath = Paths.get(project.getZipFilePath().replace("/source.zip", "/repo"));
        List<String> relativePaths = scanResult.javaFilePaths().stream()
                .map(path -> repoPath.relativize(path).toString())
                .collect(Collectors.toList());

        FileScanResponse response = new FileScanResponse(
                projectId,
                repoPath.toString(),
                scanResult.totalFiles(),
                scanResult.javaFiles(),
                scanResult.excludedJavaFiles(),
                relativePaths,
                scanResult.getSummary()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Java 파일 경로 목록만 조회
     * GET /projects/{projectId}/scan/java-files
     */
    @GetMapping("/java-files")
    public ResponseEntity<List<String>> getJavaFiles(@PathVariable String projectId) {
        ProjectResponse project = projectService.getProject(projectId);
        
        String repoPath = project.getZipFilePath().replace("/source.zip", "/repo");
        List<Path> javaFiles = fileScanService.scanJavaFiles(repoPath);

        // 절대 경로 반환
        List<String> filePaths = javaFiles.stream()
                .map(Path::toString)
                .collect(Collectors.toList());

        return ResponseEntity.ok(filePaths);
    }
}
