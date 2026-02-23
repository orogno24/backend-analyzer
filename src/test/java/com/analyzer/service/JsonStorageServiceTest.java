package com.analyzer.service;

import com.analyzer.dto.ProjectAnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON 저장 로직 테스트
 */
class JsonStorageServiceTest {

    @Test
    void testSaveAndLoadJson(@TempDir Path tempDir) throws IOException {
        // Given: JsonStorageService와 샘플 데이터
        JsonStorageService service = new JsonStorageService(new com.fasterxml.jackson.databind.ObjectMapper());
        
        ProjectAnalysisResult result = createSampleResult();
        String filePath = tempDir.resolve("analysis").resolve("analysis.json").toString();

        // When: 저장
        service.saveAsJson(result, filePath);

        // Then: 파일이 생성되었는지 확인
        assertTrue(service.exists(filePath));

        // When: 로드
        ProjectAnalysisResult loaded = service.loadFromJson(filePath, ProjectAnalysisResult.class);

        // Then: 데이터 검증
        assertEquals(result.getProjectId(), loaded.getProjectId());
        assertEquals(result.getTotalJavaFiles(), loaded.getTotalJavaFiles());
        assertEquals(result.getControllers().size(), loaded.getControllers().size());
        assertEquals(result.getEntities().size(), loaded.getEntities().size());
    }

    @Test
    void testPrettyPrint(@TempDir Path tempDir) throws IOException {
        // Given
        JsonStorageService service = new JsonStorageService(new com.fasterxml.jackson.databind.ObjectMapper());
        ProjectAnalysisResult result = createSampleResult();
        String filePath = tempDir.resolve("test.json").toString();

        // When: Pretty print로 저장
        service.saveAsJson(result, filePath);

        // Then: 파일 내용이 포맷되어 있는지 확인
        String content = Files.readString(Path.of(filePath));
        assertTrue(content.contains("\n"));  // 개행 문자 포함
        assertTrue(content.contains("  "));  // 들여쓰기 포함
        assertTrue(content.startsWith("{"));
    }

    @Test
    void testOverwrite(@TempDir Path tempDir) throws IOException {
        // Given
        JsonStorageService service = new JsonStorageService(new com.fasterxml.jackson.databind.ObjectMapper());
        String filePath = tempDir.resolve("test.json").toString();

        // When: 첫 번째 저장
        ProjectAnalysisResult result1 = new ProjectAnalysisResult();
        result1.setProjectId("version-1");
        service.saveAsJson(result1, filePath);

        // When: 덮어쓰기
        ProjectAnalysisResult result2 = new ProjectAnalysisResult();
        result2.setProjectId("version-2");
        service.saveAsJson(result2, filePath);

        // Then: 마지막 저장본이 유지되는지 확인
        ProjectAnalysisResult loaded = service.loadFromJson(filePath, ProjectAnalysisResult.class);
        assertEquals("version-2", loaded.getProjectId());
    }

    @Test
    void testToJsonString() throws IOException {
        // Given
        JsonStorageService service = new JsonStorageService(new com.fasterxml.jackson.databind.ObjectMapper());
        ProjectAnalysisResult result = createSampleResult();

        // When
        String json = service.toJsonString(result);

        // Then
        assertNotNull(json);
        assertTrue(json.contains("sample-123"));
        assertTrue(json.contains("UserController"));
    }

    @Test
    void testDelete(@TempDir Path tempDir) throws IOException {
        // Given
        JsonStorageService service = new JsonStorageService(new com.fasterxml.jackson.databind.ObjectMapper());
        String filePath = tempDir.resolve("test.json").toString();
        
        ProjectAnalysisResult result = createSampleResult();
        service.saveAsJson(result, filePath);
        assertTrue(service.exists(filePath));

        // When
        service.delete(filePath);

        // Then
        assertFalse(service.exists(filePath));
    }

    /**
     * 샘플 데이터 생성
     */
    private ProjectAnalysisResult createSampleResult() {
        ProjectAnalysisResult result = new ProjectAnalysisResult();
        result.setProjectId("sample-123");
        result.setTotalJavaFiles(25);
        result.setTotalComponents(10);

        List<ProjectAnalysisResult.ComponentInfo> controllers = new ArrayList<>();
        controllers.add(new ProjectAnalysisResult.ComponentInfo(
                "UserController", "com.example.controller", "/path/to/file.java", "RestController"
        ));
        result.setControllers(controllers);

        List<ProjectAnalysisResult.EntityInfo> entities = new ArrayList<>();
        entities.add(new ProjectAnalysisResult.EntityInfo(
                "User", "com.example.entity", "users", "/path/to/User.java"
        ));
        result.setEntities(entities);

        result.generateSummary();

        return result;
    }
}
