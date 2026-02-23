package com.analyzer.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 파일 스캔 결과 DTO
 */
@Getter
@AllArgsConstructor
public class FileScanResponse {

    /**
     * 프로젝트 ID
     */
    private String projectId;

    /**
     * 스캔한 디렉토리 경로
     */
    private String scannedDirectory;

    /**
     * 전체 파일 수
     */
    private int totalFiles;

    /**
     * Java 파일 수
     */
    private int javaFiles;

    /**
     * 제외된 Java 파일 수
     */
    private int excludedJavaFiles;

    /**
     * Java 파일 경로 목록 (상대 경로)
     */
    private List<String> javaFilePaths;

    /**
     * 스캔 요약
     */
    private String summary;
}
