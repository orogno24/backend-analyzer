package com.analyzer.rag.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * BC 분석 결과 캐싱 서비스
 * 
 * 역할:
 * - AI 분석 결과를 파일 기반으로 영속화
 * - 동일 컴포넌트 재분석 방지 (componentName + fileHash 기반)
 * - 23분 → 1~2초로 단축
 * 
 * 캐시 구조:
 * {
 *   "componentName_fileHash": BcRecommendation,
 *   ...
 * }
 */
@Slf4j
@Service
public class BcAnalysisCacheService {
    
    private final String storageBasePath;
    private final ObjectMapper objectMapper;
    
    // 메모리 캐시 (프로젝트별)
    private final Map<String, Map<String, BcRecommendation>> projectCaches = new HashMap<>();
    
    // 현재 프로젝트 ID
    private String currentProjectId = null;
    
    public BcAnalysisCacheService(
            @Value("${analyzer.storage.path:./data}") String storagePath,
            ObjectMapper objectMapper) {
        this.storageBasePath = storagePath;
        this.objectMapper = objectMapper;
        log.info("BC 분석 캐시 서비스 초기화 (저장소: {})", storagePath);
    }
    
    /**
     * 프로젝트 초기화 (캐시 로드)
     * 
     * @param projectId 프로젝트 ID
     */
    public void initProject(String projectId) {
        if (projectId.equals(currentProjectId) && projectCaches.containsKey(projectId)) {
            log.debug("프로젝트 캐시 이미 로드됨: {}", projectId);
            return;
        }
        
        this.currentProjectId = projectId;
        
        // 디스크에서 캐시 로드
        loadCacheFromDisk(projectId);
        
        log.info("프로젝트 캐시 초기화 완료: {} (캐시 크기: {})", 
                projectId, getCache(projectId).size());
    }
    
    /**
     * 캐시에서 BC 분석 결과 조회
     * 
     * @param projectId 프로젝트 ID
     * @param componentName 컴포넌트명
     * @param fileContent 파일 내용 (해시 계산용)
     * @return 캐시된 BC 분석 결과 (없으면 null)
     */
    public BcRecommendation get(String projectId, String componentName, String fileContent) {
        String cacheKey = generateCacheKey(componentName, fileContent);
        BcRecommendation result = getCache(projectId).get(cacheKey);
        
        if (result != null) {
            log.debug("캐시 HIT: {} (key: {})", componentName, cacheKey);
        } else {
            log.debug("캐시 MISS: {} (key: {})", componentName, cacheKey);
        }
        
        return result;
    }
    
    /**
     * BC 분석 결과를 캐시에 저장
     * 
     * @param projectId 프로젝트 ID
     * @param componentName 컴포넌트명
     * @param fileContent 파일 내용 (해시 계산용)
     * @param recommendation BC 분석 결과
     */
    public void put(String projectId, String componentName, String fileContent, 
                    BcRecommendation recommendation) {
        String cacheKey = generateCacheKey(componentName, fileContent);
        getCache(projectId).put(cacheKey, recommendation);
        
        log.debug("캐시 저장: {} (key: {})", componentName, cacheKey);
    }
    
    /**
     * 캐시를 디스크에 저장 (원자적 저장)
     * 
     * @param projectId 프로젝트 ID
     */
    public void saveToDisk(String projectId) {
        try {
            Path cacheDir = Paths.get(storageBasePath, "bc-analysis-cache");
            Files.createDirectories(cacheDir);
            
            Path cachePath = cacheDir.resolve(projectId + ".json");
            Path tempPath = cacheDir.resolve(projectId + ".json.tmp");
            
            Map<String, BcRecommendation> cache = getCache(projectId);
            
            // 1. 임시 파일에 먼저 저장
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(tempPath.toFile(), cache);
            
            // 2. 원자적 교체 (rename)
            Files.move(tempPath, cachePath, 
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            
            log.info("BC 분석 캐시 저장 완료: {} ({}개 항목)", cachePath, cache.size());
            
        } catch (IOException e) {
            log.error("BC 분석 캐시 저장 실패: projectId={}", projectId, e);
        }
    }
    
    /**
     * 디스크에서 캐시 로드
     */
    private void loadCacheFromDisk(String projectId) {
        try {
            Path cachePath = Paths.get(storageBasePath, "bc-analysis-cache", projectId + ".json");
            
            if (!Files.exists(cachePath)) {
                log.info("저장된 BC 분석 캐시 없음: {}", projectId);
                projectCaches.put(projectId, new HashMap<>());
                return;
            }
            
            Map<String, BcRecommendation> cache = objectMapper.readValue(
                    cachePath.toFile(),
                    objectMapper.getTypeFactory().constructMapType(
                            HashMap.class, String.class, BcRecommendation.class)
            );
            
            projectCaches.put(projectId, cache);
            
            log.info("BC 분석 캐시 로드 완료: {} ({}개 항목)", projectId, cache.size());
            
        } catch (IOException e) {
            log.error("BC 분석 캐시 로드 실패: projectId={}", projectId, e);
            projectCaches.put(projectId, new HashMap<>());
        }
    }
    
    /**
     * 프로젝트별 캐시 맵 조회
     */
    private Map<String, BcRecommendation> getCache(String projectId) {
        return projectCaches.computeIfAbsent(projectId, k -> new HashMap<>());
    }
    
    /**
     * 캐시 키 생성: componentName_fileHash
     * 
     * @param componentName 컴포넌트명
     * @param fileContent 파일 내용
     * @return 캐시 키
     */
    private String generateCacheKey(String componentName, String fileContent) {
        String fileHash = calculateHash(fileContent);
        return componentName + "_" + fileHash;
    }
    
    /**
     * 파일 내용의 SHA-256 해시 계산 (앞 8자리만 사용)
     * 
     * @param content 파일 내용
     * @return 해시값 (16진수 문자열, 앞 8자리)
     */
    private String calculateHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes());
            
            // 바이트 배열 → 16진수 문자열 (앞 8자리만)
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < Math.min(4, hash.length); i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
            
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 해시 계산 실패", e);
            // Fallback: 간단한 해시
            return String.valueOf(content.hashCode());
        }
    }
    
    /**
     * 캐시 통계
     */
    public Map<String, Object> getStatistics(String projectId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("projectId", projectId);
        stats.put("cacheSize", getCache(projectId).size());
        stats.put("cacheKeys", getCache(projectId).keySet());
        return stats;
    }
    
    /**
     * 캐시 초기화
     */
    public void clear(String projectId) {
        getCache(projectId).clear();
        log.info("BC 분석 캐시 초기화: {}", projectId);
    }
}
