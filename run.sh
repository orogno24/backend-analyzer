#!/bin/bash

echo "🚀 Backend Analyzer RAG 시스템 시작"
echo ""

# Gradle 빌드
echo "📦 1. Gradle 의존성 다운로드 중..."
./gradlew clean build -x test

if [ $? -ne 0 ]; then
    echo "❌ 빌드 실패!"
    exit 1
fi

echo "✅ 빌드 완료!"
echo ""

# 서버 시작
echo "🔄 2. Spring Boot 서버 시작 중..."
echo ""
echo "서버가 시작되면:"
echo "  - 웹: http://localhost:8080"
echo "  - API: http://localhost:8080/api/projects"
echo "  - RAG: http://localhost:8080/api/projects/{projectId}/rag/index"
echo ""

java -jar build/libs/backend-analyzer-0.0.1-SNAPSHOT.jar
