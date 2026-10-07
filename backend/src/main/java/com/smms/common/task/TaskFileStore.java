package com.smms.common.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * 任务结果文件存储：容器本地临时目录，仅保留在任务有效期内（默认 24 小时，由清理任务删除）。
 */
@Component
public class TaskFileStore {

    private static final Logger log = LoggerFactory.getLogger(TaskFileStore.class);

    private final Path baseDir;

    public TaskFileStore(@Value("${smms.task.file-dir:${java.io.tmpdir}/smms-task-files}") String dir) {
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("任务文件目录创建失败：" + baseDir, e);
        }
        log.info("任务文件目录：{}", baseDir);
    }

    /** 保存结果文件，返回绝对路径 */
    public Path save(String taskNo, String fileName, byte[] bytes) {
        try {
            Path target = baseDir.resolve(taskNo + suffix(fileName));
            Files.write(target, bytes);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("任务结果写入失败", e);
        }
    }

    /** 保存上传的临时文件（导入任务） */
    public Path saveUpload(String taskNo, String originalName, byte[] bytes) {
        try {
            Path target = baseDir.resolve("upload-" + taskNo + suffix(originalName));
            Files.write(target, bytes);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("上传文件暂存失败", e);
        }
    }

    public byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("任务结果读取失败：" + path, e);
        }
    }

    /** 删除文件（不存在时静默忽略） */
    public void deleteQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            log.warn("任务文件删除失败：{}", path, e);
        }
    }

    /** 清理超过保留期的遗留文件（含异常退出残留的上传临时文件），返回删除数量 */
    public int deleteOlderThan(Duration age) {
        Instant threshold = Instant.now().minus(age);
        int deleted = 0;
        try (Stream<Path> files = Files.list(baseDir)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(threshold)) {
                        Files.deleteIfExists(path);
                        deleted++;
                    }
                } catch (IOException e) {
                    log.warn("任务文件清理失败：{}", path, e);
                }
            }
        } catch (IOException e) {
            log.warn("任务文件目录扫描失败：{}", baseDir, e);
        }
        return deleted;
    }

    private String suffix(String fileName) {
        if (fileName == null) {
            return ".xlsx";
        }
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? ".xlsx" : fileName.substring(idx);
    }
}
