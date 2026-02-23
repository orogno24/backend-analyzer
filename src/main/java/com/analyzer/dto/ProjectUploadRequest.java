package com.analyzer.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 프로젝트 업로드 요청 DTO
 */
@Getter
@Setter
public class ProjectUploadRequest {

    /**
     * 프로젝트 이름
     */
    private String name;

    /**
     * 프로젝트 설명
     */
    private String description;
}
