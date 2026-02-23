package com.analyzer.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 분석 결과 응답 DTO
 */
@Getter
@AllArgsConstructor
public class AnalysisResultResponse {

    /**
     * 프로젝트 ID (UUID)
     */
    private String projectId;

    /**
     * 구조 분석 결과 JSON
     */
    private String structureAnalysis;

    /**
     * AI 분석 결과 (Ollama 응답)
     */
    private String aiAnalysis;
}
