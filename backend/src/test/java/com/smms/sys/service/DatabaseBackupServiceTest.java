package com.smms.sys.service;

import com.smms.sys.dto.SysBackupRecordVO;
import com.smms.sys.entity.SysBackupRecord;
import com.smms.sys.mapper.SysBackupRecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 数据备份服务用例（FR-SYS-10 / GAP-P2-23）：
 * 命令构造（密码不进命令行）、JDBC 地址解析、失败必留记录、保留策略。
 * 真实 mysqldump 备份由部署环境验证（后端镜像已安装 default-mysql-client）。
 */
@ExtendWith(MockitoExtension.class)
class DatabaseBackupServiceTest {

    @Mock
    private SysBackupRecordMapper backupMapper;

    @TempDir
    Path tempDir;

    private DatabaseBackupService service(String jdbcUrl, String binary) {
        return new DatabaseBackupService(backupMapper, jdbcUrl, "smms", "secret-pwd",
                tempDir.toString(), 30, binary);
    }

    @Test
    @DisplayName("命令构造：解析 JDBC 地址的 host/port/db，含全量备份必需参数，且不含密码明文")
    void buildCommand_parsesUrlAndHidesPassword() {
        DatabaseBackupService svc = service(
                "jdbc:mysql://mysql:3307/smms?useUnicode=true&serverTimezone=Asia/Shanghai", "mysqldump");

        List<String> cmd = svc.buildCommand(tempDir.resolve("x.sql"));

        assertThat(cmd).contains("--host=mysql", "--port=3307", "--user=smms",
                "--single-transaction", "--routines", "--triggers", "--databases", "smms");
        assertThat(String.join(" ", cmd)).doesNotContain("secret-pwd");
    }

    @Test
    @DisplayName("JDBC 地址缺省端口时回退 3306")
    void buildCommand_defaultsPort() {
        DatabaseBackupService svc = service("jdbc:mysql://localhost/smms", "mysqldump");

        List<String> cmd = svc.buildCommand(tempDir.resolve("x.sql"));

        assertThat(cmd).contains("--host=localhost", "--port=3306");
    }

    @Test
    @DisplayName("备份失败（二进制不可用）：仍落 FAILED 记录并带失败原因，页面可查")
    void backup_missingBinary_recordsFailed() {
        DatabaseBackupService svc = service("jdbc:mysql://localhost:3306/smms",
                "definitely-not-exists-mysqldump");

        SysBackupRecordVO vo = svc.backup("MANUAL", "admin");

        assertThat(vo.status()).isEqualTo("FAILED");
        assertThat(vo.errorMessage()).isNotBlank();
        assertThat(vo.fileSize()).isZero();
        ArgumentCaptor<SysBackupRecord> captor = ArgumentCaptor.forClass(SysBackupRecord.class);
        verify(backupMapper).insert(captor.capture());
        assertThat(captor.getValue().getTriggerType()).isEqualTo("MANUAL");
        assertThat(captor.getValue().getTriggerBy()).isEqualTo("admin");
        assertThat(captor.getValue().getDurationMs()).isNotNull();
    }

    @Test
    @DisplayName("保留策略：仅保留最近 30 份（按文件名时间序），非备份文件不受影响")
    void pruneRetention_keepsLatest() throws Exception {
        IntStream.rangeClosed(1, 33).forEach(i -> {
            try {
                Files.writeString(tempDir.resolve(String.format("smms-backup-202610%02d-230000.sql", i)), "x");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Files.writeString(tempDir.resolve("readme.txt"), "keep me");
        DatabaseBackupService svc = service("jdbc:mysql://localhost:3306/smms", "mysqldump");

        int pruned = svc.pruneRetention();

        assertThat(pruned).isEqualTo(3);
        try (var files = Files.list(tempDir)) {
            long backups = files.filter(p -> p.getFileName().toString().startsWith("smms-backup-")).count();
            assertThat(backups).isEqualTo(30);
        }
        assertThat(Files.exists(tempDir.resolve("readme.txt"))).isTrue();
    }
}
