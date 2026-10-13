package com.smms.goods;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.service.GoodsImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品图片上传服务用例（IR-SW-05 / GAP-P1-01）：
 * jpg/png、≤2MB、扩展名+MIME+文件头三重校验、UUID 重命名、受控读取、防目录穿越。
 */
class GoodsImageServiceTest {

    @TempDir
    Path tempDir;

    private GoodsImageService service;

    @BeforeEach
    void setUp() {
        service = new GoodsImageService(tempDir.toString());
    }

    private static byte[] jpegHead() {
        // FFD8FF + 占位内容
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1, 2, 3};
    }

    private static byte[] pngHead() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 1, 2, 3};
    }

    @Test
    @DisplayName("jpg 上传：UUID 重命名、不保留原始文件名，可在上传目录读回")
    void storeJpeg_renamesAndLoadsBack() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "门店照片.JPG", "image/jpeg", jpegHead());

        String path = service.store(file);

        assertThat(path).matches("[0-9a-f-]{36}\\.jpg");
        assertThat(path).doesNotContain("门店照片");
        assertThat(Files.exists(tempDir.resolve(path))).isTrue();

        GoodsImageService.ImageFile image = service.load(path);
        assertThat(image.contentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(image.bytes()).isEqualTo(jpegHead());
    }

    @Test
    @DisplayName("png 上传：允许 image/png，读回 Content-Type 为 image/png")
    void storePng_ok() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", pngHead());

        String path = service.store(file);

        assertThat(path).matches("[0-9a-f-]{36}\\.png");
        assertThat(service.load(path).contentType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    @DisplayName("image/jpg 别名 MIME 与 JPG 大写扩展名均可接受")
    void store_aliasMimeAndUpperExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpeg", "image/jpg", jpegHead());

        String path = service.store(file);

        assertThat(path).endsWith(".jpeg");
    }

    @Test
    @DisplayName("超过 2MB 拒绝（错误码 1310）")
    void store_tooLarge_rejected() {
        byte[] big = new byte[(int) GoodsImageService.MAX_BYTES + 1];
        System.arraycopy(jpegHead(), 0, big, 0, jpegHead().length);
        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", big);

        assertThatThrownBy(() -> service.store(file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.GOODS_IMAGE_TOO_LARGE.getCode());
    }

    @Test
    @DisplayName("非 jpg/png 扩展名拒绝（gif/exe/无扩展名）")
    void store_badExtension_rejected() {
        for (String name : new String[]{"a.gif", "a.exe", "a", "a.jpg.exe"}) {
            MockMultipartFile file = new MockMultipartFile("file", name, "image/jpeg", jpegHead());
            assertThatThrownBy(() -> service.store(file), name)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.GOODS_IMAGE_TYPE_INVALID.getCode());
        }
    }

    @Test
    @DisplayName("扩展名与 MIME 不一致（.jpg + image/png）拒绝")
    void store_mimeMismatch_rejected() {
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/png", jpegHead());

        assertThatThrownBy(() -> service.store(file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.GOODS_IMAGE_TYPE_INVALID.getCode());
    }

    @Test
    @DisplayName("伪造扩展名（文本内容改名 .jpg）被文件头校验拒绝")
    void store_fakeMagic_rejected() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.jpg", "image/jpeg",
                "not-an-image-at-all".getBytes());

        assertThatThrownBy(() -> service.store(file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.GOODS_IMAGE_TYPE_INVALID.getCode());
    }

    @Test
    @DisplayName("空文件拒绝（400 参数不合法）")
    void store_empty_rejected() {
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> service.store(file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("读取防目录穿越：非 UUID 文件名、路径分隔符一律 404")
    void load_pathTraversal_rejected() {
        for (String name : new String[]{"../application.yaml", "..%2Fsecret.jpg", "abc.jpg",
                "00000000-0000-0000-0000-000000000000.png"}) {
            assertThatThrownBy(() -> service.load(name), name)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.NOT_FOUND.getCode());
        }
    }

    @Test
    @DisplayName("受控读取：合法 UUID 名但文件不存在返回 404")
    void load_notFound() {
        assertThatThrownBy(() -> service.load("11111111-2222-3333-4444-555555555555.jpg"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }
}
