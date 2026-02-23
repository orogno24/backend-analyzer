package com.analyzer.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 프로젝트 정적 분석 결과 DTO
 */
@Getter
@Setter
public class ProjectAnalysisResult {

    /**
     * 프로젝트 ID
     */
    private String projectId;

    /**
     * 분석한 Java 파일 수
     */
    private int totalJavaFiles;

    /**
     * Spring 컴포넌트 수
     */
    private int totalComponents;

    /**
     * Controller 목록 (RestController + Controller)
     */
    private List<ComponentInfo> controllers = new ArrayList<>();

    /**
     * Service 목록
     */
    private List<ComponentInfo> services = new ArrayList<>();

    /**
     * Repository 목록
     */
    private List<ComponentInfo> repositories = new ArrayList<>();

    /**
     * Entity 목록
     */
    private List<EntityInfo> entities = new ArrayList<>();

    /**
     * 의존성 관계 (className -> DependencyInfo)
     */
    private Map<String, DependencyInfo> dependencies = new HashMap<>();

    /**
     * 분석 요약
     */
    private String summary;

    /**
     * 컴포넌트 정보
     */
    @Getter
    @Setter
    public static class ComponentInfo {
        private String className;
        private String packageName;
        private String filePath;
        private String type;  // "RestController", "Service" 등
        
        // 메타데이터
        private List<EndpointInfo> endpoints = new ArrayList<>();
        private List<MethodInfo> methods = new ArrayList<>();

        public ComponentInfo() {
        }

        public ComponentInfo(String className, String packageName, String filePath, String type) {
            this.className = className;
            this.packageName = packageName;
            this.filePath = filePath;
            this.type = type;
        }
    }

    /**
     * 엔드포인트 정보
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EndpointInfo {
        private String url;
        private String httpMethod;
        private String methodName;
        private String description;
    }

    /**
     * 메서드 정보
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MethodInfo {
        private String name;
        private String returnType;
        private String description;
    }

    /**
     * Entity 정보 (테이블명 포함)
     */
    @Getter
    @Setter
    public static class EntityInfo {
        private String className;
        private String packageName;
        private String tableName;
        private String filePath;

        public EntityInfo() {
        }

        public EntityInfo(String className, String packageName, String tableName, String filePath) {
            this.className = className;
            this.packageName = packageName;
            this.tableName = tableName;
            this.filePath = filePath;
        }
    }

    /**
     * 분석 요약 생성
     */
    public void generateSummary() {
        this.summary = String.format(
                "총 Java 파일: %d개, Spring 컴포넌트: %d개 (Controller: %d, Service: %d, Repository: %d, Entity: %d)",
                totalJavaFiles,
                totalComponents,
                controllers.size(),
                services.size(),
                repositories.size(),
                entities.size()
        );
    }

    /**
     * 의존성 정보
     */
    @Getter
    @Setter
    public static class DependencyInfo {
        /**
         * 사용하는 서비스 목록
         */
        private List<String> usesServices = new ArrayList<>();

        /**
         * 사용하는 리포지토리/DAO 목록
         */
        private List<String> usesRepositories = new ArrayList<>();

        /**
         * 접근하는 테이블 목록 (추정)
         */
        private List<String> accessedTables = new ArrayList<>();

        /**
         * MyBatis 호출 (namespace.id)
         */
        private List<String> myBatisCalls = new ArrayList<>();

        public DependencyInfo() {
        }
    }
}
