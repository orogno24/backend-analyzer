package com.analyzer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * Ollama AI 연동 서비스
 * 로컬 Ollama HTTP API 호출
 */
@Slf4j
@Service
public class OllamaService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Value("${analyzer.ollama.model}")
    private String model;

    @Value("${analyzer.ollama.timeout-seconds:120}")
    private int timeoutSeconds;

    public OllamaService(
            @Value("${analyzer.ollama.base-url}") String baseUrl,
            ObjectMapper objectMapper) {
        String url = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : "http://localhost:11434";
        this.webClient = WebClient.builder()
                .baseUrl(url)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.objectMapper = objectMapper;
        this.baseUrl = url;
        log.info("OllamaService 초기화 완료 - baseUrl: {}", url);
    }

    /**
     * Ollama에게 프롬프트 전송 및 응답 수신
     * 
     * @param prompt 프롬프트
     * @return AI 응답 텍스트
     */
    public String generate(String prompt) {
        return generate(prompt, false);
    }

    /**
     * Ollama에게 프롬프트 전송 (스트리밍 옵션)
     * 
     * @param prompt 프롬프트
     * @param stream 스트리밍 여부
     * @return AI 응답 텍스트
     */
    public String generate(String prompt, boolean stream) {
        try {
            log.info("Ollama 요청 시작 - 모델: {}, 프롬프트 길이: {}자", model, prompt.length());

            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "prompt", prompt,
                    "stream", stream
            );

            Map<String, Object> response = webClient.post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (response != null && response.containsKey("response")) {
                String aiResponse = (String) response.get("response");
                log.info("Ollama 응답 수신 - 길이: {}자", aiResponse.length());
                return aiResponse;
            }

            log.warn("Ollama 응답에 'response' 필드가 없습니다: {}", response);
            return "AI 응답을 받지 못했습니다.";

        } catch (WebClientResponseException e) {
            log.error("Ollama API 호출 실패 - 상태 코드: {}, 메시지: {}", 
                    e.getStatusCode(), e.getMessage());
            throw new RuntimeException(
                    String.format("Ollama API 호출 실패 (상태: %s): %s", 
                            e.getStatusCode(), e.getMessage()),
                    e
            );
        } catch (Exception e) {
            log.error("Ollama 서버 연결 실패", e);
            throw new RuntimeException(
                    String.format("Ollama 서버 연결 실패: %s\n\n" +
                            "확인 사항:\n" +
                            "1. Ollama가 실행 중인지 확인 (ollama serve)\n" +
                            "2. 모델이 설치되어 있는지 확인 (ollama pull %s)\n" +
                            "3. 포트가 올바른지 확인 (%s)", 
                            e.getMessage(), model, baseUrl),
                    e
            );
        }
    }

    /**
     * JSON 형식 응답을 파싱하여 반환
     * (format: json 옵션 사용으로 JSON 응답 강제)
     * 
     * @param prompt 프롬프트
     * @param responseClass 응답 클래스 타입
     * @param <T> 응답 타입
     * @return 파싱된 객체
     */
    public <T> T generateJson(String prompt, Class<T> responseClass) {
        try {
            log.info("Ollama JSON API 호출 시작 - 모델: {}", model);
            
            // 큰 프롬프트면 모델 예열 (선택적)
            if (prompt.length() > 10000) {
                warmupModel();
            }

            // 요청 본문 (system, format, temperature 추가)
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "system", "You are a precise JSON generator. Output ONLY valid JSON matching the exact structure requested. No explanations, no markdown, no additional text.",
                    "prompt", prompt,
                    "stream", false,
                    "format", "json",
                    "options", Map.of("temperature", 0.1)  // 낮은 temperature로 정확성 향상
            );

            Map<String, Object> response = webClient.post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (response == null || !response.containsKey("response")) {
                throw new RuntimeException("Ollama 응답이 비어있습니다");
            }

            String rawResponse = (String) response.get("response");
            log.info("JSON 응답 받음 - 길이: {}자", rawResponse.length());

            // JSON 추출 및 파싱
            String extractedJson = extractJson(rawResponse);
            
            log.info("JSON 추출 완료 - 길이: {}자", extractedJson.length());
            
            // DEBUG: 실제 JSON 내용 출력 (처음 1000자)
            log.info("=== AI 응답 JSON (처음 1000자) ===\n{}", 
                    extractedJson.substring(0, Math.min(1000, extractedJson.length())));
            log.info("=== AI 응답 JSON 끝 ===");
            
            // JSON 파싱
            T result = objectMapper.readValue(extractedJson, responseClass);
            log.info("JSON 파싱 성공 - 타입: {}", responseClass.getSimpleName());
            
            return result;

        } catch (WebClientResponseException e) {
            log.error("Ollama API 호출 실패 - 상태: {}", e.getStatusCode());
            throw new RuntimeException(
                    String.format("Ollama API 호출 실패 (상태: %s): %s", 
                            e.getStatusCode(), e.getMessage()),
                    e
            );
        } catch (JsonProcessingException e) {
            log.error("JSON 파싱 실패: {}", e.getMessage());
            throw new RuntimeException(
                    String.format("AI 응답을 JSON으로 파싱하는 데 실패했습니다: %s", 
                            e.getMessage()),
                    e
            );
        } catch (Exception e) {
            log.error("Ollama 서버 연결 실패", e);
            throw new RuntimeException(
                    String.format("Ollama 서버 연결 실패: %s", e.getMessage()),
                    e
            );
        }
    }
    
    /**
     * 모델 예열 (첫 요청 시 느린 로딩 방지)
     */
    private void warmupModel() {
        try {
            log.info("모델 예열 중...");
            Map<String, Object> warmupRequest = Map.of(
                    "model", model,
                    "prompt", "Hello",
                    "stream", false
            );
            
            webClient.post()
                    .uri("/api/generate")
                    .bodyValue(warmupRequest)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            
            log.info("모델 예열 완료");
        } catch (Exception e) {
            log.warn("모델 예열 실패 (무시): {}", e.getMessage());
        }
    }

    /**
     * 응답에서 JSON 부분만 추출
     * (마크다운 코드 블록이나 추가 텍스트 제거)
     */
    private String extractJson(String response) {
        log.debug("원본 응답: {}", response);
        
        String cleaned = response.trim();
        
        // 1. 마크다운 코드 블록 제거
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        
        cleaned = cleaned.trim();

        // 2. JSON 시작/끝 찾기
        int jsonStart = cleaned.indexOf('{');
        int jsonEnd = cleaned.lastIndexOf('}');

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            String extracted = cleaned.substring(jsonStart, jsonEnd + 1);
            log.debug("추출된 JSON: {}", extracted.substring(0, Math.min(200, extracted.length())));
            return extracted;
        }

        // 3. JSON을 찾지 못한 경우
        log.warn("JSON을 찾을 수 없습니다. 원본 응답을 그대로 사용 시도");
        
        // 마지막 시도: 전체 응답이 JSON인지 확인
        if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
            return cleaned;
        }
        
        // 실패: 응답 일부만 로그
        log.error("JSON 추출 실패 - 응답 시작: {}", 
                cleaned.substring(0, Math.min(200, cleaned.length())));
        
        return cleaned;
    }

    /**
     * Ollama 서버 상태 확인
     */
    public boolean isAvailable() {
        try {
            webClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            return true;
        } catch (Exception e) {
            log.warn("Ollama 서버 접근 불가: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 설치된 모델 목록 조회
     */
    public String getInstalledModels() {
        try {
            String response = webClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            return response;
        } catch (Exception e) {
            log.error("모델 목록 조회 실패", e);
            return null;
        }
    }
}
