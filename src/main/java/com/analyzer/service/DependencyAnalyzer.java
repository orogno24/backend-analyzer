package com.analyzer.service;

import com.analyzer.model.TableAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 의존성 관계 분석기
 * Controller→Service, Service→DAO, DAO→Table 관계 추출
 * (정규식 기반, AST 없음)
 */
@Slf4j
@Component
public class DependencyAnalyzer {

    // @Autowired XxxService 패턴
    private static final Pattern AUTOWIRED_FIELD_PATTERN = 
            Pattern.compile("@Autowired[\\s\\S]*?(?:private|protected|public)?\\s+(\\w+)\\s+(\\w+)\\s*;");

    // @Resource XxxService 패턴 (전자정부 프레임워크)
    private static final Pattern RESOURCE_FIELD_PATTERN = 
            Pattern.compile("@Resource[\\s\\S]*?(?:private|protected|public)?\\s+(\\w+)\\s+(\\w+)\\s*;");

    // private final XxxService xxxService; 패턴
    private static final Pattern FINAL_FIELD_PATTERN = 
            Pattern.compile("private\\s+final\\s+(\\w+)\\s+(\\w+)\\s*;");

    // xxxService.method() 호출 패턴
    private static final Pattern METHOD_CALL_PATTERN = 
            Pattern.compile("(\\w+)\\.(\\w+)\\s*\\(");

    // MyBatis namespace.id 패턴
    private static final Pattern MYBATIS_CALL_PATTERN = 
            Pattern.compile("(?:select|insert|update|delete)(?:One|List)?\\s*\\(\\s*[\"']([\\w.]+)[\"']");

