package com.analyzer.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 검색 결과 Bundle
 * 
 * 주 청크 + 관련 컴포넌트들 (의존성 체인)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchBundle {
    
    /**
     * 주요 청크 (검색에 매칭된 것)
     */
    private CodeChunk primaryChunk;
    
    /**
     * 근거 리스트 (주 청크 + 의존성 체인)
     */
    private List<Evidence> evidence;
    
    /**
     * 총 근거 개수
     */
    private int totalEvidence;
}
