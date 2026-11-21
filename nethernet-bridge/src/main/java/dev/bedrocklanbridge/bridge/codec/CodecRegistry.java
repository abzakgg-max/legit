package dev.bedrocklanbridge.bridge.codec;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Version-agnostic registry of supported Bedrock codecs.
 */
public final class CodecRegistry {

    private static final Map<Integer, BedrockCodec> CODECS;
    private static final BedrockCodec LATEST;

    static {
        Map<Integer, BedrockCodec> map = new LinkedHashMap<>();
        register(map, Bedrock_v2168_hotfix4.CODEC);
        register(map, Bedrock_v2169.CODEC);
        register(map, Bedrock_v2193.CODEC);
        register(map, Bedrock_v2225.CODEC);
        CODECS = Collections.unmodifiableMap(map);
        LATEST = Bedrock_v2225.CODEC;
    }

    private CodecRegistry() {
    }

    private static void register(Map<Integer, BedrockCodec> map, BedrockCodec codec) {
        map.put(codec.getProtocolVersion(), codec);
    }

    public static BedrockCodec latest() {
        return LATEST;
    }

    public static BedrockCodec resolve(Integer requestedProtocol) {
        if (requestedProtocol == null) {
            return LATEST;
        }
        BedrockCodec codec = CODECS.get(requestedProtocol);
        if (codec == null) {
            throw new IllegalArgumentException(
                    "Unsupported Bedrock protocol " + requestedProtocol + ". Available: " + CODECS.keySet());
        }
        return codec;
    }

    public static Optional<BedrockCodec> find(int protocolVersion) {
        return Optional.ofNullable(CODECS.get(protocolVersion));
    }

    public static int latestProtocol() {
        return LATEST.getProtocolVersion();
    }

    public static Map<Integer, BedrockCodec> all() {
        return CODECS;
    }
}
