package com.analyzer.service;

import com.analyzer.service.AnnotationAnalyzer.FileAnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnnotationAnalyzer 테스트
 */
class AnnotationAnalyzerTest {

    private final AnnotationAnalyzer analyzer = new AnnotationAnalyzer();

    @Test
    void testAnalyzeRestController(@TempDir Path tempDir) throws IOException {
        // Given: RestController 클래스
        String javaCode = """
                package com.example.controller;
                
                import org.springframework.web.bind.annotation.RestController;
                
                @RestController
                public class UserController {
                    // controller methods
                }
                """;

        Path javaFile = tempDir.resolve("UserController.java");
        Files.writeString(javaFile, javaCode);

        // When: 분석 실행
        FileAnalysisResult result = analyzer.analyzeFile(javaFile);

        // Then: 검증
        assertEquals("UserController", result.getClassName());
        assertEquals("com.example.controller", result.getPackageName());
        assertTrue(result.isRestController());
        assertFalse(result.isService());
        assertFalse(result.isEntity());
        assertEquals("RestController", result.getComponentType());
    }

    @Test
    void testAnalyzeService(@TempDir Path tempDir) throws IOException {
        // Given: Service 클래스
        String javaCode = """
                package com.example.service;
                
                import org.springframework.stereotype.Service;
                
                @Service
                public class UserService {
                    // service methods
                }
                """;

        Path javaFile = tempDir.resolve("UserService.java");
        Files.writeString(javaFile, javaCode);

        // When
        FileAnalysisResult result = analyzer.analyzeFile(javaFile);

        // Then
        assertEquals("UserService", result.getClassName());
        assertEquals("com.example.service", result.getPackageName());
        assertTrue(result.isService());
        assertFalse(result.isRestController());
        assertEquals("Service", result.getComponentType());
    }

    @Test
    void testAnalyzeEntityWithTable(@TempDir Path tempDir) throws IOException {
        // Given: Entity with @Table
        String javaCode = """
                package com.example.entity;
                
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                
                @Entity
                @Table(name = "users")
                public class User {
                    private Long id;
                    private String name;
                }
                """;

        Path javaFile = tempDir.resolve("User.java");
        Files.writeString(javaFile, javaCode);

        // When
        FileAnalysisResult result = analyzer.analyzeFile(javaFile);

        // Then
        assertEquals("User", result.getClassName());
        assertEquals("com.example.entity", result.getPackageName());
        assertTrue(result.isEntity());
        assertEquals("users", result.getTableName());
        assertEquals("Entity", result.getComponentType());
    }

    @Test
    void testAnalyzeEntityWithoutTable(@TempDir Path tempDir) throws IOException {
        // Given: Entity without @Table (테이블명 추정)
        String javaCode = """
                package com.example.entity;
                
                import jakarta.persistence.Entity;
                
                @Entity
                public class UserProfile {
                    private Long id;
                }
                """;

        Path javaFile = tempDir.resolve("UserProfile.java");
        Files.writeString(javaFile, javaCode);

        // When
        FileAnalysisResult result = analyzer.analyzeFile(javaFile);

        // Then
        assertEquals("UserProfile", result.getClassName());
        assertTrue(result.isEntity());
        assertEquals("user_profile", result.getTableName()); // CamelCase -> snake_case
    }

    @Test
    void testAnalyzeWithComments(@TempDir Path tempDir) throws IOException {
        // Given: 주석이 포함된 코드
        String javaCode = """
                package com.example;
                
                // This is a service
                /* 
                 * Multi-line comment
                 * @Service is here in comment
                 */
                import org.springframework.stereotype.Service;
                
                @Service
                public class TestService {
                    // methods
                }
                """;

        Path javaFile = tempDir.resolve("TestService.java");
        Files.writeString(javaFile, javaCode);

        // When
        FileAnalysisResult result = analyzer.analyzeFile(javaFile);

        // Then
        assertEquals("TestService", result.getClassName());
        assertTrue(result.isService());
    }

    @Test
    void testAnalyzeInterface(@TempDir Path tempDir) throws IOException {
        // Given: Repository 인터페이스
        String javaCode = """
                package com.example.repository;
                
                import org.springframework.stereotype.Repository;
                
                @Repository
                public interface UserRepository {
                    // repository methods
                }
                """;

        Path javaFile = tempDir.resolve("UserRepository.java");
        Files.writeString(javaFile, javaCode);

        // When
        FileAnalysisResult result = analyzer.analyzeFile(javaFile);

        // Then
        assertEquals("UserRepository", result.getClassName());
        assertTrue(result.isRepository());
    }
}