    // SQL FROM/JOIN 테이블 패턴 (스키마.테이블 지원)
    private static final Pattern SQL_TABLE_PATTERN = 
            Pattern.compile("(?:FROM|JOIN)\\s+(?:[A-Za-z_][A-Za-z0-9_]*\\.)?([A-Za-z_][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);

    /**
     * 컴포넌트 의존성 분석
     * 
     * @param filePath 파일 경로
     * @param content 파일 내용
     * @param componentType 컴포넌트 타입 (Controller, Service 등)
     * @return 의존성 정보
     */
    public ComponentDependency analyzeDependencies(Path filePath, String content, String componentType) {
        ComponentDependency dep = new ComponentDependency();
        String className = extractClassName(content);
        dep.setClassName(className);
        dep.setFilePath(filePath.toString());
        dep.setComponentType(componentType);

        // 주입된 필드 추출
        dep.setInjectedFields(extractInjectedFields(content));

        // 메서드 호출 추출
        dep.setMethodCalls(extractMethodCalls(content));

        // Service/DAO 의존성 추출
        dep.setUsesServices(filterByType(dep.getInjectedFields(), "Service", "ServiceImpl"));
        dep.setUsesRepositories(filterByType(dep.getInjectedFields(), "Repository", "DAO"));

        // MyBatis 호출 추출 (DAO인 경우)
        if (componentType.contains("Repository") || componentType.contains("DAO")) {
            dep.setMyBatisCalls(extractMyBatisCalls(content));
            
            // MyBatis XML에서 테이블 추출 (클래스명으로 매칭) ⭐
            dep.setAccessedTables(extractTablesFromMyBatisXml(filePath.getParent(), className));
        }

        return dep;
    }

    /**
     * 클래스명 추출
     */
    private String extractClassName(String content) {
        Pattern classPattern = Pattern.compile("(?:public\\s+)?(?:abstract\\s+)?(?:class|interface)\\s+(\\w+)");
        Matcher matcher = classPattern.matcher(content);
        return matcher.find() ? matcher.group(1) : "Unknown";
    }

    /**
     * 주입된 필드 추출 (@Autowired, @Resource, final)
     */
    private List<InjectedField> extractInjectedFields(String content) {
        List<InjectedField> fields = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // @Autowired 패턴
        Matcher autowiredMatcher = AUTOWIRED_FIELD_PATTERN.matcher(content);
        while (autowiredMatcher.find()) {
            String type = autowiredMatcher.group(1);
            String name = autowiredMatcher.group(2);
            String key = type + ":" + name;
            
            if (!seen.contains(key)) {
                fields.add(new InjectedField(type, name, "AUTOWIRED"));
                seen.add(key);
            }
        }

        // @Resource 패턴 (전자정부 프레임워크)
        Matcher resourceMatcher = RESOURCE_FIELD_PATTERN.matcher(content);
        while (resourceMatcher.find()) {
            String type = resourceMatcher.group(1);
            String name = resourceMatcher.group(2);
            String key = type + ":" + name;
            
            if (!seen.contains(key)) {
                fields.add(new InjectedField(type, name, "RESOURCE"));
                seen.add(key);
            }
        }

        // private final 패턴
        Matcher finalMatcher = FINAL_FIELD_PATTERN.matcher(content);
        while (finalMatcher.find()) {
            String type = finalMatcher.group(1);
            String name = finalMatcher.group(2);
            String key = type + ":" + name;
            
            if (!seen.contains(key)) {
                fields.add(new InjectedField(type, name, "CONSTRUCTOR"));
                seen.add(key);
            }
        }

        return fields;
    }

    /**
     * 메서드 호출 추출
     */
    private List<MethodCall> extractMethodCalls(String content) {
        List<MethodCall> calls = new ArrayList<>();
        
        Matcher matcher = METHOD_CALL_PATTERN.matcher(content);
        while (matcher.find()) {
            String object = matcher.group(1);
            String method = matcher.group(2);
            
            // this, super, System 등 제외
            if (!object.equals("this") && !object.equals("super") && 
                !object.equals("System") && !object.equals("log")) {
                calls.add(new MethodCall(object, method));
            }
        }

        return calls;
    }

    /**
     * MyBatis 호출 추출
     */
    private List<String> extractMyBatisCalls(String content) {
        List<String> calls = new ArrayList<>();
        
        Matcher matcher = MYBATIS_CALL_PATTERN.matcher(content);
        while (matcher.find()) {
            calls.add(matcher.group(1));
        }

        return calls;
    }

    /**
     * 타입별 필터링
     */
    private List<String> filterByType(List<InjectedField> fields, String... suffixes) {
        List<String> result = new ArrayList<>();
        
        for (InjectedField field : fields) {
            for (String suffix : suffixes) {
                if (field.getType().endsWith(suffix)) {
                    result.add(field.getType());
                    break;
                }
            }
        }

        return result;
    }

    /**
     * MyBatis XML에서 테이블 접근 정보 추출 (CRUD 작업 포함) ⭐ NEW
     * 
     * @param daoDirectory DAO가 있는 디렉토리
     * @param daoClassName DAO 클래스명 (예: "EmsDAO")
     * @return 테이블 접근 정보 리스트 (테이블명 + CRUD 작업)
     */
    /**
     * Mapper 분석 결과 (TableAccess 목록 + 실패 원인 리포트)
     */
    public static class MapperAnalysisResult {
        private final List<TableAccess> tableAccesses;
        private final List<String> issues;
        public MapperAnalysisResult(List<TableAccess> tableAccesses, List<String> issues) {
            this.tableAccesses = tableAccesses;
            this.issues = issues;
        }
        public List<TableAccess> getTableAccesses() { return tableAccesses; }
        public List<String> getIssues() { return issues; }
        public boolean hasIssues() { return !issues.isEmpty(); }
    }

    /** 이전 API 호환 유지: 이슈 리포트 없이 TableAccess만 반환 */
    public List<TableAccess> extractTableAccessFromMyBatisXml(Path daoDirectory, String daoClassName) {
        return extractTableAccessFromMyBatisXmlWithReport(daoDirectory, daoClassName).getTableAccesses();
    }

    /**
     * MyBatis XML CRUD 추출 + 실패 원인 리포트 (MapperAnalysisResult 반환)
     */
    public MapperAnalysisResult extractTableAccessFromMyBatisXmlWithReport(Path daoDirectory, String daoClassName) {
        Map<String, TableAccess> tableAccessMap = new HashMap<>();
        List<String> scanLog = new ArrayList<>(); // 분석 불완전 원인 리포트용

        try {
            Path currentPath = daoDirectory;
            Path resourcesRoot = null;
            
            while (currentPath != null && currentPath.getNameCount() > 3) {
                String pathStr = currentPath.toString();
                if (pathStr.contains("/src/main/java")) {
                    resourcesRoot = currentPath.getFileSystem().getPath(
                            pathStr.substring(0, pathStr.indexOf("/src/main/java")) + "/src/main/resources"
                    );
                    break;
                }
                currentPath = currentPath.getParent();
            }
            
            if (resourcesRoot != null && Files.exists(resourcesRoot)) {
                final List<String> candidateFiles = new ArrayList<>();
                final List<String> matchedFiles = new ArrayList<>();
                
                try (Stream<Path> paths = Files.walk(resourcesRoot, 10)) {
                    paths.filter(p -> p.toString().endsWith(".xml"))
                            .filter(p -> {
                                String fileName = p.getFileName().toString();
                                boolean matches = fileName.contains(daoClassName) || 
                                       fileName.contains(daoClassName.replace("DAO", "")) ||
                                       fileName.contains(daoClassName.replace("DAO", "_SQL")) ||
                                       fileName.contains(daoClassName.replace("Mapper", ""));
                                if (matches) candidateFiles.add(p.toString());
                                return matches;
                            })
                            .forEach(xmlPath -> {
                                try {
                                    String xmlContent = Files.readString(xmlPath);
                                    String simpleDao = daoClassName.replace("DAO", "");
                                    
                                    // 네임스페이스 매칭 (단순명 + 전체경로 포함)
                                    boolean nsMatch = xmlContent.contains("namespace=\"" + daoClassName + "\"") ||
                                            xmlContent.contains("namespace=\"" + simpleDao + "\"") ||
                                            xmlContent.contains("." + daoClassName + "\"") ||
                                            xmlContent.contains("." + simpleDao + "\"");
                                    
                                    if (nsMatch) {
                                        matchedFiles.add(xmlPath.getFileName().toString());
                                        extractTableAccessFromTag(xmlContent, "<select", TableAccess.CrudOperation.READ, tableAccessMap);
                                        extractTableAccessFromTag(xmlContent, "<insert", TableAccess.CrudOperation.CREATE, tableAccessMap);
                                        extractTableAccessFromTag(xmlContent, "<update", TableAccess.CrudOperation.UPDATE, tableAccessMap);
                                        extractTableAccessFromTag(xmlContent, "<delete", TableAccess.CrudOperation.DELETE, tableAccessMap);

                                        // 분석 불완전 원인 감지
                                        detectIncompletePatterns(xmlContent, xmlPath.getFileName().toString(), scanLog);
                                        log.debug("XML CRUD 추출: {} → {}개 테이블", xmlPath.getFileName(), tableAccessMap.size());
                                    } else {
                                        // 파일은 찾았지만 namespace 불일치
                                        String foundNs = extractNamespace(xmlContent);
                                        scanLog.add(String.format("NS_MISMATCH: %s (expected='%s', found='%s')",
                                                xmlPath.getFileName(), daoClassName, foundNs));
                                    }
                                } catch (IOException e) {
                                    scanLog.add("READ_FAIL: " + xmlPath.getFileName() + " - " + e.getMessage());
                                }
                            });
                }
                
                if (tableAccessMap.isEmpty()) {
                    if (candidateFiles.isEmpty()) {
                        log.info("[Mapper 미탐지] DAO='{}' → resources 내 매칭 XML 파일 없음. 파일명 규칙 확인 필요", daoClassName);
                    } else {
                        log.info("[Mapper namespace 불일치] DAO='{}' → 후보파일={}개, NS매칭={}개. 원인: {}",
                                daoClassName, candidateFiles.size(), matchedFiles.size(), scanLog);
                    }
                } else if (!scanLog.isEmpty()) {
                    log.info("[Mapper 부분 파싱] DAO='{}' → {}개 테이블 탐지, 불완전 패턴 감지: {}",
                            daoClassName, tableAccessMap.size(), scanLog);
                }
            }

        } catch (Exception e) {
            log.debug("MyBatis XML CRUD 스캔 실패 [{}]: {}", daoClassName, e.getMessage());
        }

        return new MapperAnalysisResult(new ArrayList<>(tableAccessMap.values()), scanLog);
    }
    
    /** 동적 SQL 패턴 감지 (분석 불완전 원인 리포트) */
    private void detectIncompletePatterns(String xmlContent, String fileName, List<String> scanLog) {
        if (xmlContent.contains("<include refid=")) scanLog.add(fileName + ": <include refid> 사용 (테이블 누락 가능)");
        if (xmlContent.contains("<bind ")) scanLog.add(fileName + ": <bind> 동적 SQL (테이블명 변수화)");
        if (xmlContent.contains("<foreach ")) scanLog.add(fileName + ": <foreach> 다중 바인딩 (테이블 분산 가능)");
        if (xmlContent.contains("${") || xmlContent.contains("${tableName") || xmlContent.contains("${table"))
            scanLog.add(fileName + ": ${변수} String concat 쿼리 (동적 테이블명)");
        if (xmlContent.contains("EXECUTE") || xmlContent.contains("EXEC "))
            scanLog.add(fileName + ": 동적 실행(EXECUTE/EXEC) → 정적 분석 불가");
    }
    
    /** XML namespace 추출 */
    private String extractNamespace(String xmlContent) {
        Pattern nsPattern = Pattern.compile("namespace=\"([^\"]+)\"");
        Matcher m = nsPattern.matcher(xmlContent);
        return m.find() ? m.group(1) : "(없음)";
    }
    
    /**
     * XML에서 특정 태그의 SQL 추출하여 테이블 + CRUD 작업 매핑
     */
    private void extractTableAccessFromTag(String xmlContent, String tagPrefix, 
                                          TableAccess.CrudOperation operation,
                                          Map<String, TableAccess> tableAccessMap) {
        // 정규식: <select ... > ... </select> 형태 추출
        String regex = tagPrefix + "[^>]*>(.*?)</" + tagPrefix.substring(1) + ">";
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(xmlContent);
        
        while (matcher.find()) {
            String sqlContent = matcher.group(1);
            Set<String> tables = extractTablesFromSql(sqlContent);
            
            for (String tableName : tables) {
                // ⭐ DUAL 특별 처리: 무조건 READ만
                if ("DUAL".equals(tableName)) {
                    TableAccess tableAccess = tableAccessMap.computeIfAbsent(
                            tableName, 
                            k -> TableAccess.builder().tableName(tableName).build()
                    );
                    tableAccess.addOperation(TableAccess.CrudOperation.READ);
                    continue;  // 다른 작업 추가 안 함
                }
                
                // 일반 테이블
                TableAccess tableAccess = tableAccessMap.computeIfAbsent(
                        tableName, 
                        k -> TableAccess.builder().tableName(tableName).build()
                );
                tableAccess.addOperation(operation);
            }
        }
    }

    /**
     * MyBatis XML 파일에서 테이블 추출 (DAO별 매칭) ⭐
     * 
     * @param daoDirectory DAO가 있는 디렉토리
     * @param daoClassName DAO 클래스명 (예: "EmsDAO")
     * @return 테이블명 목록
     */
    public List<String> extractTablesFromMyBatisXml(Path daoDirectory, String daoClassName) {
        Set<String> tables = new HashSet<>();

        try {
            // 프로젝트 루트에서 src/main/resources 찾기
            Path currentPath = daoDirectory;
            Path resourcesRoot = null;
            
            // 상위로 올라가면서 src/main 찾기
            while (currentPath != null && currentPath.getNameCount() > 3) {
                String pathStr = currentPath.toString();
                if (pathStr.contains("/src/main/java")) {
                    // src/main/java -> src/main/resources로 변경
                    resourcesRoot = currentPath.getFileSystem().getPath(
                            pathStr.substring(0, pathStr.indexOf("/src/main/java")) + "/src/main/resources"
                    );
                    break;
                }
                currentPath = currentPath.getParent();
            }
            
            if (resourcesRoot != null && Files.exists(resourcesRoot)) {
                log.debug("MyBatis XML 스캔 시작: {} (DAO: {})", resourcesRoot, daoClassName);
                
                // resources 전체를 재귀적으로 스캔하되, DAO 이름과 매칭되는 XML만 파싱
                try (Stream<Path> paths = Files.walk(resourcesRoot, 10)) {
                    paths.filter(p -> p.toString().endsWith(".xml"))
                            .filter(p -> {
                                String fileName = p.getFileName().toString();
                                // 파일명이 DAO 클래스명을 포함하거나, namespace가 매칭되는지 확인
                                return fileName.contains(daoClassName) || 
                                       fileName.contains(daoClassName.replace("DAO", "")) ||
                                       fileName.contains(daoClassName.replace("DAO", "_SQL"));
                            })
                            .forEach(xmlPath -> {
                                try {
                                    String xmlContent = Files.readString(xmlPath);
                                    
                                    // namespace 확인 (더 정확한 매칭)
                                    if (xmlContent.contains("namespace=\"" + daoClassName + "\"") ||
                                        xmlContent.contains("namespace=\"" + daoClassName.replace("DAO", "") + "\"")) {
                                        
                                        Set<String> extractedTables = extractTablesFromSql(xmlContent);
                                        if (!extractedTables.isEmpty()) {
                                            log.debug("XML에서 테이블 추출: {} -> {}", 
                                                    xmlPath.getFileName(), extractedTables);
                                            tables.addAll(extractedTables);
                                        }
                                    }
                                } catch (IOException e) {
                                    log.debug("XML 읽기 실패: {}", xmlPath);
                                }
                            });
                }
                
                log.debug("MyBatis XML 스캔 완료: DAO={}, {}개 테이블 발견", daoClassName, tables.size());
            } else {
                log.debug("resources 디렉토리를 찾을 수 없음: {}", resourcesRoot);
            }

        } catch (Exception e) {
            log.debug("MyBatis XML 스캔 실패: {}", e.getMessage());
        }

        return new ArrayList<>(tables);
    }

    /**
     * SQL 쿼리에서 테이블명 추출
     */
    private Set<String> extractTablesFromSql(String sql) {
        Set<String> tables = new HashSet<>();
        
        Matcher matcher = SQL_TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            String tableName = matcher.group(1).toUpperCase();
            
            // SQL 키워드 제외
            if (!isReservedWord(tableName)) {
                tables.add(tableName);
            }
        }

        return tables;
    }

