package com.analyzer.service;

import com.analyzer.domain.Project;
import com.analyzer.dto.ProjectResponse;
import com.analyzer.dto.ProjectUploadRequest;
import com.analyzer.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 프로젝트 관리 서비스
 * 업로드, 저장, 조회, 삭제 담당
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final FileService fileService;

    @Value("${analyzer.storage.projects-dir}")
    private String projectsDir;

    /** 프로젝트 저장 기준 경로 (절대 경로로 정규화) */
    private Path getBaseDir() {
        return Paths.get(projectsDir).toAbsolutePath().normalize();
    }

    /**
     * 파일 내용 기반 SHA-256 해시 계산 (프로젝트 ID로 사용)
     */
    private String calculateFileHash(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hashBytes = digest.digest();
            return HexFormat.of().formatHex(hashBytes);
        } catch (Exception e) {
            log.error("파일 해시 계산 실패", e);
            throw new RuntimeException("파일 해시 계산 중 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 프로젝트 zip 파일 업로드 및 저장
     * 저장 경로: /data/projects/{projectId}/source.zip
     * 
     * ⭐ 동일 파일 해시면 기존 프로젝트 재사용 (캐시 재활용)
     */
    @Transactional
    public ProjectResponse uploadProject(MultipartFile file, ProjectUploadRequest request) {
        // ⭐ 파일 해시 기반 프로젝트 ID 생성
        String fileHash = calculateFileHash(file);
        String projectId = fileHash.substring(0, 36); // 64자 해시 → 36자로 축약 (UUID 길이 맞춤)
        
        log.info("파일 해시 계산 완료 - projectId: {}", projectId);
        
        // ⭐ 기존 프로젝트 존재 확인 (동일 소스코드 재업로드)
        Optional<Project> existingProject = projectRepository.findById(projectId);
        if (existingProject.isPresent()) {
            log.info("🔄 기존 프로젝트 재사용 (동일 소스코드) - ID: {}", projectId);
            return new ProjectResponse(existingProject.get(), true); // ⭐ reused=true
        }
        
        try {
            // 프로젝트 디렉토리 생성: {projects-dir}/{projectId}/
            Path projectDir = getBaseDir().resolve(projectId);
            Files.createDirectories(projectDir);

            // zip 파일 저장: {projects-dir}/{projectId}/source.zip
            Path zipFilePath = projectDir.resolve("source.zip");
            file.transferTo(zipFilePath.toFile());

            log.info("프로젝트 업로드 완료 - ID: {}, 파일: {}", projectId, file.getOriginalFilename());

            // 압축 해제: /data/projects/{projectId}/repo/
            Path repoPath = projectDir.resolve("repo");
            fileService.extractZip(zipFilePath.toString(), repoPath.toString());

            log.info("압축 해제 완료 - 경로: {}", repoPath);
            
            // Java 파일 스캔
            List<Path> javaFiles = fileService.scanJavaFiles(repoPath.toString());
            log.info("Java 파일 스캔 완료 - 총 {}개 파일", javaFiles.size());

            // 프로젝트 엔티티 생성 및 저장
            Project project = new Project(
                    projectId,
                    request.getName(),
                    request.getDescription(),
                    zipFilePath.toString(),
                    repoPath.toString()
            );

            project = projectRepository.save(project);

            return new ProjectResponse(project);

        } catch (IOException e) {
            log.error("파일 업로드 중 오류 발생 - projectId: {}", projectId, e);
            // 실패 시 생성된 디렉토리 정리
            fileService.deleteDirectory(getBaseDir().resolve(projectId).toString());
            throw new RuntimeException("파일 업로드 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 모든 프로젝트 조회
     */
    @Transactional(readOnly = true)
    public List<ProjectResponse> getAllProjects() {
        return projectRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(ProjectResponse::new)
                .collect(Collectors.toList());
    }

    /**
     * 특정 프로젝트 조회
     */
    @Transactional(readOnly = true)
    public ProjectResponse getProject(String projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다: " + projectId));
        return new ProjectResponse(project);
    }

    /**
     * 프로젝트 삭제
     */
    @Transactional
    public void deleteProject(String projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다: " + projectId));

        // 파일 시스템에서 전체 프로젝트 디렉토리 삭제
        String projectDirPath = getBaseDir().resolve(projectId).toString();
        fileService.deleteDirectory(projectDirPath);

        log.info("프로젝트 디렉토리 삭제 완료 - {}", projectDirPath);

        // DB에서 삭제
        projectRepository.delete(project);
        
        log.info("프로젝트 삭제 완료 - ID: {}", projectId);
    }
}
