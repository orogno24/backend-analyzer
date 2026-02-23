package com.analyzer.rag.ai;

import com.analyzer.model.TableAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CRUD Matrix 생성 서비스 (v4: BC 독립화)
 * 
 * - BC 그룹 기반 (기존 방식, deprecated)
 * - CodeChunk 기반 (신규 방식, 빠름) ⭐
 * 
 * ⭐ v4: CodeChunk에서 직접 tableAccesses 읽어서 CRUD Matrix 생성 (BC 분석 불필요)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrudMatrixService {
    
    private final com.analyzer.rag.RagService ragService;
    
    /**
     * CodeChunk 기반 CRUD Matrix 생성 (신규 방식, BC 독립) ⭐⭐⭐
     * 
     * Service → DAO 의존성을 추적하여 테이블 접근 정보 수집
     * 
     * @return CRUD Matrix
     */
    public CrudMatrix createMatrixFromChunks() {
        log.info("CRUD Matrix 생성 시작 (CodeChunk 기반, BC 독립)");
        
        // 1. RagService에서 모든 컴포넌트 조회
        List<com.analyzer.rag.CodeChunk> allChunks = ragService.getAllChunks();
        List<com.analyzer.rag.CodeChunk> serviceChunks = ragService.getServiceChunks();
        log.info("전체 컴포넌트: {}개, Service: {}개", allChunks.size(), serviceChunks.size());
        
        // 2. 컴포넌트명 → Chunk 매핑 (빠른 조회용)
        Map<String, com.analyzer.rag.CodeChunk> chunkMap = allChunks.stream()
            .collect(Collectors.toMap(
                com.analyzer.rag.CodeChunk::getName,
                c -> c,
                (c1, c2) -> c1  // 중복 시 첫 번째 선택
            ));
        
        // 3. 모든 서비스/테이블 수집
        Set<String> allServices = new HashSet<>();
        Set<String> allTables = new HashSet<>();
        
        // 4. Service → Table → CRUD Operations 매핑
        Map<String, Map<String, Set<CrudMatrix.CrudOperation>>> matrix = new HashMap<>();
        
        for (com.analyzer.rag.CodeChunk serviceChunk : serviceChunks) {
            String serviceName = normalizeServiceName(serviceChunk.getName());
            allServices.add(serviceName);
            
            matrix.computeIfAbsent(serviceName, k -> new HashMap<>());
            
            // ⭐ Service의 의존성에서 DAO/Repository 찾기
            if (serviceChunk.getDependencies() != null && !serviceChunk.getDependencies().isEmpty()) {
                for (String depName : serviceChunk.getDependencies()) {
                    // 의존성이 DAO/Repository인지 확인
                    com.analyzer.rag.CodeChunk depChunk = chunkMap.get(depName);
                    
                    if (depChunk != null && isRepositoryComponent(depChunk.getComponentType())) {
                        // DAO의 tableAccesses 수집
                        if (depChunk.getTableAccesses() != null && !depChunk.getTableAccesses().isEmpty()) {
                            for (TableAccess ta : depChunk.getTableAccesses()) {
                                String tableName = ta.getTableName();
                                allTables.add(tableName);
                                
                                // TableAccess의 CrudOperation → CrudMatrix.CrudOperation 변환
                                Set<CrudMatrix.CrudOperation> operations = ta.getOperations().stream()
                                        .map(this::convertCrudOperation)
                                        .collect(Collectors.toSet());
                                
                                // 기존 작업이 있으면 병합
                                matrix.get(serviceName).merge(tableName, operations, (existing, newOps) -> {
                                    Set<CrudMatrix.CrudOperation> merged = new HashSet<>(existing);
                                    merged.addAll(newOps);
                                    return merged;
                                });
                            }
                            
                            log.debug("서비스 '{}' → DAO '{}': {}개 테이블", 
                                serviceName, depName, depChunk.getTableAccesses().size());
                        }
                        
                        // Fallback: DAO의 accessedTables만 있는 경우
                        else if (depChunk.getAccessedTables() != null && !depChunk.getAccessedTables().isEmpty()) {
                            for (String tableName : depChunk.getAccessedTables()) {
                                allTables.add(tableName);
                                
                                // 기본 CRUD 작업 추론 (보수적: READ만)
                                Set<CrudMatrix.CrudOperation> operations = new HashSet<>();
                                operations.add(CrudMatrix.CrudOperation.READ);
                                
                                matrix.get(serviceName).merge(tableName, operations, (existing, newOps) -> {
                                    existing.addAll(newOps);
                                    return existing;
                                });
                            }
                            
                            log.debug("서비스 '{}' → DAO '{}': {}개 테이블 (fallback)", 
                                serviceName, depName, depChunk.getAccessedTables().size());
                        }
                    }
                }
            }
        }
        
        // 4. 통계 생성
        CrudMatrix.Statistics statistics = calculateStatistics(
                allServices, allTables, matrix);
        
        // 5. 정렬된 리스트 생성
        List<String> sortedServices = new ArrayList<>(allServices);
        Collections.sort(sortedServices);
        
        List<String> sortedTables = new ArrayList<>(allTables);
        Collections.sort(sortedTables);
        
        log.info("CRUD Matrix 생성 완료: 서비스 {}개, 테이블 {}개, 관계 {}개",
                sortedServices.size(), sortedTables.size(), statistics.getTotalRelations());
        
        return CrudMatrix.builder()
                .services(sortedServices)
                .tables(sortedTables)
                .matrix(matrix)
                .statistics(statistics)
                .build();
    }
    
    /**
     * BC 그룹 기반 CRUD Matrix 생성 (기존 방식, deprecated)
     * 
     * @param bcGroups BC 그룹 목록
     * @return CRUD Matrix
     * @deprecated BC 독립화 후 createMatrixFromChunks() 사용 권장
     */
    @Deprecated
    public CrudMatrix createMatrix(List<BoundedContextGroup> bcGroups) {
        log.info("CRUD Matrix 생성 시작: {}개 BC", bcGroups.size());
        
        // 1. 모든 서비스 수집 (Service/Impl 통합) ⭐
        Set<String> allServices = new HashSet<>();
        Set<String> allTables = new HashSet<>();
        
        // 2. Service → Table → CRUD Operations 매핑 ⭐
        Map<String, Map<String, Set<CrudMatrix.CrudOperation>>> matrix = new HashMap<>();
        
        for (BoundedContextGroup bc : bcGroups) {
            // BC 내의 Service 컴포넌트 수집 (Impl 제거하여 통합)
            List<String> services = bc.getComponents().stream()
                    .filter(c -> isServiceComponent(c.getType()))
                    .map(c -> normalizeServiceName(c.getName()))
                    .collect(Collectors.toList());
            
            allServices.addAll(services);
            
            // BC의 테이블 수집
            Set<String> bcTables = bc.getAccessedTables();
            if (bcTables != null && !bcTables.isEmpty()) {
                allTables.addAll(bcTables);
            }
            
            // ⭐ 핵심: BC의 TableAccessMap에서 실제 CRUD 정보 추출
            Map<String, TableAccess> bcTableAccessMap = bc.getTableAccessMap();
            
            if (bcTableAccessMap != null && !bcTableAccessMap.isEmpty()) {
                // 각 서비스를 BC의 모든 테이블과 연결하고, 실제 CRUD 작업 설정
                for (String service : services) {
                    matrix.computeIfAbsent(service, k -> new HashMap<>());
                    
                    for (Map.Entry<String, TableAccess> entry : bcTableAccessMap.entrySet()) {
                        String tableName = entry.getKey();
                        TableAccess tableAccess = entry.getValue();
                        
                        // TableAccess의 CrudOperation을 CrudMatrix.CrudOperation으로 변환 ⭐
                        Set<CrudMatrix.CrudOperation> operations = tableAccess.getOperations().stream()
                                .map(this::convertCrudOperation)
                                .collect(Collectors.toSet());
                        
                        // 기존 작업이 있으면 병합, 없으면 새로 설정
                        matrix.get(service).merge(tableName, operations, (existing, newOps) -> {
                            Set<CrudMatrix.CrudOperation> merged = new HashSet<>(existing);
                            merged.addAll(newOps);
                            return merged;
                        });
                    }
                }
                
                log.debug("BC '{}': {}개 서비스, {}개 테이블 (실제 CRUD 정보 반영)", 
                        bc.getBcName(), services.size(), bcTableAccessMap.size());
            } else {
                // TableAccessMap이 없으면 fallback: 기본 추론 사용
                log.debug("BC '{}': TableAccessMap 없음, fallback 사용", bc.getBcName());
                
                for (String service : services) {
                    matrix.computeIfAbsent(service, k -> new HashMap<>());
                    
                    if (bcTables != null) {
                        for (String table : bcTables) {
                            Set<CrudMatrix.CrudOperation> operations = inferBasicCrudOperations(table);
                            matrix.get(service).put(table, operations);
                        }
                    }
                }
            }
        }
        
        // 3. 통계 생성
        CrudMatrix.Statistics statistics = calculateStatistics(
                allServices, allTables, matrix);
        
        // 4. 정렬된 리스트 생성
        List<String> sortedServices = new ArrayList<>(allServices);
        Collections.sort(sortedServices);
        
        List<String> sortedTables = new ArrayList<>(allTables);
        Collections.sort(sortedTables);
        
        log.info("CRUD Matrix 생성 완료: 서비스 {}개, 테이블 {}개, 관계 {}개",
                sortedServices.size(), sortedTables.size(), statistics.getTotalRelations());
        
        return CrudMatrix.builder()
                .services(sortedServices)
                .tables(sortedTables)
                .matrix(matrix)
                .statistics(statistics)
                .build();
    }
    
    /**
     * TableAccess.CrudOperation → CrudMatrix.CrudOperation 변환 ⭐
     */
    private CrudMatrix.CrudOperation convertCrudOperation(TableAccess.CrudOperation operation) {
        switch (operation) {
            case CREATE:
                return CrudMatrix.CrudOperation.CREATE;
            case READ:
                return CrudMatrix.CrudOperation.READ;
            case UPDATE:
                return CrudMatrix.CrudOperation.UPDATE;
            case DELETE:
                return CrudMatrix.CrudOperation.DELETE;
            default:
                return CrudMatrix.CrudOperation.READ;
        }
    }
    
    /**
     * Service 이름 정규화 (Impl 제거하여 인터페이스명으로 통합)
     */
    private String normalizeServiceName(String serviceName) {
        if (serviceName.endsWith("ServiceImpl")) {
            return serviceName.replace("ServiceImpl", "Service");
        }
        return serviceName;
    }
    
    /**
     * Service 계층 컴포넌트 여부 확인
     */
    private boolean isServiceComponent(String type) {
        return type != null && type.contains("Service");
    }
    
    /**
     * Repository/DAO 계층 컴포넌트 여부 확인
     */
    private boolean isRepositoryComponent(String type) {
        return type != null && (type.contains("Repository") || type.contains("DAO"));
    }
    
    /**
     * CRUD 작업 추론 (Fallback: TableAccessMap 없을 때만 사용)
     */
    private Set<CrudMatrix.CrudOperation> inferBasicCrudOperations(String tableName) {
        Set<CrudMatrix.CrudOperation> operations = new HashSet<>();
        
        String upperTable = tableName.toUpperCase();
        
        // DUAL, CODE 등 참조 테이블 → READ만
        if (upperTable.equals("DUAL") ||
            upperTable.contains("CODE") ||
            upperTable.contains("_CD")) {
            operations.add(CrudMatrix.CrudOperation.READ);
        }
        // HIST, LOG, AUDIT 등 → READ + CREATE
        else if (upperTable.contains("HIST") || 
                 upperTable.contains("LOG") || 
                 upperTable.contains("AUDIT")) {
            operations.add(CrudMatrix.CrudOperation.READ);
            operations.add(CrudMatrix.CrudOperation.CREATE);
        }
        // 그 외 → READ + UPDATE (보수적)
        else {
            operations.add(CrudMatrix.CrudOperation.READ);
            operations.add(CrudMatrix.CrudOperation.UPDATE);
        }
        
        return operations;
    }
    
    /**
     * 통계 계산
     */
    private CrudMatrix.Statistics calculateStatistics(
            Set<String> allServices,
            Set<String> allTables,
            Map<String, Map<String, Set<CrudMatrix.CrudOperation>>> matrix) {
        
        // 테이블 → 서비스 역매핑
        Map<String, List<String>> tableToServices = new HashMap<>();
        for (Map.Entry<String, Map<String, Set<CrudMatrix.CrudOperation>>> serviceEntry : matrix.entrySet()) {
            String service = serviceEntry.getKey();
            for (String table : serviceEntry.getValue().keySet()) {
                tableToServices.computeIfAbsent(table, k -> new ArrayList<>())
                        .add(service);
            }
        }
        
        // 공유 테이블 (2개 이상 서비스가 접근)
        List<CrudMatrix.SharedTable> sharedTables = tableToServices.entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .map(e -> {
                    int accessCount = e.getValue().size();
                    String risk = accessCount >= 5 ? "HIGH" :
                                 accessCount >= 3 ? "MEDIUM" : "LOW";
                    
                    return CrudMatrix.SharedTable.builder()
                            .tableName(e.getKey())
                            .accessedByServices(e.getValue())
                            .accessCount(accessCount)
                            .couplingRisk(risk)
                            .build();
                })
                .sorted(Comparator.comparingInt(CrudMatrix.SharedTable::getAccessCount).reversed())
                .collect(Collectors.toList());
        
        // 고립 테이블 (어떤 서비스도 접근 안 함)
        List<String> orphanTables = allTables.stream()
                .filter(t -> !tableToServices.containsKey(t))
                .sorted()
                .collect(Collectors.toList());
        
        // 전체 관계 수
        int totalRelations = matrix.values().stream()
                .mapToInt(Map::size)
                .sum();
        
        return CrudMatrix.Statistics.builder()
                .totalServices(allServices.size())
                .totalTables(allTables.size())
                .totalRelations(totalRelations)
                .sharedTables(sharedTables)
                .orphanTables(orphanTables)
                .build();
    }
}
