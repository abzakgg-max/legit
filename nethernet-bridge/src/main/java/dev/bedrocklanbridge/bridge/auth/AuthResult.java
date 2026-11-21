package dev.bedrocklanbridge.bridge.auth;

import org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;

import java.security.PublicKey;
import java.util.UUID;

/**
 * Result of verifying LoginPacket authentication payload, clientJwt signature,
 * and NetherNet TransportIdentityBinding.
 */
public record AuthResult(
        AuthType authType,
        boolean chainSigned,
        String displayName,
        UUID identity,
        String xuid,
        String minecraftId,
        PublicKey identityPublicKey,
        TransportIdentityBinding.State transportBindingState,
        String transportBindingDetail,
        ClientDataSummary clientData
) {
    public String formatSummary() {
        return "authType=" + authType
                + ", signed=" + chainSigned
                + ", displayName=\"" + displayName + "\""
                + ", xuid=\"" + xuid + "\""
                + ", minecraftId=\"" + minecraftId + "\""
                + ", identity=" + identity
                + ", transportBinding=" + transportBindingState + " (" + transportBindingDetail + ")"
                + ", gameVersion=" + clientData.gameVersion()
                + ", deviceOS=" + clientData.deviceOs()
                + ", deviceModel=\"" + clientData.deviceModel() + "\"";
    }
}
