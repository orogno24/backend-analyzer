package com.analyzer.example;

import com.analyzer.dto.ServiceAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * STEP 6: Ollama AI 연동 예제
 * 서비스/엔티티 네이밍 및 역할 추천
 */
public class OllamaIntegrationExample {

    public static void main(String[] args) throws IOException {
        // 1. 샘플 AI 분석 결과 생성
        ServiceAnalysisResult result = createSampleResult();

        // 2. JSON으로 직렬화
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        String json = mapper.writeValueAsString(result);
        System.out.println("=== AI 분석 결과 JSON ===");
        System.out.println(json);
    }

    /**
     * 샘플 AI 분석 결과
     */
    private static ServiceAnalysisResult createSampleResult() {
        ServiceAnalysisResult result = new ServiceAnalysisResult();

        // 서비스 추천
        List<ServiceAnalysisResult.ServiceRecommendation> services = new ArrayList<>();
        
        services.add(new ServiceAnalysisResult.ServiceRecommendation(
                "UserService",
                "사용자 관리 서비스",
                "UserManagementService",
                "사용자 CRUD 및 인증/인가 관련 비즈니스 로직을 담당합니다. " +
                "사용자 생성, 조회, 수정, 삭제 기능을 제공하며, " +
                "비밀번호 암호화 및 검증을 수행합니다.",
                0.95
        ));

        services.add(new ServiceAnalysisResult.ServiceRecommendation(
                "OrderService",
                "주문 처리 서비스",
                "OrderProcessingService",
                "주문 생성, 상태 관리, 결제 연동을 담당합니다. " +
                "재고 확인, 결제 처리, 주문 이력 관리 등의 트랜잭션 처리를 수행합니다.",
                0.92
        ));

        result.setServices(services);

        // 엔티티 추천
        List<ServiceAnalysisResult.EntityRecommendation> entities = new ArrayList<>();

        entities.add(new ServiceAnalysisResult.EntityRecommendation(
                "User",
                "users",
                "회원",
                "시스템 사용자 정보를 저장하는 핵심 도메인 엔티티입니다. " +
                "사용자명, 이메일, 비밀번호, 권한 정보를 포함합니다.",
                0.98
        ));

        entities.add(new ServiceAnalysisResult.EntityRecommendation(
                "Order",
                "orders",
                "주문",
                "고객의 주문 정보를 저장합니다. " +
                "주문 상태, 총액, 주문 일시 등을 관리하며 OrderItem과 연관관계를 가집니다.",
                0.96
        ));

        entities.add(new ServiceAnalysisResult.EntityRecommendation(
                "Product",
                "products",
                "상품",
                "판매 가능한 상품 정보를 저장합니다. " +
                "상품명, 가격, 재고 수량, 카테고리 정보를 포함합니다.",
                0.94
        ));

        result.setEntities(entities);

        // 추가 질문
        List<String> questions = new ArrayList<>();
        questions.add("UserService와 AuthService를 분리하는 것이 좋을까요?");
        questions.add("Order와 Payment의 관계를 어떻게 설계하면 좋을까요?");
        questions.add("Product 엔티티에 재고 관리 로직을 넣어야 할까요, 아니면 별도 서비스로 분리해야 할까요?");

        result.setQuestions(questions);

        return result;
    }

    /**
     * API 사용 예제
     */
    public static void apiUsageExample() {
        System.out.println("=== API 사용 예제 ===\n");

        String projectId = "5462dcaf-7713-43de-82fe-466ad297a551";

        System.out.println("1. Ollama 상태 확인");
        System.out.println("   curl http://localhost:8080/projects/" + projectId + "/ai/health\n");

        System.out.println("2. AI 분석 실행");
        System.out.println("   curl -X POST http://localhost:8080/projects/" + projectId + "/ai/service-analysis\n");

        System.out.println("3. 저장된 분석 결과 조회");
        System.out.println("   curl http://localhost:8080/projects/" + projectId + "/ai/service-analysis\n");

        System.out.println("4. 결과 파일 확인");
        System.out.println("   cat ./data/projects/" + projectId + "/analysis/service-analysis.json\n");
    }

    /**
     * 예상 JSON 출력
     */
    public static void expectedJsonOutput() {
        String expectedJson = """
                {
                  "services" : [ {
                    "className" : "UserService",
                    "suggestedNameKo" : "사용자 관리 서비스",
                    "suggestedNameEn" : "UserManagementService",
                    "responsibility" : "사용자 CRUD 및 인증/인가 관련 비즈니스 로직을 담당합니다.",
                    "confidence" : 0.95
                  } ],
                  "entities" : [ {
                    "className" : "User",
                    "tableName" : "users",
                    "suggestedDomainName" : "회원",
                    "description" : "시스템 사용자 정보를 저장하는 핵심 도메인 엔티티입니다.",
                    "confidence" : 0.98
                  } ],
                  "questions" : [
                    "UserService와 AuthService를 분리하는 것이 좋을까요?"
                  ]
                }
                """;

        System.out.println("=== 예상 JSON 출력 ===");
        System.out.println(expectedJson);
    }
}
