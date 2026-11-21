package dev.bedrocklanbridge.bridge.auth;

import com.mojang.authlib.GameProfile;

import java.util.UUID;

/**
 * Resolved Bedrock-to-Java identity binding.
 *
 * @param xuid              Canonical numeric Xbox XUID (primary identity key).
 * @param bedrockUuid       Deterministic UUID derived strictly from XUID.
 * @param javaProfile       Java {@link GameProfile} using {@code bedrockUuid} and a safe 3..16 {@code [a-zA-Z0-9_]} name.
 * @param originalGamertag Original unmodified Xbox Gamertag from the verified Microsoft chain.
 * @param sanitized         True if the Java name differs from {@code originalGamertag}.
 * @param collisionResolved True if collision escalation was required to allocate a unique Java name.
 */
public record MappedBedrockProfile(
        String xuid,
        UUID bedrockUuid,
        GameProfile javaProfile,
        String originalGamertag,
        boolean sanitized,
        boolean collisionResolved
) {
}
