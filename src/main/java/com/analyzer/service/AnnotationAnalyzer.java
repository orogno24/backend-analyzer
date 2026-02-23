package com.analyzer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 파일 어노테이션 정적 분석기
 * 정규식/문자열 기반으로 Spring 어노테이션 추출 (AST 파싱 없음)
 * 
 * 분석 대상:
 * - @RestController
 * - @Service
 * - @Entity
 * - @Table(name="...")
 */
@Slf4j
@Component
public class AnnotationAnalyzer {

    // 클래스명 추출 패턴
    private static final Pattern CLASS_PATTERN = 
            Pattern.compile("(?:public\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");
    
    // 인터페이스명 추출 패턴
    private static final Pattern INTERFACE_PATTERN = 
            Pattern.compile("(?:public\\s+)?interface\\s+(\\w+)");
    
    // @RestController 패턴
    private static final Pattern REST_CONTROLLER_PATTERN = 
            Pattern.compile("@RestController");
    
    // @Controller 패턴
    private static final Pattern CONTROLLER_PATTERN = 
            Pattern.compile("@Controller");
    
    // @Service 패턴
    private static final Pattern SERVICE_PATTERN = 
            Pattern.compile("@Service");
    
    // @Repository 패턴
    private static final Pattern REPOSITORY_PATTERN = 
            Pattern.compile("@Repository");
    
    // @Entity 패턴
    private static final Pattern ENTITY_PATTERN = 
            Pattern.compile("@Entity");
    
    // @Table(name="...") 패턴
    private static final Pattern TABLE_NAME_PATTERN = 
            Pattern.compile("@Table\\s*\\(.*?name\\s*=\\s*[\"']([^\"']+)[\"'].*?\\)");

    // package 패턴
    private static final Pattern PACKAGE_PATTERN = 
            Pattern.compile("package\\s+([\\w.]+)\\s*;");

    // 메타데이터 추출 패턴
    private static final Pattern REQUEST_MAPPING_PATTERN = Pattern.compile(
            "@(?:Request|Get|Post|Put|Delete|Patch)Mapping\\s*\\([^)]*?(?:value\\s*=\\s*)?\\{?\"([^\"]+)\"");
    
    private static final Pattern HTTP_METHOD_PATTERN = Pattern.compile(
            "method\\s*=\\s*RequestMethod\\.(GET|POST|PUT|DELETE|PATCH)");
    
    private static final Pattern JAVADOC_PATTERN = Pattern.compile(
            "/\\*\\*\\s*\\n\\s*\\*\\s*([^\\n*]+)");
    
    private static final Pattern PUBLIC_METHOD_PATTERN = Pattern.compile(
            "public\\s+(?:@\\w+\\s+)?(?:<[^>]+>\\s+)?(\\w+(?:<[^>]+>)?)\\s+(\\w+)\\s*\\(");

    /**
     * Java 파일 분석
     * 
     * @param javaFilePath Java 파일 경로
     * @return 분석 결과
     */
    public FileAnalysisResult analyzeFile(Path javaFilePath) {
        try {
            // 파일 내용 읽기
            String content = Files.readString(javaFilePath);
            
            // 주석 제거 (간단한 방식)
            String cleanContent = removeComments(content);
            
            return analyzeContent(javaFilePath, cleanContent);
            
        } catch (IOException e) {
            log.error("파일 읽기 실패: {}", javaFilePath, e);
            return FileAnalysisResult.empty(javaFilePath);
        }
    }

