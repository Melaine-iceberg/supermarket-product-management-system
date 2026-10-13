package com.smms.sys.service;

import com.smms.sys.dto.SysBackupRecordVO;
import com.smms.sys.entity.SysBackupRecord;
import com.smms.sys.mapper.SysBackupRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 数据备份服务（FR-SYS-10 / GAP-P2-23）：
 * 调用 mysqldump 全量备份（--single-transaction --routines --triggers），
 * 文件存于可配置备份目录，保留最近 N 份（默认 30，与 SRS 一致）；
 * 每次执行（定时/手工一键）落 sys_backup_record 供页面查看。
 * 密码仅经进程环境变量 MYSQL_PWD 传递，不出现在命令行（避免 ps 泄露，NFR-SEC-07）。
 */
@Slf4j
@Service
public class DatabaseBackupService {

    private static final Pattern URL_PATTERN =
            Pattern.compile("jdbc:mysql://([^/:?]+)(?::(\\d+))?/([^?]+).*");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 备份超时（分钟），超大库可调大 */
    private static final long TIMEOUT_MINUTES = 30;
    private static final String FILE_PREFIX = "smms-backup-";
    private static final int ERROR_LIMIT = 500;

    private final SysBackupRecordMapper backupMapper;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Path dir;
    private final int retain;
    private final String mysqldump;

    public DatabaseBackupService(SysBackupRecordMapper backupMapper,
                                 @Value("${spring.datasource.url}") String jdbcUrl,
                                 @Value("${spring.datasource.username}") String username,
                                 @Value("${spring.datasource.password}") String password,
                                 @Value("${smms.backup.dir}") String dir,
                                 @Value("${smms.backup.retain}") int retain,
                                 @Value("${smms.backup.mysqldump}") String mysqldump) {
        this.backupMapper = backupMapper;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.dir = Paths.get(dir).toAbsolutePath().normalize();
        this.retain = retain <= 0 ? 30 : retain;
        this.mysqldump = mysqldump;
    }

    /** 执行一次全量备份：成功返回记录（含文件大小），失败返回 FAILED 记录（错误原因截断 500 字） */
    public SysBackupRecordVO backup(String triggerType, String triggerBy) {
        LocalDateTime startedAt = LocalDateTime.now();
        long start = System.currentTimeMillis();
        String fileName = FILE_PREFIX + startedAt.format(FILE_TS) + ".sql";
        Path file = dir.resolve(fileName);
        SysBackupRecord record = new SysBackupRecord();
        record.setFileName(fileName);
        record.setFilePath(file.toString());
        record.setTriggerType(triggerType);
        record.setTriggerBy(triggerBy);
        record.setStartedAt(startedAt);

        long size = 0;
        String error = null;
        try {
            Files.createDirectories(dir);
            ProcessBuilder pb = new ProcessBuilder(buildCommand(file));
            pb.redirectErrorStream(true);
            // 密码仅经环境变量传递（NFR-SEC-07：不出现在命令行/日志）
            pb.environment().put("MYSQL_PWD", password);
            Process process = pb.start();
            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new IllegalStateException("备份超时（超过 " + TIMEOUT_MINUTES + " 分钟）");
            }
            String output = readOutput(process);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("mysqldump 退出码 " + process.exitValue()
                        + (output.isBlank() ? "" : "：" + output.trim()));
            }
            size = Files.size(file);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "备份被中断";
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }

        boolean success = error == null;
        record.setStatus(success ? "SUCCESS" : "FAILED");
        record.setFileSize(size);
        record.setFinishedAt(LocalDateTime.now());
        record.setDurationMs(System.currentTimeMillis() - start);
        record.setErrorMessage(truncate(error));
        backupMapper.insert(record);
        if (success) {
            int pruned = pruneRetention();
            log.info("数据库备份完成: file={}, size={}B, 耗时={}ms, 触发={}, 清理历史文件={}",
                    fileName, size, record.getDurationMs(), triggerType, pruned);
        } else {
            log.error("数据库备份失败: file={}, 触发={}, 原因={}", fileName, triggerType, error);
        }
        return SysBackupRecordVO.of(record);
    }

    /** 构造 mysqldump 命令（不含密码；便于测试校验参数与防泄露） */
    public List<String> buildCommand(Path resultFile) {
        Target target = parseJdbcUrl();
        List<String> cmd = new ArrayList<>();
        cmd.add(mysqldump);
        cmd.add("--host=" + target.host());
        cmd.add("--port=" + target.port());
        cmd.add("--user=" + target.user());
        cmd.add("--single-transaction");
        cmd.add("--routines");
        cmd.add("--triggers");
        cmd.add("--databases");
        cmd.add(target.database());
        cmd.add("--result-file=" + resultFile.toAbsolutePath());
        return cmd;
    }

    /** 保留最近 N 份备份文件（按文件名时间序），返回清理数；历史记录保留可查 */
    int pruneRetention() {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> backups = files
                    .filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().startsWith(FILE_PREFIX)
                            && p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            int pruned = 0;
            for (int i = 0; i < backups.size() - retain; i++) {
                Files.deleteIfExists(backups.get(i));
                pruned++;
            }
            return pruned;
        } catch (IOException e) {
            log.warn("备份保留策略清理失败: {}", e.getMessage());
            return 0;
        }
    }

    private Target parseJdbcUrl() {
        Matcher m = URL_PATTERN.matcher(jdbcUrl);
        if (!m.matches()) {
            throw new IllegalStateException("无法从数据源地址解析备份参数：" + jdbcUrl);
        }
        return new Target(m.group(1), m.group(2) == null ? "3306" : m.group(2), m.group(3), username);
    }

    private static String readOutput(Process process) {
        try (var in = process.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= ERROR_LIMIT ? text : text.substring(0, ERROR_LIMIT);
    }

    /** JDBC 地址解析结果 */
    private record Target(String host, String port, String database, String user) {
    }
}
