package com.analyzer.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 컴포넌트 간 의존성 그래프
 * 
 * 역할:
 * - Controller → Service 관계
 * - Service → Repository 관계
 * - Repository → Table 관계
 * 
 * Bundle 생성 시 1~2 hop 확장에 사용
 */
@Slf4j
@Component
public class DependencyGraph {
    
    // 컴포넌트명 → 의존하는 컴포넌트 리스트
    private final Map<String, Set<String>> outgoingEdges = new HashMap<>();
    
    // 컴포넌트명 → 의존받는 컴포넌트 리스트 (역방향)
    private final Map<String, Set<String>> incomingEdges = new HashMap<>();
    
    // 컴포넌트명 → 청크 ID
    private final Map<String, String> componentToChunkId = new HashMap<>();
    
    /**
     * 그래프 초기화
     */
    public void clear() {
        outgoingEdges.clear();
        incomingEdges.clear();
        componentToChunkId.clear();
        log.info("의존성 그래프 초기화 완료");
    }
    
    /**
     * 의존성 추가
     * 
     * @param from 의존하는 컴포넌트 (예: EmsController)
     * @param to 의존받는 컴포넌트 (예: EmsService)
     * @param fromChunkId from의 청크 ID
     */
    public void addDependency(String from, String to, String fromChunkId) {
        // Outgoing
        outgoingEdges.computeIfAbsent(from, k -> new HashSet<>()).add(to);
        
        // Incoming
        incomingEdges.computeIfAbsent(to, k -> new HashSet<>()).add(from);
        
        // 청크 ID 저장
        componentToChunkId.put(from, fromChunkId);
        
        log.debug("의존성 추가: {} → {}", from, to);
    }
    
    /**
     * 1-hop 확장: 이 컴포넌트가 직접 의존하는 것들
     * 
     * @param componentName 컴포넌트명
     * @return 의존하는 컴포넌트 리스트
     */
    public Set<String> getDirectDependencies(String componentName) {
        return outgoingEdges.getOrDefault(componentName, Collections.emptySet());
    }
    
    /**
     * 1-hop 역방향: 이 컴포넌트를 의존하는 것들
     * 
     * @param componentName 컴포넌트명
     * @return 의존하는 컴포넌트 리스트
     */
    public Set<String> getDirectDependents(String componentName) {
        return incomingEdges.getOrDefault(componentName, Collections.emptySet());
    }
    
    /**
     * 2-hop 확장: 의존성 체인 따라가기
     * 
     * 예: EmsController → [EmsService, EmsDAO, ...]
     * 
     * @param componentName 시작 컴포넌트
     * @param maxHops 최대 hop 수 (1 또는 2)
     * @return 확장된 컴포넌트 리스트
     */
    public Set<String> expandDependencies(String componentName, int maxHops) {
        Set<String> expanded = new HashSet<>();
        Set<String> visited = new HashSet<>();
        
        expandRecursive(componentName, maxHops, 0, expanded, visited);
        
        // 자기 자신 제외
        expanded.remove(componentName);
        
        log.debug("의존성 확장: {} → {} ({}개)", componentName, expanded, expanded.size());
        
        return expanded;
    }
    
    /**
     * BFS로 의존성 확장 (재귀)
     */
    private void expandRecursive(String current, int maxHops, int currentHop, 
                                  Set<String> result, Set<String> visited) {
        if (currentHop >= maxHops || visited.contains(current)) {
            return;
        }
        
        visited.add(current);
        
        // 직접 의존하는 것들
        Set<String> dependencies = getDirectDependencies(current);
        result.addAll(dependencies);
        
        // 재귀적으로 확장
        for (String dep : dependencies) {
            expandRecursive(dep, maxHops, currentHop + 1, result, visited);
        }
    }
    
    /**
     * 컴포넌트명으로 청크 ID 조회
     */
    public String getChunkIdByComponent(String componentName) {
        return componentToChunkId.get(componentName);
    }
    
    /**
     * 그래프 통계
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalComponents", componentToChunkId.size());
        stats.put("totalEdges", outgoingEdges.values().stream()
                .mapToInt(Set::size).sum());
        return stats;
    }
}
