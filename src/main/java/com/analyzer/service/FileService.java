package com.analyzer.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 파일 시스템 유틸리티 서비스
 * zip 압축 해제, 파일/디렉토리 삭제, 파일 스캔 등
 */
@Slf4j
@Service
public class FileService {

    /**
     * 제외할 디렉토리 목록
     */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "build", "target", ".idea", "out", 
            ".gradle", "node_modules", ".vscode", "bin"
    );

    /**
     * zip 파일 압축 해제 (Zip Slip 방지 포함)
     * 
     * @param zipFilePath 압축 파일 경로
     * @param destDirectory 압축 해제 대상 디렉토리
     * @throws IOException 압축 해제 실패 시
     */
    public void extractZip(String zipFilePath, String destDirectory) throws IOException {
        Path destPath = Paths.get(destDirectory).normalize();
        
        // 대상 디렉토리 생성
        if (!Files.exists(destPath)) {
            Files.createDirectories(destPath);
        }

        log.info("zip 압축 해제 시작 - from: {}, to: {}", zipFilePath, destDirectory);

        int extractedCount = 0;
        
        try (InputStream fis = Files.newInputStream(Paths.get(zipFilePath));
             BufferedInputStream bis = new BufferedInputStream(fis);
             ZipArchiveInputStream zis = new ZipArchiveInputStream(bis)) {

            ZipArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // Zip Slip 방지: 경로 검증
                Path targetPath = destPath.resolve(entry.getName()).normalize();
                
                if (!targetPath.startsWith(destPath)) {
                    log.warn("Zip Slip 공격 시도 감지 - entry: {}", entry.getName());
                    throw new IOException("잘못된 zip 파일 경로: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    // 부모 디렉토리 생성
                    Files.createDirectories(targetPath.getParent());
                    
                    // 파일 쓰기
                    try (OutputStream os = Files.newOutputStream(targetPath)) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, length);
                        }
                    }
                    extractedCount++;
                }
            }
        }

        log.info("zip 압축 해제 완료 - 파일 수: {}", extractedCount);
    }

    /**
     * 디렉토리에서 모든 Java 파일 스캔
     * 제외 폴더를 제외한 모든 .java 파일의 절대 경로 수집
     * 
     * @param rootDirectory 스캔할 루트 디렉토리
     * @return Java 파일 경로 리스트
     * @throws IOException 파일 스캔 실패 시
     */
    public List<Path> scanJavaFiles(String rootDirectory) throws IOException {
        Path rootPath = Paths.get(rootDirectory);
        
        if (!Files.exists(rootPath)) {
            log.warn("디렉토리가 존재하지 않습니다: {}", rootDirectory);
            return new ArrayList<>();
        }

        log.info("Java 파일 스캔 시작 - 경로: {}", rootDirectory);

        List<Path> javaFiles;
        
        try (Stream<Path> paths = Files.walk(rootPath)) {
            javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isInExcludedDirectory(rootPath, path))
                    .map(Path::toAbsolutePath)
                    .collect(Collectors.toList());
        }

        log.info("Java 파일 스캔 완료 - 파일 수: {}", javaFiles.size());

        return javaFiles;
    }

    /**
     * 파일이 제외 디렉토리에 포함되어 있는지 확인
     * 
     * @param rootPath 루트 경로
     * @param filePath 검사할 파일 경로
     * @return 제외 디렉토리에 포함되어 있으면 true
     */
    private boolean isInExcludedDirectory(Path rootPath, Path filePath) {
        Path relativePath = rootPath.relativize(filePath);
        
        for (Path part : relativePath) {
            String partStr = part.toString();
            
            // 제외 디렉토리 체크
            if (EXCLUDED_DIRS.contains(partStr)) {
                return true;
            }
            
            // __MACOSX 폴더 (macOS zip 메타데이터)
            if (partStr.equals("__MACOSX") || partStr.startsWith("__MACOSX")) {
                return true;
            }
            
            // 숨김 파일/폴더 (.으로 시작)
            if (partStr.startsWith(".") && !partStr.equals(".")) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 스캔 통계 정보 생성
     * 
     * @param rootDirectory 스캔할 루트 디렉토리
     * @return 스캔 통계 정보
     */
    public ScanStatistics getScanStatistics(String rootDirectory) throws IOException {
        Path rootPath = Paths.get(rootDirectory);
        
        if (!Files.exists(rootPath)) {
            return new ScanStatistics(0, 0, 0);
        }

        int totalFiles = 0;
        int javaFiles = 0;
        int excludedFiles = 0;

        try (Stream<Path> paths = Files.walk(rootPath)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isRegularFile(path)) {
                    totalFiles++;
                    
                    if (path.toString().endsWith(".java")) {
                        if (isInExcludedDirectory(rootPath, path)) {
                            excludedFiles++;
                        } else {
                            javaFiles++;
                        }
                    }
                }
            }
        }

        return new ScanStatistics(totalFiles, javaFiles, excludedFiles);
    }

    /**
     * 스캔 통계 정보 클래스
     */
    public record ScanStatistics(
            int totalFiles,
            int javaFiles,
            int excludedJavaFiles
    ) {}


    /**
     * 디렉토리 또는 파일 삭제
     */
    public void deleteDirectory(String path) {
        try {
            Path dirPath = Paths.get(path);
            if (Files.exists(dirPath)) {
                if (Files.isDirectory(dirPath)) {
                    Files.walk(dirPath)
                            .sorted((a, b) -> -a.compareTo(b)) // 하위부터 삭제
                            .forEach(p -> {
                                try {
                                    Files.delete(p);
                                } catch (IOException e) {
                                    // 삭제 실패 시 무시
                                }
                            });
                } else {
                    Files.delete(dirPath);
                }
            }
        } catch (IOException e) {
            // 삭제 실패 시 무시
        }
    }
}