    /**
     * 파일 내용 분석
     */
    private FileAnalysisResult analyzeContent(Path filePath, String content) {
        FileAnalysisResult result = new FileAnalysisResult();
        result.setFilePath(filePath.toString());
        
        // 주석 제거
        content = removeComments(content);

        // 패키지명 추출
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(content);
        if (packageMatcher.find()) {
            result.setPackageName(packageMatcher.group(1));
        }

        // 클래스명 추출
        String className = extractClassName(content);
        if (className == null) {
            // 인터페이스일 수도 있음
            className = extractInterfaceName(content);
        }
        result.setClassName(className);

        // 어노테이션 검사
        result.setRestController(REST_CONTROLLER_PATTERN.matcher(content).find());
        result.setController(CONTROLLER_PATTERN.matcher(content).find());
        result.setService(SERVICE_PATTERN.matcher(content).find());
        result.setRepository(REPOSITORY_PATTERN.matcher(content).find());
        result.setEntity(ENTITY_PATTERN.matcher(content).find());

        // @Table 어노테이션에서 테이블명 추출
        if (result.isEntity()) {
            String tableName = extractTableName(content);
            result.setTableName(tableName);
        }

        // 메타데이터 추출 (원본 content 사용, 주석 포함)
        String originalContent = null;
        try {
            originalContent = Files.readString(filePath);
        } catch (Exception e) {
            originalContent = content;
        }

        if (result.isController() || result.isRestController()) {
            result.setEndpoints(extractEndpoints(originalContent));
        }
        
        if (result.isService() || result.isRepository()) {
            result.setMethods(extractPublicMethods(originalContent));
        }

        return result;
    }

    /**
     * 클래스명 추출
     */
    private String extractClassName(String content) {
        Matcher matcher = CLASS_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 인터페이스명 추출
     */
    private String extractInterfaceName(String content) {
        Matcher matcher = INTERFACE_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * @Table 어노테이션에서 테이블명 추출
     */
    private String extractTableName(String content) {
        Matcher matcher = TABLE_NAME_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // @Table 어노테이션이 없으면 클래스명을 소문자로 변환하여 추정
        String className = extractClassName(content);
        if (className != null) {
            return toSnakeCase(className);
        }
        
        return null;
    }

    /**
     * CamelCase를 snake_case로 변환
     */
    private String toSnakeCase(String camelCase) {
        return camelCase
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase();
    }

    /**
     * 간단한 주석 제거
     * (완벽하지 않지만 대부분의 경우 동작)
     */
    private String removeComments(String content) {
        // 한 줄 주석 제거
        content = content.replaceAll("//.*", "");
        
        // 여러 줄 주석 제거
        content = content.replaceAll("/\\*.*?\\*/", "");
        
        return content;
    }

    /**
     * Controller 엔드포인트 추출
     */
    private List<EndpointInfo> extractEndpoints(String content) {
        List<EndpointInfo> endpoints = new ArrayList<>();
        
        String[] lines = content.split("\n");
        String currentJavaDoc = null;
        String currentUrl = null;
        String currentMethod = "GET";
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            
            if (line.startsWith("/**")) {
                currentJavaDoc = extractJavaDocDescription(content, i);
            }
            
            if (line.contains("@RequestMapping") || line.contains("@GetMapping") || 
                line.contains("@PostMapping") || line.contains("@PutMapping") || 
                line.contains("@DeleteMapping") || line.contains("@PatchMapping")) {
                
                Matcher urlMatcher = REQUEST_MAPPING_PATTERN.matcher(line);
                if (urlMatcher.find()) {
                    currentUrl = urlMatcher.group(1);
                }
                
                if (line.contains("@GetMapping")) currentMethod = "GET";
                else if (line.contains("@PostMapping")) currentMethod = "POST";
                else if (line.contains("@PutMapping")) currentMethod = "PUT";
                else if (line.contains("@DeleteMapping")) currentMethod = "DELETE";
                else if (line.contains("@PatchMapping")) currentMethod = "PATCH";
                else {
                    Matcher methodMatcher = HTTP_METHOD_PATTERN.matcher(line);
                    if (methodMatcher.find()) {
                        currentMethod = methodMatcher.group(1);
                    }
                }
            }
            
            if (line.startsWith("public") && currentUrl != null) {
                Matcher methodMatcher = PUBLIC_METHOD_PATTERN.matcher(line);
                if (methodMatcher.find()) {
                    String methodName = methodMatcher.group(2);
                    
                    EndpointInfo endpoint = new EndpointInfo();
                    endpoint.setUrl(currentUrl);
                    endpoint.setHttpMethod(currentMethod);
                    endpoint.setMethodName(methodName);
                    endpoint.setDescription(currentJavaDoc != null ? currentJavaDoc : "");
                    
                    endpoints.add(endpoint);
                    
                    currentUrl = null;
                    currentJavaDoc = null;
                    currentMethod = "GET";
                }
            }
        }
        
        return endpoints;
    }

    private String extractJavaDocDescription(String content, int startLine) {
        String[] lines = content.split("\n");
        
        for (int i = startLine; i < Math.min(startLine + 10, lines.length); i++) {
            String line = lines[i].trim();
            
            if (line.startsWith("*") && !line.startsWith("/**") && !line.startsWith("*/")) {
                String desc = line.replaceFirst("^\\*\\s*", "").trim();
                if (!desc.isEmpty() && !desc.startsWith("@")) {
                    return desc;
                }
            }
        }
        
        return null;
    }

    private List<MethodInfo> extractPublicMethods(String content) {
        List<MethodInfo> methods = new ArrayList<>();
        
        String[] lines = content.split("\n");
        String currentJavaDoc = null;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            
            if (line.startsWith("/**")) {
                currentJavaDoc = extractJavaDocDescription(content, i);
            }
            
            if (line.startsWith("public") && !line.contains("class") && !line.contains("interface")) {
                Matcher matcher = PUBLIC_METHOD_PATTERN.matcher(line);
                if (matcher.find()) {
                    String returnType = matcher.group(1);
                    String methodName = matcher.group(2);
                    
                    MethodInfo method = new MethodInfo();
                    method.setName(methodName);
                    method.setReturnType(returnType);
                    method.setDescription(currentJavaDoc != null ? currentJavaDoc : "");
                    
                    methods.add(method);
                    
                    currentJavaDoc = null;
                }
            }
        }
        
        return methods;
    }

