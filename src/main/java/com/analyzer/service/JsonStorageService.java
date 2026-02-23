package com.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * JSON 파일 저장/로드 서비스
 * Jackson을 사용한 직렬화/역직렬화
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JsonStorageService {

    private final ObjectMapper objectMapper;

    /**
     * 객체를 JSON 파일로 저장
     * 
     * @param object 저장할 객체
     * @param filePath 저장 경로
     * @param <T> 객체 타입
     */
    public <T> void saveAsJson(T object, String filePath) throws IOException {
        Path path = Paths.get(filePath);

        // 디렉토리 생성
        Files.createDirectories(path.getParent());

        // Pretty print 설정
        ObjectMapper prettyMapper = objectMapper.copy();
        prettyMapper.enable(SerializationFeature.INDENT_OUTPUT);

        // JSON 직렬화 및 저장 (덮어쓰기)
        String json = prettyMapper.writeValueAsString(object);
        Files.writeString(path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        log.info("JSON 저장 완료 - {}", filePath);
    }

    /**
     * JSON 파일을 객체로 로드
     * 
     * @param filePath 파일 경로
     * @param clazz 객체 클래스
     * @param <T> 객체 타입
     * @return 역직렬화된 객체
     */
    public <T> T loadFromJson(String filePath, Class<T> clazz) throws IOException {
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            throw new IOException("파일이 존재하지 않습니다: " + filePath);
        }

        String json = Files.readString(path);
        T object = objectMapper.readValue(json, clazz);

        log.info("JSON 로드 완료 - {}", filePath);

        return object;
    }

    /**
     * 객체를 JSON 문자열로 변환 (Pretty print)
     * 
     * @param object 변환할 객체
     * @param <T> 객체 타입
     * @return JSON 문자열
     */
    public <T> String toJsonString(T object) throws IOException {
        ObjectMapper prettyMapper = objectMapper.copy();
        prettyMapper.enable(SerializationFeature.INDENT_OUTPUT);
        return prettyMapper.writeValueAsString(object);
    }

    /**
     * JSON 문자열을 객체로 변환
     * 
     * @param json JSON 문자열
     * @param clazz 객체 클래스
     * @param <T> 객체 타입
     * @return 역직렬화된 객체
     */
    public <T> T fromJsonString(String json, Class<T> clazz) throws IOException {
        return objectMapper.readValue(json, clazz);
    }

    /**
     * 파일 존재 여부 확인
     * 
     * @param filePath 파일 경로
     * @return 존재 여부
     */
    public boolean exists(String filePath) {
        return Files.exists(Paths.get(filePath));
    }

    /**
     * 파일 삭제
     * 
     * @param filePath 파일 경로
     */
    public void delete(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (Files.exists(path)) {
            Files.delete(path);
            log.info("JSON 파일 삭제 완료 - {}", filePath);
        }
    }
}
