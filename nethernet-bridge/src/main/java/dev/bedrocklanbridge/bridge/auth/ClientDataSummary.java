package dev.bedrocklanbridge.bridge.auth;

import org.jose4j.json.JsonUtil;
import org.jose4j.lang.JoseException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Safe subset of decoded Bedrock clientJwt claims after ECDSA signature verification.
 */
public record ClientDataSummary(
        String gameVersion,
        String deviceModel,
        int deviceOs,
        String languageCode,
        String thirdPartyName,
        String selfSignedId
) {
    public static ClientDataSummary parse(byte[] verifiedPayloadBytes) throws JoseException {
        String json = new String(verifiedPayloadBytes, StandardCharsets.UTF_8);
        Map<String, Object> claims = JsonUtil.parseJson(json);
        return new ClientDataSummary(
                str(claims, "GameVersion", "unknown"),
                str(claims, "DeviceModel", "unknown"),
                num(claims, "DeviceOS", -1),
                str(claims, "LanguageCode", "unknown"),
                str(claims, "ThirdPartyName", ""),
                str(claims, "SelfSignedId", "")
        );
    }

    private static String str(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String s ? s : fallback;
    }

    private static int num(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }
}
