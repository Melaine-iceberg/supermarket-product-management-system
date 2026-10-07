package com.smms.common.task;

import java.nio.file.Path;

/**
 * 任务结果下载句柄：由服务层完成权限与状态校验后返回给控制器。
 *
 * @param path        结果文件绝对路径
 * @param fileName    下载时的默认文件名
 * @param contentType HTTP Content-Type
 * @param size        文件字节数（可为 null）
 */
public record TaskDownload(Path path, String fileName, String contentType, Long size) {
}
