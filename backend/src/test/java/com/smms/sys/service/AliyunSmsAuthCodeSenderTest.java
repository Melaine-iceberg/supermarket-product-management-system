package com.smms.sys.service;

import com.smms.sys.config.CodeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阿里云短信签名算法单元测试（V1.3）。
 *
 * <p>签名算错的表现是线上收到 {@code SignatureDoesNotMatch}，排查成本高，
 * 因此在本地把规范里的关键约束逐条钉住：RFC3986 编码规则、参数排序、
 * StringToSign 的拼接形式、Base64(HMAC-SHA1(secret + "&", ...)) 的密钥形式。</p>
 */
class AliyunSmsAuthCodeSenderTest {

    private static CodeProperties.Aliyun cfg() {
        CodeProperties.Aliyun a = new CodeProperties.Aliyun();
        a.setAccessKeyId("testAccessKeyId");
        a.setAccessKeySecret("testAccessKeySecret");
        a.setSignName("好又多超市");
        a.setTemplateCodeLogin("SMS_123456789");
        a.setEndpoint("dysmsapi.aliyuncs.com");
        return a;
    }

    private static AliyunSmsAuthCodeSender sender() {
        CodeProperties props = new CodeProperties();
        props.getSms().setAliyun(cfg());
        return new AliyunSmsAuthCodeSender(props);
    }