    /**
     * SQL 예약어 및 함수명 체크 (개선 버전) ⭐
     */
    private boolean isReservedWord(String word) {
        Set<String> reserved = Set.of(
                // SQL 키워드
                "SELECT", "INSERT", "UPDATE", "DELETE", "WHERE", "AND", "OR", 
                "ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET", "INNER", "OUTER",
                "LEFT", "RIGHT", "CROSS", "NATURAL", "ON", "USING", "AS",
                "SET", "VALUES", "INTO", "FROM", "JOIN",
                
                // Oracle 함수 ⭐
                "TO_TIMESTAMP", "TO_DATE", "TO_CHAR", "TO_NUMBER",
                "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP",
                "NVL", "NVL2", "DECODE", "COALESCE", "CASE",
                "COUNT", "SUM", "AVG", "MAX", "MIN",
                "ROWNUM", "LEVEL", "CONNECT_BY_ROOT",
                "SUBSTR", "INSTR", "LENGTH", "TRIM", "REPLACE",
                "UPPER", "LOWER", "INITCAP",
                "ROUND", "TRUNC", "CEIL", "FLOOR", "MOD",
                "CONCAT", "LPAD", "RPAD"
        );
        return reserved.contains(word);
    }

    // DTO 클래스들

    @lombok.Getter
    @lombok.Setter
    public static class ComponentDependency {
        private String className;
        private String filePath;
        private String componentType;
        private List<InjectedField> injectedFields = new ArrayList<>();
        private List<MethodCall> methodCalls = new ArrayList<>();
        private List<String> usesServices = new ArrayList<>();
        private List<String> usesRepositories = new ArrayList<>();
        private List<String> myBatisCalls = new ArrayList<>();
        private List<String> accessedTables = new ArrayList<>();
    }

    @lombok.Getter
    @lombok.Setter
    @lombok.AllArgsConstructor
    public static class InjectedField {
        private String type;       // XxxService
        private String name;       // xxxService
        private String injection;  // AUTOWIRED, CONSTRUCTOR
    }

    @lombok.Getter
    @lombok.Setter
    @lombok.AllArgsConstructor
    public static class MethodCall {
        private String object;  // xxxService
        private String method;  // findById
    }
}
