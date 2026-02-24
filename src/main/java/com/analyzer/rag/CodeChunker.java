package com.analyzer.rag;

import com.analyzer.service.AnnotationAnalyzer;
import com.analyzer.service.DependencyAnalyzer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 파일을 RAG용 코드 청크로 분할
 * 
 * 전략:
 * 1. 클래스 단위로 분할 (주 청크)
 * 2. 메서드는 나중에 추가 가능
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeChunker {
    
    private final AnnotationAnalyzer annotationAnalyzer;
    private final DependencyAnalyzer dependencyAnalyzer;
    
    // 클래스 선언 패턴
    private static final Pattern CLASS_PATTERN = 
            Pattern.compile("(?:public\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");
    
    /**
     * Java 파일을 청크로 분할
     * 
     * @param javaFile Java 파일 경로
     * @return 코드 청크 리스트
     */
    public List<CodeChunk> chunkFile(Path javaFile) {
        List<CodeChunk> chunks = new ArrayList<>();
        
        try {
            // 1. 파일 분석 (기존 정적 분석 활용)
            var fileResult = annotationAnalyzer.analyzeFile(javaFile);
            if (fileResult == null || !fileResult.isSpringComponent()) {
                log.debug("Spring 컴포넌트가 아님, 스킵: {}", javaFile);
                return chunks;
            }
            
            // 2. 파일 내용 읽기
            String content = Files.readString(javaFile);
            List<String> lines = Files.readAllLines(javaFile);
            
            // 3. 의존성 분석
            var dependencies = dependencyAnalyzer.analyzeDependencies(
                    javaFile, content, fileResult.getComponentType());
            
            // 3-1. DAO/Repository인 경우 CRUD 정보 추출 + 실패 원인 리포트 ⭐
            List<com.analyzer.model.TableAccess> tableAccesses = null;
            List<String> mapperIssues = null;
            if (fileResult.getComponentType().contains("Repository") || 
                fileResult.getComponentType().contains("DAO")) {
                var mapperResult = dependencyAnalyzer.extractTableAccessFromMyBatisXmlWithReport(
                        javaFile.getParent(), 
                        fileResult.getClassName()
                );
                tableAccesses = mapperResult.getTableAccesses();
                if (mapperResult.hasIssues()) {
                    mapperIssues = new java.util.ArrayList<>();
                    mapperIssues.add("DAO: " + fileResult.getClassName());
                    mapperIssues.addAll(mapperResult.getIssues());
                }
            }
            
            // 4. 클래스 단위 청크 생성 (간단하게 전체 파일을 하나의 청크로)
            // dependencies는 컴포넌트 타입에 따라 다르게 설정 ⭐
            List<String> allDependencies = new ArrayList<>();
            allDependencies.addAll(dependencies.getUsesServices());
            allDependencies.addAll(dependencies.getUsesRepositories());
            
            // ⭐⭐⭐ 주입 필드 맵 구축 (타입 → 변수명)
            Map<String, String> injectedFieldMap = new HashMap<>();
            for (var field : dependencies.getInjectedFields()) {
                injectedFieldMap.put(field.getType(), field.getName());
            }
            
            // ⭐⭐⭐ 메서드 호출 정보 구축 (변수명 → 타입 역매핑 + 호출)
            Map<String, String> fieldNameToType = new HashMap<>();
            for (var field : dependencies.getInjectedFields()) {
                fieldNameToType.put(field.getName(), field.getType());
            }
            
            List<CodeChunk.ServiceMethodCall> serviceMethodCalls = new ArrayList<>();
            for (var call : dependencies.getMethodCalls()) {
                String fieldType = fieldNameToType.get(call.getObject());
                if (fieldType != null) {
                    // 주입된 빈에 대한 메서드 호출만 추적
                    serviceMethodCalls.add(CodeChunk.ServiceMethodCall.builder()
                            .fieldName(call.getObject())
                            .fieldType(fieldType)
                            .methodName(call.getMethod())
                            .build());
                }
            }
            
            CodeChunk chunk = CodeChunk.builder()
                    .id(CodeChunk.generateId(javaFile.toString(), 1, lines.size()))
                    .code(content)
                    .filePath(javaFile.toString())
                    .startLine(1)
                    .endLine(lines.size())
                    .type(CodeChunk.ChunkType.CLASS)
                    .name(fileResult.getClassName())
                    .componentType(fileResult.getComponentType())
                    .annotations(extractAnnotations(content))
                    .dependencies(allDependencies)  // Service + Repository 모두 포함 ⭐
                    .injectedFieldMap(injectedFieldMap)  // ⭐⭐⭐ 주입 필드 맵
                    .serviceMethodCalls(serviceMethodCalls)  // ⭐⭐⭐ 메서드 호출 정보
                    .myBatisCalls(dependencies.getMyBatisCalls())
                    .accessedTables(dependencies.getAccessedTables())  // 기존 호환
                    .tableAccesses(tableAccesses)   // ⭐ CRUD 정보 포함
                    .mapperIssues(mapperIssues)     // ⭐ Mapper 파싱 실패 원인
                    .packageName(fileResult.getPackageName())
                    .build();
            
            chunks.add(chunk);
            
            log.debug("청크 생성: {} ({})", chunk.getName(), chunk.getComponentType());
            
        } catch (IOException e) {
            log.error("파일 읽기 실패: {}", javaFile, e);
        }
        
        return chunks;
    }
    
    /**
     * 여러 파일을 청크로 분할
     */
    public List<CodeChunk> chunkFiles(List<Path> javaFiles) {
        List<CodeChunk> allChunks = new ArrayList<>();
        
        for (Path file : javaFiles) {
            allChunks.addAll(chunkFile(file));
        }
        
        log.info("총 {}개 파일에서 {}개 청크 생성", javaFiles.size(), allChunks.size());
        
        return allChunks;
    }
    
    /**
     * 어노테이션 추출 (간단 버전)
     */
    private List<String> extractAnnotations(String content) {
        List<String> annotations = new ArrayList<>();
        Pattern pattern = Pattern.compile("@(\\w+)");
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String annotation = matcher.group(1);
            if (!annotations.contains(annotation)) {
                annotations.add(annotation);
            }
        }
        
        return annotations;
    }
}
