package dev.bedrocklanbridge.bridge.session;

/**
 * Strict state machine stages for Bedrock login and session establishment.
 */
public enum LoginStage {
    NETWORK_SETTINGS,
    LOGIN,
    HANDSHAKE,
    LOGIN_SUCCESS,
    RESOURCE_PACKS,
    COMPLETED,
    FAILED_OR_DISCONNECTED
}
