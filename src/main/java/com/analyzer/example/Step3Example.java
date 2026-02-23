package com.analyzer.example;

import com.analyzer.service.FileService;
import com.analyzer.service.FileScanService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * STEP 3 사용 예제
 * 
 * 이 예제는 파일 스캔의 전체 흐름을 보여줍니다.
 */
public class Step3Example {

    public static void main(String[] args) throws IOException {
        FileService fileService = new FileService();
        FileScanService scanService = new FileScanService(fileService);

        // 1. zip 압축 해제 (Zip Slip 방지)
        String zipFile = "./test-project.zip";
        String extractPath = "./data/projects/test-project-id/repo";
        
        System.out.println("=== 1. zip 압축 해제 ===");
        fileService.extractZip(zipFile, extractPath);
        System.out.println("압축 해제 완료: " + extractPath);

        // 2. Java 파일 스캔
        System.out.println("\n=== 2. Java 파일 스캔 ===");
        List<Path> javaFiles = scanService.scanJavaFiles(extractPath);
        System.out.println("발견된 Java 파일 수: " + javaFiles.size());

        // 3. 파일 목록 출력
        System.out.println("\n=== 3. Java 파일 목록 ===");
        javaFiles.forEach(file -> {
            System.out.println("  - " + file.toAbsolutePath());
        });

        // 4. 스캔 통계
        System.out.println("\n=== 4. 스캔 통계 ===");
        FileScanService.ScanResult result = scanService.getScanStatistics(extractPath);
        System.out.println("총 파일: " + result.totalFiles());
        System.out.println("Java 파일: " + result.javaFiles());
        System.out.println("제외된 Java 파일: " + result.excludedJavaFiles());
        System.out.println("요약: " + result.getSummary());

        // 5. 제외 디렉토리 확인
        System.out.println("\n=== 5. 제외된 디렉토리 ===");
        System.out.println("다음 디렉토리는 스캔에서 제외됩니다:");
        System.out.println("  - .git (Git 저장소)");
        System.out.println("  - build (Gradle 빌드 출력)");
        System.out.println("  - target (Maven 빌드 출력)");
        System.out.println("  - .idea (IntelliJ 설정)");
        System.out.println("  - out (컴파일 출력)");
        System.out.println("  - .gradle (Gradle 캐시)");
        System.out.println("  - node_modules (Node.js 의존성)");
        System.out.println("  - .vscode (VS Code 설정)");
        System.out.println("  - bin (바이너리)");
    }

    /**
     * 압축 해제 예제
     */
    public static void exampleExtractZip() throws IOException {
        FileService fileService = new FileService();

        String zipFilePath = "./project.zip";
        String destDirectory = "./data/projects/project-id/repo";

        // Zip Slip 방지가 자동으로 적용됨
        fileService.extractZip(zipFilePath, destDirectory);

        System.out.println("✅ 압축 해제 완료!");
        System.out.println("📂 경로: " + destDirectory);
    }

    /**
     * 파일 스캔 예제
     */
    public static void exampleScanFiles() throws IOException {
        FileService fileService = new FileService();

        String projectDir = "./data/projects/project-id/repo";

        // .git, build, target 등이 제외된 Java 파일 목록
        List<Path> javaFiles = fileService.scanJavaFiles(projectDir);

        System.out.println("✅ 스캔 완료!");
        System.out.println("📊 Java 파일 수: " + javaFiles.size());

        // 파일 경로 출력
        javaFiles.forEach(file -> {
            System.out.println("  📄 " + file);
        });
    }

    /**
     * 스캔 통계 예제
     */
    public static void exampleScanStatistics() throws IOException {
        FileService fileService = new FileService();

        String projectDir = "./data/projects/project-id/repo";

        FileService.ScanStatistics stats = fileService.getScanStatistics(projectDir);

        System.out.println("=== 스캔 통계 ===");
        System.out.println("📦 총 파일: " + stats.totalFiles() + "개");
        System.out.println("☕ Java 파일: " + stats.javaFiles() + "개");
        System.out.println("🚫 제외된 Java 파일: " + stats.excludedJavaFiles() + "개");
    }

    /**
     * Zip Slip 방지 테스트
     */
    public static void exampleZipSlipPrevention() {
        System.out.println("=== Zip Slip 방지 예제 ===\n");

        System.out.println("❌ 악의적인 zip 엔트리:");
        System.out.println("  - ../../../etc/passwd");
        System.out.println("  - ../../.ssh/authorized_keys");

        System.out.println("\n✅ 방어 로직:");
        System.out.println("  1. normalize()로 경로 정규화");
        System.out.println("  2. startsWith()로 대상 디렉토리 검증");
        System.out.println("  3. 벗어나면 IOException 발생");

        System.out.println("\n🛡️ 결과:");
        System.out.println("  - 시스템 파일 덮어쓰기 불가");
        System.out.println("  - 안전하게 지정된 디렉토리에만 압축 해제");
    }
}
