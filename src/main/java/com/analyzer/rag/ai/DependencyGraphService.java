package com.analyzer.rag.ai;

import com.analyzer.rag.CodeChunk;
import com.analyzer.rag.RagService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * BC 간 의존성 그래프 분석 서비스
 * 
 * ⭐⭐⭐ JVM 내부 Service→Service 호출 기반
 * - Spring Bean 간 직접 호출 (@Autowired, @Resource, final 주입)
 * - 메서드 호출 횟수 기반 정확한 집계
 * - 공통 유틸/공통 모듈은 별도 카테고리(Common)로 분리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyGraphService {
    
    private final RagService ragService;
    
    // 공통/유틸 컴포넌트 패턴 (노이즈 제거용)
    // ⚠️ Egov는 제외 - 전자정부 프레임워크에서 비즈니스 서비스에도 Egov 접두사 사용
    private static final Set<String> COMMON_PREFIXES = Set.of(
            "Comm", "Common", "Util", "Base", "Abstract", "Default",
            "Global", "Helper", "Support"
    );
    
    // Egov 중에서도 진짜 공통 유틸만 필터 (화이트리스트)
    private static final Set<String> EGOV_COMMON_WHITELIST = Set.of(
            "EgovProperties", "EgovPropertyService", "EgovMessageSource",
            "EgovIdGnrService", "EgovFileMngUtil", "EgovWebUtil",
            "EgovDateUtil", "EgovStringUtil", "EgovNetworkUtil"
    );
    
    /**
     * BC 간 의존성 분석 (⭐ JVM 내부 호출 포함)
     */
    public BCDependencyAnalysis analyzeBCDependencies(
            BoundedContextGroup bc,
            List<BoundedContextGroup> allBCs) {
        
        // 1. 컴포넌트 이름 → BC 매핑
        Map<String, String> componentToBC = buildComponentToBCMapping(allBCs);
        
        // 2. CodeChunk 맵 구축 (메서드 호출 정보 포함)
        List<CodeChunk> allChunks = ragService.getAllChunks();
        Map<String, CodeChunk> chunkMap = allChunks.stream()
                .collect(Collectors.toMap(CodeChunk::getName, c -> c, (c1, c2) -> c1));
        
        // 3. 주입된 필드의 타입명 → CodeChunk 이름 매핑 (ServiceImpl 정규화)
        Map<String, String> typeToChunkName = buildTypeToChunkNameMapping(allChunks);
        
        int internalCalls = 0;
        int externalCalls = 0;
        int commonCalls = 0;
        Map<String, Integer> externalBCDeps = new HashMap<>();
        Map<String, List<String>> externalCallDetails = new HashMap<>();  // BC → [method calls]
        
        // 4. BC 내부 각 컴포넌트의 메서드 호출 순회
        for (BoundedContextGroup.ComponentInfo comp : bc.getComponents()) {
            CodeChunk chunk = chunkMap.get(comp.getName());
            if (chunk == null) continue;
            
            // ⭐⭐⭐ 메서드 호출 기반 분석 (새로운 방식!)
            if (chunk.getServiceMethodCalls() != null && !chunk.getServiceMethodCalls().isEmpty()) {
                for (CodeChunk.ServiceMethodCall call : chunk.getServiceMethodCalls()) {
                    String targetType = call.getFieldType();
                    String methodName = call.getMethodName();
                    
                    // 공통 유틸 호출 분리
                    if (isCommonComponent(targetType)) {
                        commonCalls++;
                        continue;
                    }
                    
                    // 타입명으로 실제 ChunkName 찾기
                    String targetChunkName = typeToChunkName.get(targetType);
                    if (targetChunkName == null) {
                        // Impl 접미사 시도
                        targetChunkName = typeToChunkName.get(targetType + "Impl");
                        if (targetChunkName == null) continue;
                    }
                    
                    // BC 찾기
                    String targetBC = componentToBC.get(targetChunkName);
                    if (targetBC == null) continue;
                    
                    if (targetBC.equals(bc.getBcName())) {
                        internalCalls++;
                    } else {
                        externalCalls++;
                        externalBCDeps.merge(targetBC, 1, Integer::sum);
                        
                        // 호출 상세 기록
                        externalCallDetails
                                .computeIfAbsent(targetBC, k -> new ArrayList<>())
                                .add(comp.getName() + " → " + targetType + "." + methodName + "()");
                    }
                }
            }
            // 기존 방식 폴백 (serviceMethodCalls가 없는 경우)
            else if (chunk.getDependencies() != null) {
                for (String depName : chunk.getDependencies()) {
                    if (isCommonComponent(depName)) {
                        commonCalls++;
                        continue;
                    }
                    
                    String targetBC = componentToBC.get(depName);
                    if (targetBC == null) continue;
                    
                    if (targetBC.equals(bc.getBcName())) {
                        internalCalls++;
                    } else {
                        externalCalls++;
                        externalBCDeps.merge(targetBC, 1, Integer::sum);
                    }
                }
            }
        }
        
        // 5. 응집도 계산
        int totalDeps = internalCalls + externalCalls;
        double cohesion = totalDeps == 0 ? 1.0 : (double) internalCalls / totalDeps;
        
        log.debug("BC 의존성 분석: {} - 내부호출:{}, 외부호출:{}, 공통호출:{}, 응집도:{}", 
                bc.getBcName(), internalCalls, externalCalls, commonCalls, 
                String.format("%.2f", cohesion));
        
        return BCDependencyAnalysis.builder()
                .bcName(bc.getBcName())
                .internalDependencies(internalCalls)
                .externalDependencies(externalCalls)
                .commonDependencies(commonCalls)
                .externalBCDependencies(externalBCDeps)
                .externalCallDetails(externalCallDetails)
                .cohesionScore(cohesion)
                .build();
    }
    
    /**
     * 공통/유틸 컴포넌트 판별
     */
    private boolean isCommonComponent(String typeName) {
        if (typeName == null) return false;
        
        // 일반 공통 패턴 체크
        for (String prefix : COMMON_PREFIXES) {
            if (typeName.startsWith(prefix)) return true;
        }
        
        // Egov는 화이트리스트만 공통으로 처리
        if (typeName.startsWith("Egov")) {
            return EGOV_COMMON_WHITELIST.contains(typeName);
        }
        
        // 추가 패턴
        String lower = typeName.toLowerCase();
        return lower.contains("util") || lower.contains("helper") || 
               lower.contains("common") || lower.contains("base");
    }
    
    /**
     * 타입명 → CodeChunk 이름 매핑 (Interface → Impl 정규화)
     * 
     * 예: UserService → UserServiceImpl (실제 구현체)
     */
    private Map<String, String> buildTypeToChunkNameMapping(List<CodeChunk> allChunks) {
        Map<String, String> mapping = new HashMap<>();
        
        for (CodeChunk chunk : allChunks) {
            String name = chunk.getName();
            mapping.put(name, name);
            
            // ServiceImpl → Service 매핑도 추가
            if (name.endsWith("Impl")) {
                String interfaceName = name.substring(0, name.length() - 4);
                mapping.putIfAbsent(interfaceName, name);
            }
        }
        
        return mapping;
    }
    
    /**
     * 컴포넌트 이름 → BC 매핑 구축
     */
    private Map<String, String> buildComponentToBCMapping(List<BoundedContextGroup> allBCs) {
        Map<String, String> mapping = new HashMap<>();
        
        for (BoundedContextGroup bc : allBCs) {
            for (BoundedContextGroup.ComponentInfo comp : bc.getComponents()) {
                mapping.put(comp.getName(), bc.getBcName());
            }
        }
        
        log.debug("컴포넌트→BC 매핑 구축 완료: {}개", mapping.size());
        return mapping;
    }
    
    /**
     * BC 의존성 분석 결과
     */
    @Data
    @lombok.Builder
    public static class BCDependencyAnalysis {
        private String bcName;
        private int internalDependencies;       // 내부 호출 수
        private int externalDependencies;       // 외부 호출 수
        private int commonDependencies;         // 공통/유틸 호출 수 (노이즈 분리)
        private Map<String, Integer> externalBCDependencies;  // BC별 외부 의존 카운트
        private Map<String, List<String>> externalCallDetails;  // BC별 호출 상세
        private double cohesionScore;           // 응집도 (0.0~1.0)
    }
}
