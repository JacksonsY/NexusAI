package com.nexusai.provider.bedrock;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AWS Signature Version 4 签名器（bedrock-runtime 等用·HMAC-SHA256 规范请求）。
 * 仅签 host + x-amz-date 两头（POST·空 query·payload 全哈希）；返回需附加的 Authorization/X-Amz-Date。
 * 规范见 AWS SigV4 文档；canonicalUri 按 RFC3986 逐段编码（modelId 含 ':' 需编码）。
 *
 * @author nexusai
 */
public final class AwsV4Signer {

    private AwsV4Signer() {
    }

    private static final DateTimeFormatter AMZ =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    public static Map<String, String> sign(String accessKey, String secretKey, String region, String service,
                                           String host, String canonicalUri, byte[] body) {
        Instant now = Instant.now();
        String amzDate = AMZ.format(now);
        String dateStamp = STAMP.format(now);

        String payloadHash = hex(sha256(body == null ? new byte[0] : body));
        String canonicalHeaders = "host:" + host + "\n" + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-date";
        String canonicalRequest = "POST\n"
                + encodePath(canonicalUri) + "\n"
                + "" + "\n"                       // 空 canonical query string
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String scope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + scope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = hex(hmac(kSigning, stringToSign));

        String auth = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Amz-Date", amzDate);
        h.put("Authorization", auth);
        return h;
    }

    /** RFC3986 逐段编码 path（不编码 '/'；unreserved A-Za-z0-9-_.~ 保留）。 */
    static String encodePath(String path) {
        String[] segs = path.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(encodeSegment(segs[i]));
        }
        return sb.toString();
    }

    private static String encodeSegment(String s) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }
}
