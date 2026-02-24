package com.analyzer.rag;

import com.analyzer.model.TableAccess;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 시스템의 기본 단위: 코드 청크
 * 
 * 코드를 의미 있는 단위(클래스, 메서드)로 나눈 것
 * 벡터 임베딩과 메타데이터를 함께 저장
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeChunk {
    
    /**
     * 고유 ID (file:startLine:endLine)
     */
    private String id;
    
    /**
     * 실제 코드 내용
     */
    private String code;
    
    /**
     * 파일 경로
     */
    private String filePath;
    
    /**
     * 시작 라인 번호
     */
    private int startLine;
    
    /**
     * 종료 라인 번호
     */
    private int endLine;
    
    /**
     * 청크 타입 (CLASS, METHOD, INTERFACE 등)
     */
    private ChunkType type;
    
    /**
     * 클래스명 또는 메서드명
     */
    private String name;
    
    /**
     * Spring 컴포넌트 타입 (Controller, Service, Repository 등)
     */
    private String componentType;
    
    /**
     * 어노테이션 목록
     */
    private List<String> annotations;
    
    /**
     * 의존성 (이 청크가 사용하는 다른 클래스들)
     */
    private List<String> dependencies;
    
    /**
     * 주입된 필드 정보 (타입 → 변수명 매핑)
     * 예: {"UserService": "userService", "LoginDAO": "loginDAO"}
     */
    private java.util.Map<String, String> injectedFieldMap;
    
    /**
     * 메서드 호출 정보 (변수명.메서드명 쌍)
     * 예: [{"object": "userService", "method": "findById"}, ...]
     */
    private List<ServiceMethodCall> serviceMethodCalls;
    
    /**
     * 서비스 메서드 호출 정보
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceMethodCall {
        private String fieldName;       // 변수명 (예: userService)
        private String fieldType;       // 타입명 (예: UserService)
        private String methodName;      // 메서드명 (예: findById)
    }
    
    /**
     * MyBatis 호출
     */
    private List<String> myBatisCalls;
    
    /**
     * 접근하는 테이블 (테이블명만, 기존 호환)
     */
    private List<String> accessedTables;
    
    /**
     * 테이블 접근 정보 (CRUD 작업 포함) ⭐ NEW
     */
    private List<TableAccess> tableAccesses;
    
    /**
     * MyBatis Mapper 파싱 실패 원인 리포트 (DAO 컴포넌트에만 해당)
     * 예: ["NS_MISMATCH: EmsDAO_SQL.xml", "<include refid> 사용 (테이블 누락 가능)"]
     */
    private List<String> mapperIssues;

    /**
     * 패키지명
     */
    private String packageName;
    
    /**
     * 청크 타입
     */
    public enum ChunkType {
        CLASS,
        METHOD,
        INTERFACE,
        ENUM,
        ANNOTATION
    }
    
    /**
     * 청크 ID 생성
     */
    public static String generateId(String filePath, int startLine, int endLine) {
        return String.format("%s:%d:%d", filePath, startLine, endLine);
    }
}
