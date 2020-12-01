package cn.felord.payment.wechat.v3;


import cn.felord.payment.wechat.enumeration.WeChatServer;
import cn.felord.payment.wechat.enumeration.WechatPayV3Type;
import cn.felord.payment.wechat.v3.model.ResponseSignVerifyParams;
import cn.felord.payment.PayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.springframework.http.*;
import org.springframework.util.AlternativeJdkIdGenerator;
import org.springframework.util.Base64Utils;
import org.springframework.util.IdGenerator;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 签名 加签 验签
 * <p>
 * 我方请求微信服务器时，需要根据我方的API证书对参数进行加签；微信服务器会根据我方签名验签以确定请求来自我方服务器；
 * <p>
 * 然后微信服务器响应我方请求并在响应报文中使用【微信平台证书】加签 我方需要根据规则验签是否响应来自微信支付服务器
 * <p>
 * 其中【微信平台证书】定期会进行更新，不受我方管控，我方需要适当的时候获取最新的证书列表。
 *
 * @author Dax
 * @since 16 :48
 */
public class SignatureProvider {

    private static final IdGenerator ID_GENERATOR = new AlternativeJdkIdGenerator();
    private static final String SCHEMA = "WECHATPAY2-SHA256-RSA2048 ";
    private final RestOperations restOperations = new RestTemplate();
    /**
     * The constant TOKEN_PATTERN.
     */
    public static final String TOKEN_PATTERN = "mchid=\"%s\",nonce_str=\"%s\",timestamp=\"%d\",serial_no=\"%s\",signature=\"%s\"";
    private final WechatMetaBean wechatMetaBean;
    /**
     * 微信平台证书容器  key = 序列号  value = 证书对象
     */
    private static final Map<String, Certificate> CERTIFICATE_MAP = new ConcurrentHashMap<>();

    /**
     * Instantiates a new Signature provider.
     *
     * @param wechatMetaBean the wechat meta bean
     */
    public SignatureProvider(WechatMetaBean wechatMetaBean) {
        this.wechatMetaBean = wechatMetaBean;
    }


    /**
     * 我方请求时加签，使用API证书.
     *
     * @param method       the method
     * @param canonicalUrl the canonical url
     * @param body         the body
     * @return the string
     */
    @SneakyThrows
    public String requestSign(String method, String canonicalUrl, String body) {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(wechatMetaBean.getKeyPair().getPrivate());

        long timestamp = System.currentTimeMillis() / 1000;
        String nonceStr = ID_GENERATOR.generateId()
                .toString()
                .replaceAll("-", "");
        final String signatureStr = createSign(method, canonicalUrl, String.valueOf(timestamp), nonceStr, body);
        signer.update(signatureStr.getBytes(StandardCharsets.UTF_8));
        String encode = Base64Utils.encodeToString(signer.sign());

        // 序列号
        String serialNo = wechatMetaBean.getSerialNumber();
        // 生成token
        String token = String.format(TOKEN_PATTERN,
                wechatMetaBean.getWechatPayProperties().getV3().getMchId(),
                nonceStr, timestamp, serialNo, encode);
        return SCHEMA.concat(token);
    }

    /**
     * 我方对响应验签，和应答签名做比较，使用微信平台证书.
     *
     * @param params the params
     * @return the boolean
     */
    @SneakyThrows
    public boolean responseSignVerify(ResponseSignVerifyParams params) {

        String wechatpaySerial = params.getWechatpaySerial();
        if (CERTIFICATE_MAP.isEmpty() || !CERTIFICATE_MAP.containsKey(wechatpaySerial)) {
            refreshCertificate();
        }
        Certificate certificate = CERTIFICATE_MAP.get(wechatpaySerial);

        final String signatureStr = createSign(params.getWechatpayTimestamp(), params.getWechatpayNonce(), params.getBody());
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initVerify(certificate);
        signer.update(signatureStr.getBytes(StandardCharsets.UTF_8));

        return signer.verify(Base64Utils.decodeFromString(params.getWechatpaySignature()));
    }


    /**
     * 当我方服务器不存在平台证书或者证书同当前响应报文中的证书序列号不一致时应当刷新  调用/v3/certificates
     */
    @SneakyThrows
    private synchronized void refreshCertificate() {
        String url = WechatPayV3Type.CERT.uri(WeChatServer.CHINA);

        UriComponents uri = UriComponentsBuilder.fromHttpUrl(url).build();

        String canonicalUrl = uri.getPath();
        String encodedQuery = uri.getQuery();

        if (encodedQuery != null) {
            canonicalUrl += "?" + encodedQuery;
        }
        // 签名
        HttpMethod httpMethod = WechatPayV3Type.CERT.method();
        String authorization = requestSign(httpMethod.name(), canonicalUrl, "");

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Authorization", authorization);
        headers.add("User-Agent", "X-Pay-Service");
        RequestEntity<?> requestEntity = new RequestEntity<>(headers, httpMethod, uri.toUri());
        ResponseEntity<ObjectNode> responseEntity = restOperations.exchange(requestEntity, ObjectNode.class);
        ObjectNode bodyObjectNode = responseEntity.getBody();

        if (Objects.isNull(bodyObjectNode)) {
            throw new PayException("cant obtain the response body");
        }
        ArrayNode certificates = bodyObjectNode.withArray("data");
        if (certificates.isArray() && certificates.size() > 0) {
            CERTIFICATE_MAP.clear();
            final CertificateFactory cf = CertificateFactory.getInstance("X509");
            certificates.forEach(objectNode -> {
                JsonNode encryptCertificate = objectNode.get("encrypt_certificate");
                String associatedData = encryptCertificate.get("associated_data").asText();
                String nonce = encryptCertificate.get("nonce").asText();
                String ciphertext = encryptCertificate.get("ciphertext").asText();
                String publicKey = decryptResponseBody(associatedData, nonce, ciphertext);

                ByteArrayInputStream inputStream = new ByteArrayInputStream(publicKey.getBytes(StandardCharsets.UTF_8));
                Certificate certificate = null;
                try {
                    certificate = cf.generateCertificate(inputStream);
                } catch (CertificateException e) {
                    e.printStackTrace();
                }
                String responseSerialNo = objectNode.get("serial_no").asText();
                CERTIFICATE_MAP.put(responseSerialNo, certificate);
            });

        }

    }


    /**
     * 解密响应体.
     *
     * @param associatedData the associated data
     * @param nonce          the nonce
     * @param ciphertext     the ciphertext
     * @return the string
     */
    public String decryptResponseBody(String associatedData, String nonce, String ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            String apiV3Key = wechatMetaBean.getWechatPayProperties().getV3().getAppV3Secret();
            SecretKeySpec key = new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES");
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));

            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));

            byte[] bytes;
            try {
                bytes = cipher.doFinal(Base64Utils.decodeFromString(ciphertext));
            } catch (GeneralSecurityException e) {
                throw new PayException(e);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException |
                InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new PayException(e);
        }
    }

    /**
     * Gets wechat meta bean.
     *
     * @return the wechat meta bean
     */
    public WechatMetaBean getWechatMetaBean() {
        return wechatMetaBean;
    }

    /**
     * 请求时设置签名   组件
     *
     * @param components the components
     * @return string string
     */
    private String createSign(String... components) {
        return Arrays.stream(components)
                .collect(Collectors.joining("\n", "", "\n"));
    }


}