    /**
     * 파일 분석 결과 클래스
     */
    public static class FileAnalysisResult {
        private String filePath;
        private String packageName;
        private String className;
        private boolean restController;
        private boolean controller;
        private boolean service;
        private boolean repository;
        private boolean entity;
        private String tableName;
        
        // 메타데이터
        private List<EndpointInfo> endpoints = new ArrayList<>();
        private List<MethodInfo> methods = new ArrayList<>();

        public static FileAnalysisResult empty(Path filePath) {
            FileAnalysisResult result = new FileAnalysisResult();
            result.setFilePath(filePath.toString());
            return result;
        }

        // Getters and Setters
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }

        public String getPackageName() { return packageName; }
        public void setPackageName(String packageName) { this.packageName = packageName; }

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public boolean isRestController() { return restController; }
        public void setRestController(boolean restController) { this.restController = restController; }

        public boolean isController() { return controller; }
        public void setController(boolean controller) { this.controller = controller; }

        public boolean isService() { return service; }
        public void setService(boolean service) { this.service = service; }

        public boolean isRepository() { return repository; }
        public void setRepository(boolean repository) { this.repository = repository; }

        public boolean isEntity() { return entity; }
        public void setEntity(boolean entity) { this.entity = entity; }

        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }

        public List<EndpointInfo> getEndpoints() { return endpoints; }
        public void setEndpoints(List<EndpointInfo> endpoints) { this.endpoints = endpoints; }

        public List<MethodInfo> getMethods() { return methods; }
        public void setMethods(List<MethodInfo> methods) { this.methods = methods; }

        /**
         * 어떤 Spring 컴포넌트인지 확인
         */
        public boolean isSpringComponent() {
            return restController || controller || service || repository || entity;
        }

        /**
         * 컴포넌트 타입 반환
         */
        public String getComponentType() {
            if (restController) return "RestController";
            if (controller) return "Controller";
            if (service) return "Service";
            if (repository) return "Repository";
            if (entity) return "Entity";
            return "Unknown";
        }
    }

    /**
     * 엔드포인트 정보
     */
    public static class EndpointInfo {
        private String url;
        private String httpMethod;
        private String methodName;
        private String description;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getHttpMethod() { return httpMethod; }
        public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    /**
     * 메서드 정보
     */
    public static class MethodInfo {
        private String name;
        private String returnType;
        private String description;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getReturnType() { return returnType; }
        public void setReturnType(String returnType) { this.returnType = returnType; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
