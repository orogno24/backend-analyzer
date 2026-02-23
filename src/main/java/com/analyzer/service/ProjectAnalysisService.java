package com.analyzer.service;

import com.analyzer.dto.ProjectAnalysisResult;
import com.analyzer.service.AnnotationAnalyzer.FileAnalysisResult;
import com.analyzer.service.DependencyAnalyzer.ComponentDependency;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 프로젝트 전체 정적 분석 서비스
 * Spring 어노테이션 기반 컴포넌트 분석 및 JSON 생성
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAnalysisService {

    private final AnnotationAnalyzer annotationAnalyzer;
    private final FileScanService fileScanService;
    private final DependencyAnalyzer dependencyAnalyzer;
    private final ObjectMapper objectMapper;

    @Value("${analyzer.storage.projects-dir}")
    private String projectsDir;

    private Path getProjectsBaseDir() {
        return Paths.get(projectsDir).toAbsolutePath().normalize();
    }

    /**
     * 프로젝트 전체 분석 수행
     * 
     * @param projectId 프로젝트 ID
     * @param repoPath 프로젝트 소스 경로
     * @return 분석 결과
     */
    public ProjectAnalysisResult analyzeProject(String projectId, String repoPath) {
        log.info("프로젝트 분석 시작 - projectId: {}, path: {}", projectId, repoPath);

        ProjectAnalysisResult result = new ProjectAnalysisResult();
        result.setProjectId(projectId);

        try {
            // 1. Java 파일 스캔
            List<Path> javaFiles = fileScanService.scanJavaFiles(repoPath);
            result.setTotalJavaFiles(javaFiles.size());

            log.info("Java 파일 {}개 발견", javaFiles.size());

            // 2. 각 파일 분석 (어노테이션 + 의존성)
            Map<String, ComponentDependency> dependencyMap = new HashMap<>();
            int componentCount = 0;
            
            for (Path javaFile : javaFiles) {
                FileAnalysisResult fileResult = annotationAnalyzer.analyzeFile(javaFile);
                
                if (fileResult == null) continue; // 분석 실패 (숨김 파일 등)

                if (fileResult.isSpringComponent()) {
                    componentCount++;
                    categorizeComponent(result, fileResult);
                    
                    // 의존성 분석
                    try {
                        String content = Files.readString(javaFile);
                        ComponentDependency dep = dependencyAnalyzer.analyzeDependencies(
                                javaFile, content, fileResult.getComponentType()
                        );
                        dependencyMap.put(fileResult.getClassName(), dep);
                    } catch (IOException e) {
                        log.warn("의존성 분석 실패: {}", javaFile);
                    }
                }
            }

            result.setTotalComponents(componentCount);
            
            // 3. 의존성 정보를 result에 변환
            result.setDependencies(convertToDependencyInfo(dependencyMap));
            
            result.generateSummary();

            log.info("분석 완료 - {}, 의존성: {}개", result.getSummary(), result.getDependencies().size());

            return result;

        } catch (Exception e) {
            log.error("프로젝트 분석 중 오류 발생", e);
            throw new RuntimeException("프로젝트 분석 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * ComponentDependency를 DependencyInfo로 변환
     */
    private Map<String, ProjectAnalysisResult.DependencyInfo> convertToDependencyInfo(
            Map<String, ComponentDependency> depMap) {
        
        Map<String, ProjectAnalysisResult.DependencyInfo> result = new HashMap<>();
        
        for (Map.Entry<String, ComponentDependency> entry : depMap.entrySet()) {
            ComponentDependency dep = entry.getValue();
            
            ProjectAnalysisResult.DependencyInfo info = new ProjectAnalysisResult.DependencyInfo();
            info.setUsesServices(dep.getUsesServices());
            info.setUsesRepositories(dep.getUsesRepositories());
            info.setMyBatisCalls(dep.getMyBatisCalls());
            
            // MyBatis XML에서 테이블 추출 (DAO인 경우)
            if (dep.getComponentType().contains("Repository") || dep.getComponentType().contains("DAO")) {
                Path daoDir = Paths.get(dep.getFilePath()).getParent();
                List<String> tables = dependencyAnalyzer.extractTablesFromMyBatisXml(daoDir, dep.getClassName());
                info.setAccessedTables(tables);
            } else {
                info.setAccessedTables(dep.getAccessedTables());
            }
            
            result.put(entry.getKey(), info);
        }
        
        return result;
    }

    /**
     * 파일 분석 결과를 컴포넌트 타입별로 분류
     */
    private void categorizeComponent(ProjectAnalysisResult result, FileAnalysisResult fileResult) {
        String className = fileResult.getClassName();
        String packageName = fileResult.getPackageName();
        String filePath = fileResult.getFilePath();

        // Controller (RestController + Controller)
        if (fileResult.isRestController() || fileResult.isController()) {
            String type = fileResult.isRestController() ? "RestController" : "Controller";
            ProjectAnalysisResult.ComponentInfo comp = 
                    new ProjectAnalysisResult.ComponentInfo(className, packageName, filePath, type);
            
            // 엔드포인트 메타데이터 복사
            if (fileResult.getEndpoints() != null && !fileResult.getEndpoints().isEmpty()) {
                for (var ep : fileResult.getEndpoints()) {
                    comp.getEndpoints().add(new ProjectAnalysisResult.EndpointInfo(
                            ep.getUrl(), ep.getHttpMethod(), ep.getMethodName(), ep.getDescription()
                    ));
                }
            }
            
            result.getControllers().add(comp);
        }

        // Service
        if (fileResult.isService()) {
            ProjectAnalysisResult.ComponentInfo comp = 
                    new ProjectAnalysisResult.ComponentInfo(className, packageName, filePath, "Service");
            
            // 메서드 메타데이터 복사
            if (fileResult.getMethods() != null && !fileResult.getMethods().isEmpty()) {
                for (var m : fileResult.getMethods()) {
                    comp.getMethods().add(new ProjectAnalysisResult.MethodInfo(
                            m.getName(), m.getReturnType(), m.getDescription()
                    ));
                }
            }
            
            result.getServices().add(comp);
        }

        // Repository
        if (fileResult.isRepository()) {
            ProjectAnalysisResult.ComponentInfo comp = 
                    new ProjectAnalysisResult.ComponentInfo(className, packageName, filePath, "Repository");
            
            // 메서드 메타데이터 복사
            if (fileResult.getMethods() != null && !fileResult.getMethods().isEmpty()) {
                for (var m : fileResult.getMethods()) {
                    comp.getMethods().add(new ProjectAnalysisResult.MethodInfo(
                            m.getName(), m.getReturnType(), m.getDescription()
                    ));
                }
            }
            
            result.getRepositories().add(comp);
        }

        // Entity
        if (fileResult.isEntity()) {
            result.getEntities().add(
                    new ProjectAnalysisResult.EntityInfo(
                            className,
                            packageName,
                            fileResult.getTableName(),
                            filePath
                    )
            );
        }
    }

    /**
     * 분석 결과를 JSON 파일로 저장
     * 경로: /data/projects/{projectId}/analysis/analysis.json
     * 
     * @param projectId 프로젝트 ID
     * @param result 분석 결과
     * @return 저장된 파일 경로
     */
    public String saveAnalysisResult(String projectId, ProjectAnalysisResult result) {
        try {
            // JSON 파일 경로: {projects-dir}/{projectId}/analysis/analysis.json
            Path analysisDir = getProjectsBaseDir().resolve(projectId).resolve("analysis");
            Path analysisFilePath = analysisDir.resolve("analysis.json");

            // analysis 디렉토리 생성 (존재하지 않을 경우)
            Files.createDirectories(analysisDir);

            // Jackson ObjectMapper 설정
            ObjectMapper mapper = new ObjectMapper();
            // Pretty print 활성화 (읽기 쉽게 포맷)
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            // 날짜 포맷 설정 (ISO-8601)
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            
            // 객체를 JSON 문자열로 직렬화
            String json = mapper.writeValueAsString(result);
            
            // 파일로 저장 (덮어쓰기)
            Files.writeString(analysisFilePath, json);

            log.info("분석 결과 저장 완료 - {}", analysisFilePath);
            log.info("파일 크기: {} bytes", Files.size(analysisFilePath));

            return analysisFilePath.toString();

        } catch (IOException e) {
            log.error("분석 결과 저장 실패", e);
            throw new RuntimeException("분석 결과 저장 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 저장된 분석 결과 읽기
     * 
     * @param projectId 프로젝트 ID
     * @return 분석 결과
     */
    public ProjectAnalysisResult loadAnalysisResult(String projectId) {
        try {
            // 파일 경로: {projects-dir}/{projectId}/analysis/analysis.json
            Path analysisFilePath = getProjectsBaseDir().resolve(projectId).resolve("analysis").resolve("analysis.json");

            if (!Files.exists(analysisFilePath)) {
                throw new RuntimeException("분석 결과 파일이 없습니다: " + analysisFilePath);
            }

            // JSON 파일 읽기
            String json = Files.readString(analysisFilePath);
            
            // JSON 문자열을 객체로 역직렬화
            ObjectMapper mapper = new ObjectMapper();
            ProjectAnalysisResult result = mapper.readValue(json, ProjectAnalysisResult.class);

            log.info("분석 결과 로드 완료 - projectId: {}", projectId);

            return result;

        } catch (IOException e) {
            log.error("분석 결과 읽기 실패", e);
            throw new RuntimeException("분석 결과 읽기 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }
}
