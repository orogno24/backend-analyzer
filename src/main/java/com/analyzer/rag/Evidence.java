package com.analyzer.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 근거 (Evidence)
 * 
 * Bundle에 포함된 각 코드 조각의 상세 정보
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Evidence {
    
    /**
     * 파일 경로
     */
    private String file;
    
    /**
     * 시작 라인
     */
    private int startLine;
    
    /**
     * 종료 라인
     */
    private int endLine;
    
    /**
     * 컴포넌트명
     */
    private String componentName;
    
    /**
     * 컴포넌트 타입 (Controller, Service, Repository 등)
     */
    private String componentType;
    
    /**
     * 코드 스니펫 (앞부분만)
     */
    private String snippet;
    
    /**
     * 이 근거가 포함된 이유
     */
    private String reason;
}
