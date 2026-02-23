package com.analyzer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileService 테스트
 */
class FileServiceTest {

    private final FileService fileService = new FileService();

    @Test
    void testScanJavaFiles(@TempDir Path tempDir) throws IOException {
        // Given: 테스트 디렉토리 구조 생성
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        
        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(buildDir);
        
        Path targetDir = tempDir.resolve("target");
        Files.createDirectories(targetDir);

        // Java 파일 생성
        Files.createFile(srcDir.resolve("Main.java"));
        Files.createFile(srcDir.resolve("Service.java"));
        
        // 제외 디렉토리에 Java 파일 생성
        Files.createFile(buildDir.resolve("Generated.java"));
        Files.createFile(targetDir.resolve("Compiled.java"));

        // When: Java 파일 스캔
        List<Path> javaFiles = fileService.scanJavaFiles(tempDir.toString());

        // Then: 제외 디렉토리 파일은 제외되어야 함
        assertEquals(2, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(p -> p.toString().contains("Main.java")));
        assertTrue(javaFiles.stream().anyMatch(p -> p.toString().contains("Service.java")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.toString().contains("Generated.java")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.toString().contains("Compiled.java")));
    }

    @Test
    void testGetScanStatistics(@TempDir Path tempDir) throws IOException {
        // Given: 테스트 파일 생성
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        
        Files.createFile(srcDir.resolve("Test1.java"));
        Files.createFile(srcDir.resolve("Test2.java"));
        Files.createFile(srcDir.resolve("readme.txt"));

        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(buildDir);
        Files.createFile(buildDir.resolve("Excluded.java"));

        // When: 통계 조회
        FileService.ScanStatistics stats = fileService.getScanStatistics(tempDir.toString());

        // Then: 통계 검증
        assertEquals(4, stats.totalFiles()); // 3 in src + 1 in build
        assertEquals(2, stats.javaFiles()); // only in src
        assertEquals(1, stats.excludedJavaFiles()); // in build
    }
}
