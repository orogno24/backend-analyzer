package com.analyzer.dto;

import com.analyzer.domain.AnalysisStatus;
import com.analyzer.domain.Project;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 프로젝트 응답 DTO
 */
@Getter
public class ProjectResponse {

    private final String projectId;  // UUID
    private final String name;
    private final String description;
    private final AnalysisStatus status;
    private final String zipFilePath;
    private final LocalDateTime createdAt;
    private final LocalDateTime analyzedAt;
    private final boolean reused; // ⭐ 기존 프로젝트 재사용 여부

    public ProjectResponse(Project project) {
        this(project, false);
    }

    public ProjectResponse(Project project, boolean reused) {
        this.projectId = project.getId();
        this.name = project.getName();
        this.description = project.getDescription();
        this.status = project.getStatus();
        this.zipFilePath = project.getZipFilePath();
        this.createdAt = project.getCreatedAt();
        this.analyzedAt = project.getAnalyzedAt();
        this.reused = reused; // ⭐
    }
}
