package com.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

/**
 * 테이블 접근 정보 (테이블명 + CRUD 작업)
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableAccess {
    
    /**
     * 테이블명 (예: TCOM_USER, DUAL)
     */
    private String tableName;
    
    /**
     * 수행되는 CRUD 작업들
     */
    @Builder.Default
    private Set<CrudOperation> operations = new HashSet<>();
    
    /**
     * CRUD 작업 타입
     */
    public enum CrudOperation {
        CREATE,  // INSERT
        READ,    // SELECT
        UPDATE,  // UPDATE
        DELETE   // DELETE
    }
    
    /**
     * 작업 추가
     */
    public void addOperation(CrudOperation operation) {
        this.operations.add(operation);
    }
    
    /**
     * READ 작업 포함 여부
     */
    public boolean hasRead() {
        return operations.contains(CrudOperation.READ);
    }
    
    /**
     * 쓰기 작업(CUD) 포함 여부
     */
    public boolean hasWrite() {
        return operations.contains(CrudOperation.CREATE) ||
               operations.contains(CrudOperation.UPDATE) ||
               operations.contains(CrudOperation.DELETE);
    }
    
    @Override
    public String toString() {
        return tableName + operations;
    }
}
