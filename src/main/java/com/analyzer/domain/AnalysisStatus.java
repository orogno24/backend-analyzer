package com.analyzer.domain;

/**
 * 프로젝트 분석 상태
 */
public enum AnalysisStatus {
    /**
     * 업로드 완료, 분석 대기
     */
    UPLOADED,

    /**
     * 분석 진행 중
     */
    ANALYZING,

    /**
     * 분석 완료
     */
    COMPLETED,

    /**
     * 분석 실패
     */
    FAILED
}
