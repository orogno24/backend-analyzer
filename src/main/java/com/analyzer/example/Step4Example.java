package com.analyzer.example;

import com.analyzer.dto.ProjectAnalysisResult;
import com.analyzer.service.AnnotationAnalyzer;
import com.analyzer.service.AnnotationAnalyzer.FileAnalysisResult;
import com.analyzer.service.ProjectAnalysisService;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * STEP 4 사용 예제
 * 정규식 기반 어노테이션 분석
 */
public class Step4Example {

    public static void main(String[] args) {
        AnnotationAnalyzer analyzer = new AnnotationAnalyzer();

        // 1. 단일 파일 분석
        System.out.println("=== 1. 단일 파일 분석 ===");
        Path javaFile = Paths.get("./src/main/java/com/example/UserController.java");
        FileAnalysisResult fileResult = analyzer.analyzeFile(javaFile);

        System.out.println("파일: " + fileResult.getFilePath());
        System.out.println("클래스: " + fileResult.getClassName());
        System.out.println("패키지: " + fileResult.getPackageName());
        System.out.println("타입: " + fileResult.getComponentType());
        System.out.println("Spring 컴포넌트: " + fileResult.isSpringComponent());

        // 2. Entity 분석 (테이블명 추출)
        System.out.println("\n=== 2. Entity 분석 ===");
        Path entityFile = Paths.get("./src/main/java/com/example/entity/User.java");
        FileAnalysisResult entityResult = analyzer.analyzeFile(entityFile);

        if (entityResult.isEntity()) {
            System.out.println("Entity: " + entityResult.getClassName());
            System.out.println("테이블명: " + entityResult.getTableName());
        }

        // 3. 전체 프로젝트 분석 (가상 예제)
        System.out.println("\n=== 3. 전체 프로젝트 분석 결과 ===");
        printSampleAnalysisResult();
    }

    /**
     * 샘플 분석 결과 출력
     */
    private static void printSampleAnalysisResult() {
        System.out.println("총 Java 파일: 25개");
        System.out.println("Spring 컴포넌트: 12개");
        System.out.println("");
        
        System.out.println("📋 Controllers (3개):");
        System.out.println("  - UserController (@RestController)");
        System.out.println("  - ProductController (@RestController)");
        System.out.println("  - OrderController (@RestController)");
        System.out.println("");
        
        System.out.println("⚙️  Services (4개):");
        System.out.println("  - UserService (@Service)");
        System.out.println("  - ProductService (@Service)");
        System.out.println("  - OrderService (@Service)");
        System.out.println("  - PaymentService (@Service)");
        System.out.println("");
        
        System.out.println("💾 Entities (5개):");
        System.out.println("  - User → users");
        System.out.println("  - Product → products");
        System.out.println("  - Order → orders");
        System.out.println("  - OrderItem → order_items");
        System.out.println("  - Payment → payments");
    }

    /**
     * 정규식 패턴 설명
     */
    public static void explainPatterns() {
        System.out.println("=== 정규식 패턴 설명 ===\n");

        System.out.println("1. 클래스명 추출:");
        System.out.println("   Pattern: (?:public\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");
        System.out.println("   예시: public class UserController → UserController\n");

        System.out.println("2. @RestController 검출:");
        System.out.println("   Pattern: @RestController");
        System.out.println("   간단한 문자열 매칭\n");

        System.out.println("3. @Table(name=\"...\") 추출:");
        System.out.println("   Pattern: @Table\\s*\\(.*?name\\s*=\\s*[\"']([^\"']+)[\"'].*?\\)");
        System.out.println("   예시: @Table(name=\"users\") → users\n");

        System.out.println("4. CamelCase → snake_case 변환:");
        System.out.println("   UserProfile → user_profile");
        System.out.println("   OrderItem → order_item");
    }

    /**
     * JSON 출력 형태 예시
     */
    public static void showJsonExample() {
        System.out.println("=== JSON 출력 예시 ===\n");
        
        String json = """
                {
                  "projectId": "abc-123-def",
                  "totalJavaFiles": 25,
                  "totalComponents": 12,
                  "controllers": [
                    {
                      "className": "UserController",
                      "packageName": "com.example.controller",
                      "filePath": "/data/projects/.../UserController.java",
                      "type": "RestController"
                    }
                  ],
                  "services": [
                    {
                      "className": "UserService",
                      "packageName": "com.example.service",
                      "filePath": "/data/projects/.../UserService.java",
                      "type": "Service"
                    }
                  ],
                  "entities": [
                    {
                      "className": "User",
                      "packageName": "com.example.entity",
                      "tableName": "users",
                      "filePath": "/data/projects/.../User.java"
                    }
                  ],
                  "summary": "총 Java 파일: 25개, Spring 컴포넌트: 12개 (Controller: 3, Service: 4, Repository: 0, Entity: 5)"
                }
                """;
        
        System.out.println(json);
    }
}
