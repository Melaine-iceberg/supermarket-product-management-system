package com.smms.sys.service;

import com.smms.sys.config.CodeProperties;
import com.smms.sys.entity.SysAuthCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云短信验证码发送器（V1.3）。
 *
 * <p>直接按阿里云「短信服务 SendSms」的 RPC 风格签名规则实现，使用 JDK 自带
 * {@link HttpClient} 与 {@code HmacSHA1}，<b>不引入阿里云 SDK</b>：
 * 少一个依赖、少一份升级负担，且签名逻辑可单元测试。</p>
 *
 * <p>签名算法（阿里云 RPC 接口 V1.0 规范）：</p>
 * <ol>
 *   <li>把公共参数与业务参数按参数名升序排列；</li>
 *   <li>逐个做 RFC3986 百分号编码后用 {@code &} 拼接成待签串；</li>
 *   <li>{@code StringToSign = "GET&%2F&" + encode(待签串)}；</li>
 *   <li>{@code Signature = Base64(HMAC-SHA1(AccessKeySecret + "&", StringToSign))}。</li>
 * </ol>
 *
 * <p>启用条件：{@code smms.code.sms.provider=aliyun}。凭证或模板不全时
 * {@link #available(String)} 返回 false，由 {@link CodeSenderRouter} 回退到日志模式。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "smms.code.sms.provider", havingValue = AliyunSmsAuthCodeSender.PROVIDER)
public class AliyunSmsAuthCodeSender implements AuthCodeSender {

    public static final String PROVIDER = "aliyun";

    private static final String API_VERSION = "2017-05-25";
    private static final String ACTION = "SendSms";
    private static final String SIGNATURE_METHOD = "HMAC-SHA1";
    private static final String SIGNATURE_VERSION = "1.0";
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final CodeProperties.Aliyun cfg;
    private final HttpClient httpClient;

    public AliyunSmsAuthCodeSender(CodeProperties codeProperties) {
        this.cfg = codeProperties.getSms().getAliyun();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        if (isConfigured()) {
            log.info("阿里云短信发送器已就绪：signName={} endpoint={} 登录模板={}",
                    cfg.getSignName(), cfg.getEndpoint(), cfg.getTemplateCodeLogin());
        }
    }

    private boolean isConfigured() {
        return notBlank(cfg.getAccessKeyId())
                && notBlank(cfg.getAccessKeySecret())
                && notBlank(cfg.getSignName())
                && notBlank(cfg.getTemplateCodeLogin());
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public boolean supports(String channel) {
        return SysAuthCode.CHANNEL_SMS.equals(channel);
    }

    @Override
    public boolean available(String channel) {
        return supports(channel) && isConfigured();
    }

    @Override
    public void send(String channel, String target, String code, String scene, int expireMinutes) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "阿里云短信未配置完整（需要 access-key-id/access-key-secret/sign-name/template-code-login）");
        }
        String templateCode = templateFor(scene);
        Map<String, String> params = new TreeMap<>();
        params.put("PhoneNumbers", target);
        params.put("SignName", cfg.getSignName());
        params.put("TemplateCode", templateCode);
        // 阿里云要求模板变量以 JSON 形式传入，模板里用 ${code} 取值
        params.put("TemplateParam", "{\"code\":\"" + code + "\"}");

        String url = buildSignedUrl(params);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() != 200) {
                log.error("阿里云短信 HTTP 失败：status={} body={}", response.statusCode(), truncate(body));
                throw new IllegalStateException("阿里云短信接口返回 " + response.statusCode());
            }
            // 成功响应形如 {"Message":"OK","RequestId":"...","Code":"OK","BizId":"..."}
            if (!body.contains("\"Code\":\"OK\"")) {
                String code2 = extract(body, "Code");
                String message = extract(body, "Message");
                log.error("阿里云短信发送失败：code={} message={} template={} phone={}",
                        code2, message, templateCode, mask(target));
                throw new IllegalStateException("阿里云短信发送失败：" + code2 + " " + message);
            }
            log.info("验证码短信已发送：to={} scene={} template={} requestId={}",
                    mask(target), scene, templateCode, extract(body, "RequestId"));
        } catch (IOException e) {
            log.error("阿里云短信网络异常：to={} cause={}", mask(target), e.getMessage());
            throw new IllegalStateException("阿里云短信网络异常", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("阿里云短信发送被中断", e);
        }
    }

    String templateFor(String scene) {
        if (SysAuthCode.SCENE_REGISTER.equals(scene) && notBlank(cfg.getTemplateCodeRegister())) {
            return cfg.getTemplateCodeRegister();
        }
        return cfg.getTemplateCodeLogin();
    }

    /**
     * 构造带签名的 GET 请求 URL。包可见，便于单元测试直接校验签名串。
     */
    String buildSignedUrl(Map<String, String> bizParams) {
        Map<String, String> all = new TreeMap<>(bizParams);
        all.put("Action", ACTION);
        all.put("Version", API_VERSION);
        all.put("Format", "JSON");
        all.put("SignatureMethod", SIGNATURE_METHOD);
        all.put("SignatureVersion", SIGNATURE_VERSION);
        all.put("SignatureNonce", UUID.randomUUID().toString());
        all.put("Timestamp", TIMESTAMP_FMT.format(Instant.now()));
        all.put("AccessKeyId", cfg.getAccessKeyId());
        // RegionId 对国内短信非必需，但显式带上可避免部分地域要求
        all.put("RegionId", "cn-hangzhou");

        String canonical = all.entrySet().stream()
                .map(e -> percentEncode(e.getKey()) + "=" + percentEncode(e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        String stringToSign = "GET&" + percentEncode("/") + "&" + percentEncode(canonical);
        String signature = hmacSha1Base64(cfg.getAccessKeySecret() + "&", stringToSign);

        return "https://" + cfg.getEndpoint() + "/?Signature=" + percentEncode(signature) + "&" + canonical;
    }

    /** 阿里云要求的 RFC3986 编码：空格用 %20、~ 不编码、其余按 UTF-8 百分号编码。 */
    static String percentEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    static String hmacSha1Base64(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算阿里云签名失败", e);
        }
    }

    /** 极简 JSON 取值：阿里云响应结构固定，不值得为它引入解析分支。 */
    private static String extract(String json, String field) {
        String key = "\"" + field + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int start = i + key.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    private static String truncate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "-";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
