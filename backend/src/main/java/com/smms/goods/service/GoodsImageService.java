package com.smms.goods.service;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 商品图片存储服务（IR-SW-05）：
 * jpg/png、单文件 ≤2MB；扩展名 + MIME + 文件头三重校验；
 * 存储于可配置本地目录（与 Web 根隔离），UUID 重命名，禁止使用原始文件名；
 * 数据库保存相对路径，读取经受控接口（登录态）访问。
 */
@Slf4j
@Service
public class GoodsImageService {

    /** 单文件上限 2MB（IR-SW-05） */
    public static final long MAX_BYTES = 2 * 1024 * 1024;

    /** 允许的扩展名 → 响应 Content-Type */
    private static final Map<String, MediaType> EXT_TYPES = Map.of(
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "png", MediaType.IMAGE_PNG);

    /** 存储文件名白名单：UUID + 允许的扩展名（防目录穿越/枚举） */
    private static final Pattern STORED_NAME = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|jpeg|png)$");

    private static final Set<String> ALIAS_MIME = Set.of("image/jpg", "image/pjpeg", "image/x-png");

    private final Path root;

    public GoodsImageService(@Value("${smms.upload.dir}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    /**
     * 保存上传图片，返回数据库用的相对路径（{uuid}.{ext}）。
     * 校验顺序：空文件 → 大小 → 扩展名 → 文件头（magic number）→ MIME。
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择要上传的图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.GOODS_IMAGE_TOO_LARGE);
        }
        String ext = extension(file.getOriginalFilename());
        if (ext == null) {
            throw new BusinessException(ErrorCode.GOODS_IMAGE_TYPE_INVALID);
        }
        String mime = file.getContentType();
        MediaType expected = EXT_TYPES.get(ext);
        if (mime != null && !mime.isBlank()
                && !expected.toString().equalsIgnoreCase(mime) && !ALIAS_MIME.contains(mime.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.GOODS_IMAGE_TYPE_INVALID);
        }
        try (InputStream in = file.getInputStream()) {
            if (!magicMatches(ext, in)) {
                throw new BusinessException(ErrorCode.GOODS_IMAGE_TYPE_INVALID);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "读取上传图片失败");
        }
        String storedName = UUID.randomUUID() + "." + ext;
        try {
            Files.createDirectories(root);
            // 存储名完全由服务端生成（UUID），与用户输入无关，不存在目录穿越风险
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, root.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("商品图片写入失败: dir={}, 原因={}", root, e.getMessage(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片保存失败，请稍后重试");
        }
        log.info("商品图片已存储: file={}, size={}B", storedName, file.getSize());
        return storedName;
    }

    /** 读取存储图片（受控访问）：文件名须匹配 UUID 白名单，且解析路径必须位于上传目录内 */
    public ImageFile load(String filename) {
        String name = filename == null ? "" : filename.trim();
        if (!STORED_NAME.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "图片不存在");
        }
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "图片不存在");
        }
        try {
            return new ImageFile(Files.readAllBytes(target), EXT_TYPES.get(extension(name)));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片读取失败");
        }
    }

    /** 提取并规范扩展名；不允许/缺失时返回 null */
    private String extension(String originalFilename) {
        if (originalFilename == null) {
            return null;
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return null;
        }
        String ext = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXT_TYPES.containsKey(ext) ? ext : null;
    }

    /** 文件头校验：JPEG(FFD8FF) / PNG(89504E47...) */
    private boolean magicMatches(String ext, InputStream in) throws IOException {
        byte[] head = in.readNBytes(8);
        if (head.length < 8) {
            return false;
        }
        boolean jpeg = (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
        boolean png = (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A;
        return "png".equals(ext) ? png : jpeg;
    }

    /** 读取结果：字节内容 + 响应类型 */
    public record ImageFile(byte[] bytes, MediaType contentType) {
    }
}
