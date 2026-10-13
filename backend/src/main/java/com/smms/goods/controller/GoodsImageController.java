package com.smms.goods.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.goods.service.GoodsImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Map;

/**
 * 商品图片上传与读取（IR-SW-05）：
 * 上传 jpg/png（≤2MB）返回相对路径；读取经受控接口（登录态），上传目录与 Web 根隔离。
 */
@RestController
@RequestMapping("/api/v1/goods/images")
@RequiredArgsConstructor
public class GoodsImageController {

    private final GoodsImageService goodsImageService;

    /** 上传商品图片：新增/编辑商品均可调用；返回数据库保存的相对路径 */
    @PostMapping
    @RequirePerm({"sys:goods:create", "sys:goods:update"})
    @OpLog(module = "商品管理", action = "上传商品图片")
    public ApiResponse<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(Map.of("path", goodsImageService.store(file)));
    }

    /** 读取商品图片（受控访问）：登录后凭 Token 访问，UUID 文件名白名单防穿越/枚举 */
    @GetMapping("/{filename}")
    public ResponseEntity<byte[]> get(@PathVariable String filename) {
        GoodsImageService.ImageFile image = goodsImageService.load(filename);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(image.contentType());
        headers.setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate());
        return ResponseEntity.ok().headers(headers).body(image.bytes());
    }
}