    /** 从生成的 URL 里取出查询参数（URL 解码后） */
    private static Map<String, String> queryOf(String url) throws UnsupportedEncodingException {
        Map<String, String> out = new TreeMap<>();
        String q = url.substring(url.indexOf('?') + 1);
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, i), "UTF-8"),
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8"));
        }
        return out;
    }

    @Test
    @DisplayName("percentEncode 符合 RFC3986：空格为 %20、~ 不编码、* 编码为 %2A")
    void percentEncodeFollowsRfc3986() {
        assertThat(AliyunSmsAuthCodeSender.percentEncode("a b")).isEqualTo("a%20b");
        assertThat(AliyunSmsAuthCodeSender.percentEncode("~")).isEqualTo("~");
        assertThat(AliyunSmsAuthCodeSender.percentEncode("*")).isEqualTo("%2A");
        assertThat(AliyunSmsAuthCodeSender.percentEncode("a+b")).isEqualTo("a%2Bb");
        // 中文按 UTF-8 编码：好又多超市
        assertThat(AliyunSmsAuthCodeSender.percentEncode("好又多超市"))
                .isEqualTo("%E5%A5%BD%E5%8F%88%E5%A4%9A%E8%B6%85%E5%B8%82");
    }

    @Test
    @DisplayName("生成的 URL 含全部公共参数与业务参数")
    void urlContainsRequiredParams() throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("PhoneNumbers", "13800000001");
        params.put("SignName", "好又多超市");
        params.put("TemplateCode", "SMS_123456789");
        params.put("TemplateParam", "{\"code\":\"123456\"}");

        String url = sender().buildSignedUrl(params);
        Map<String, String> q = queryOf(url);

        assertThat(q).containsEntry("Action", "SendSms");
        assertThat(q).containsEntry("Version", "2017-05-25");
        assertThat(q).containsEntry("Format", "JSON");
        assertThat(q).containsEntry("SignatureMethod", "HMAC-SHA1");
        assertThat(q).containsEntry("SignatureVersion", "1.0");
        assertThat(q).containsEntry("AccessKeyId", "testAccessKeyId");
        assertThat(q).containsEntry("PhoneNumbers", "13800000001");
        assertThat(q).containsEntry("SignName", "好又多超市");
        assertThat(q).containsEntry("TemplateCode", "SMS_123456789");
        assertThat(q).containsEntry("TemplateParam", "{\"code\":\"123456\"}");
        assertThat(q.get("SignatureNonce")).isNotBlank();
        assertThat(q.get("Timestamp")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(q.get("Signature")).isNotBlank();
        // 使用 https，且默认走国内短信端点
        assertThat(url).startsWith("https://dysmsapi.aliyuncs.com/?Signature=");
    }

    @Test
    @DisplayName("签名是密钥 secret+\"&\" 的 Base64(HMAC-SHA1)，可被独立复算验证")
    void signatureIsReproducible() throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("PhoneNumbers", "13800000001");
        params.put("SignName", "好又多超市");
        params.put("TemplateCode", "SMS_123456789");
        params.put("TemplateParam", "{\"code\":\"123456\"}");

        String url = sender().buildSignedUrl(params);
        Map<String, String> q = queryOf(url);

        // 按规范独立复算：把除 Signature 外的所有参数排序拼接 → StringToSign → HMAC-SHA1
        Map<String, String> toSignMap = new TreeMap<>(q);
        toSignMap.remove("Signature");
        String canonical = toSignMap.entrySet().stream()
                .map(e -> AliyunSmsAuthCodeSender.percentEncode(e.getKey())
                        + "=" + AliyunSmsAuthCodeSender.percentEncode(e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        String stringToSign = "GET&" + AliyunSmsAuthCodeSender.percentEncode("/") + "&"
                + AliyunSmsAuthCodeSender.percentEncode(canonical);
        String expected = AliyunSmsAuthCodeSender.hmacSha1Base64("testAccessKeySecret&", stringToSign);

        assertThat(q.get("Signature")).isEqualTo(expected);
    }

    @Test
    @DisplayName("固定输入下的签名等于已知向量（由 Python hmac 独立复算，交叉校验实现）")
    void signatureMatchesKnownVector() {
        // 向量来源：python3 -c "import hmac,hashlib,base64;
        //   print(base64.b64encode(hmac.new(b'secret&', b'GET&%2F&a%3D1', hashlib.sha1).digest()).decode())"
        assertThat(AliyunSmsAuthCodeSender.hmacSha1Base64("secret&", "GET&%2F&a%3D1"))
                .isEqualTo("eLKLeSRSuJUzQsBT2tikxeY93AU=");
        assertThat(AliyunSmsAuthCodeSender.hmacSha1Base64("testAccessKeySecret&", "GET&%2F&"))
                .isEqualTo("X4w6gsBnGFJqiCXfhRNoOkkVdl8=");
        // 不同密钥必须得到不同签名
        assertThat(AliyunSmsAuthCodeSender.hmacSha1Base64("other&", "GET&%2F&a%3D1"))
                .isNotEqualTo("eLKLeSRSuJUzQsBT2tikxeY93AU=");
    }

    @Test
    @DisplayName("supports/available：仅短信渠道，且凭证齐备才算可用")
    void availabilityReflectsConfiguration() {
        AliyunSmsAuthCodeSender ok = sender();
        assertThat(ok.supports("SMS")).isTrue();
        assertThat(ok.supports("EMAIL")).isFalse();
        assertThat(ok.available("SMS")).isTrue();
        assertThat(ok.available("EMAIL")).isFalse();

        // 缺签名 → 不可用（路由会回退到日志模式，而不是启动失败）
        CodeProperties props = new CodeProperties();
        props.getSms().setAliyun(cfg());
        props.getSms().getAliyun().setSignName("");
        assertThat(new AliyunSmsAuthCodeSender(props).available("SMS")).isFalse();

        // 缺模板 → 不可用
        CodeProperties props2 = new CodeProperties();
        props2.getSms().setAliyun(cfg());
        props2.getSms().getAliyun().setTemplateCodeLogin("");
        assertThat(new AliyunSmsAuthCodeSender(props2).available("SMS")).isFalse();
    }

    @Test
    @DisplayName("注册场景使用独立模板；未配置注册模板时回退到登录模板")
    void registerTemplateFallsBackToLoginTemplate() {
        AliyunSmsAuthCodeSender s = sender();
        assertThat(s.providerName()).isEqualTo("aliyun");
        // 未配注册模板 → 注册场景回退登录模板
        assertThat(s.templateFor("REGISTER")).isEqualTo("SMS_123456789");
        assertThat(s.templateFor("LOGIN")).isEqualTo("SMS_123456789");

        // 配了注册模板 → 注册场景用它，登录不受影响
        CodeProperties props = new CodeProperties();
        props.getSms().setAliyun(cfg());
        props.getSms().getAliyun().setTemplateCodeRegister("SMS_987654321");
        AliyunSmsAuthCodeSender s2 = new AliyunSmsAuthCodeSender(props);
        assertThat(s2.templateFor("REGISTER")).isEqualTo("SMS_987654321");
        assertThat(s2.templateFor("LOGIN")).isEqualTo("SMS_123456789");
    }
}
