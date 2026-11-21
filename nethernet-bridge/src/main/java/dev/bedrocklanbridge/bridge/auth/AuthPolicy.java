package dev.bedrocklanbridge.bridge.auth;

/**
 * Explicit authentication & transport-binding policy for Bedrock sessions.
 * Never silently weakens verification: STRICT_MICROSOFT is the production default.
 */
public enum AuthPolicy {
    STRICT_MICROSOFT(true, true),
    ALLOW_SELF_SIGNED_TEST(false, false);

    private final boolean requireMicrosoftAuth;
    private final boolean requireTransportBinding;

    AuthPolicy(boolean requireMicrosoftAuth, boolean requireTransportBinding) {
        this.requireMicrosoftAuth = requireMicrosoftAuth;
        this.requireTransportBinding = requireTransportBinding;
    }

    public boolean requireMicrosoftAuth() {
        return requireMicrosoftAuth;
    }

    public boolean requireTransportBinding() {
        return requireTransportBinding;
    }
}
