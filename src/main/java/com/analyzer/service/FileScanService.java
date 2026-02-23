package com.analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 프로젝트 파일 스캔 서비스
 * Java 파일 목록 수집 및 통계 생성
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileScanService {

    private final FileService fileService;

    /**
     * 프로젝트 디렉토리에서 모든 Java 파일 스캔
     * 
     * @param projectDirectory 프로젝트 디렉토리 경로
     * @return Java 파일 경로 리스트
     */
    public List<Path> scanJavaFiles(String projectDirectory) {
        try {
            log.info("프로젝트 Java 파일 스캔 시작 - 경로: {}", projectDirectory);
            
            List<Path> javaFiles = fileService.scanJavaFiles(projectDirectory);
            
            log.info("스캔 완료 - Java 파일 수: {}", javaFiles.size());
            
            return javaFiles;
            
        } catch (IOException e) {
            log.error("Java 파일 스캔 중 오류 발생", e);
            throw new RuntimeException("Java 파일 스캔 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 프로젝트 스캔 통계 조회
     * 
     * @param projectDirectory 프로젝트 디렉토리 경로
     * @return 스캔 통계
     */
    public ScanResult getScanStatistics(String projectDirectory) {
        try {
            FileService.ScanStatistics stats = fileService.getScanStatistics(projectDirectory);
            List<Path> javaFiles = fileService.scanJavaFiles(projectDirectory);
            
            return new ScanResult(
                    stats.totalFiles(),
                    stats.javaFiles(),
                    stats.excludedJavaFiles(),
                    javaFiles
            );
            
        } catch (IOException e) {
            log.error("스캔 통계 조회 중 오류 발생", e);
            throw new RuntimeException("스캔 통계 조회 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 스캔 결과 클래스
     */
    public record ScanResult(
            int totalFiles,
            int javaFiles,
            int excludedJavaFiles,
            List<Path> javaFilePaths
    ) {
        /**
         * 간단한 요약 텍스트 생성
         */
        public String getSummary() {
            return String.format(
                    "총 파일: %d개, Java 파일: %d개 (제외: %d개)",
                    totalFiles, javaFiles, excludedJavaFiles
            );
        }
    }
}
