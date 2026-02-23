package com.analyzer.example;

import com.analyzer.dto.ProjectAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * STEP 5: JSON 직렬화 예제
 * Jackson을 사용한 분석 결과 저장
 */
public class JsonSerializationExample {

    public static void main(String[] args) throws IOException {
        // 1. 샘플 분석 결과 생성
        ProjectAnalysisResult result = createSampleResult();

        // 2. JSON으로 직렬화
        String json = serializeToJson(result);
        System.out.println("=== JSON 직렬화 결과 ===");
        System.out.println(json);

        // 3. 파일로 저장
        String filePath = "./data/projects/sample-project/analysis/analysis.json";
        saveToFile(result, filePath);
        System.out.println("\n파일 저장 완료: " + filePath);

        // 4. 파일에서 읽기
        ProjectAnalysisResult loaded = loadFromFile(filePath);
        System.out.println("\n=== 파일에서 로드 ===");
        System.out.println("프로젝트 ID: " + loaded.getProjectId());
        System.out.println("총 Java 파일: " + loaded.getTotalJavaFiles());
        System.out.println("Controller 수: " + loaded.getControllers().size());
    }

    /**
     * 샘플 분석 결과 생성
     */
    private static ProjectAnalysisResult createSampleResult() {
        ProjectAnalysisResult result = new ProjectAnalysisResult();
        result.setProjectId("sample-123");
        result.setTotalJavaFiles(25);
        result.setTotalComponents(12);

        // Controller 추가
        List<ProjectAnalysisResult.ComponentInfo> controllers = new ArrayList<>();
        controllers.add(new ProjectAnalysisResult.ComponentInfo(
                "UserController",
                "com.example.controller",
                "/path/to/UserController.java",
                "RestController"
        ));
        controllers.add(new ProjectAnalysisResult.ComponentInfo(
                "ProductController",
                "com.example.controller",
                "/path/to/ProductController.java",
                "RestController"
        ));
        result.setControllers(controllers);

        // Service 추가
        List<ProjectAnalysisResult.ComponentInfo> services = new ArrayList<>();
        services.add(new ProjectAnalysisResult.ComponentInfo(
                "UserService",
                "com.example.service",
                "/path/to/UserService.java",
                "Service"
        ));
        result.setServices(services);

        // Entity 추가
        List<ProjectAnalysisResult.EntityInfo> entities = new ArrayList<>();
        entities.add(new ProjectAnalysisResult.EntityInfo(
                "User",
                "com.example.entity",
                "users",
                "/path/to/User.java"
        ));
        entities.add(new ProjectAnalysisResult.EntityInfo(
                "Product",
                "com.example.entity",
                "products",
                "/path/to/Product.java"
        ));
        result.setEntities(entities);

        result.generateSummary();

        return result;
    }

    /**
     * 객체를 JSON 문자열로 직렬화 (Pretty print)
     */
    private static String serializeToJson(ProjectAnalysisResult result) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        
        // Pretty print 활성화
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        // 날짜 포맷 설정
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        return mapper.writeValueAsString(result);
    }

    /**
     * 파일로 저장
     */
    private static void saveToFile(ProjectAnalysisResult result, String filePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Path path = Paths.get(filePath);
        
        // 디렉토리 생성
        Files.createDirectories(path.getParent());

        // JSON 직렬화 및 저장
        String json = mapper.writeValueAsString(result);
        Files.writeString(path, json);
    }

    /**
     * 파일에서 로드
     */
    private static ProjectAnalysisResult loadFromFile(String filePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();

        Path path = Paths.get(filePath);
        String json = Files.readString(path);

        return mapper.readValue(json, ProjectAnalysisResult.class);
    }

    /**
     * 단순 직렬화 예제
     */
    public static void simpleExample() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 객체 생성
        ProjectAnalysisResult.ComponentInfo component = 
                new ProjectAnalysisResult.ComponentInfo(
                        "UserController",
                        "com.example.controller",
                        "/path/to/UserController.java",
                        "RestController"
                );

        // JSON으로 변환
        String json = mapper.writeValueAsString(component);
        System.out.println(json);

        // JSON에서 객체로 변환
        ProjectAnalysisResult.ComponentInfo loaded = 
                mapper.readValue(json, ProjectAnalysisResult.ComponentInfo.class);
        System.out.println("Loaded: " + loaded.getClassName());
    }

    /**
     * 파일 덮어쓰기 예제
     */
    public static void overwriteExample() throws IOException {
        String filePath = "./data/test.json";
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 첫 번째 저장
        ProjectAnalysisResult result1 = new ProjectAnalysisResult();
        result1.setProjectId("version-1");
        Files.writeString(Paths.get(filePath), mapper.writeValueAsString(result1));
        System.out.println("첫 번째 저장 완료");

        // 두 번째 저장 (덮어쓰기)
        ProjectAnalysisResult result2 = new ProjectAnalysisResult();
        result2.setProjectId("version-2");
        Files.writeString(Paths.get(filePath), mapper.writeValueAsString(result2));
        System.out.println("덮어쓰기 완료");

        // 확인
        String json = Files.readString(Paths.get(filePath));
        ProjectAnalysisResult loaded = mapper.readValue(json, ProjectAnalysisResult.class);
        System.out.println("최종 버전: " + loaded.getProjectId());  // version-2
    }
}
