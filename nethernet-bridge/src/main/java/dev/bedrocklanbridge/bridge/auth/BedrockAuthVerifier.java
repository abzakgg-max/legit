package dev.bedrocklanbridge.bridge.auth;

import io.netty.channel.Channel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;

import java.security.PublicKey;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Verifies Bedrock LoginPacket payloads using CloudburstMC EncryptionUtils and
 * NetherNet TransportIdentityBinding, requiring a valid numeric XUID under STRICT_MICROSOFT.
 */
public final class BedrockAuthVerifier {

    private static final Pattern NUMERIC_XUID = Pattern.compile("^[0-9]{1,32}$");

    private BedrockAuthVerifier() {
    }

    public static AuthResult verify(
            LoginPacket loginPacket,
            Channel channel,
            AuthPolicy policy,
            boolean requireTransportBinding
    ) {
        Objects.requireNonNull(loginPacket, "loginPacket");
        Objects.requireNonNull(policy, "policy");

        AuthPayload authPayload = loginPacket.getAuthPayload();
        if (authPayload == null) {
            throw new SecurityException("LoginPacket missing AuthPayload");
        }
        if (authPayload.getAuthType() == AuthType.GUEST) {
            throw new SecurityException("AuthType.GUEST (split-screen) is rejected by policy");
        }

        ChainValidationResult validation;
        try {
            validation = EncryptionUtils.validatePayload(authPayload);
        } catch (Exception e) {
            throw new SecurityException("AuthPayload validation failed: " + e.getMessage(), e);
        }

        boolean signed = validation.signed();
        if (policy.requireMicrosoftAuth()) {
            if (authPayload.getAuthType() != AuthType.FULL || !signed) {
                throw new SecurityException("STRICT_MICROSOFT policy requires signed AuthType.FULL, got authType="
                        + authPayload.getAuthType() + ", signed=" + signed);
            }
        }

        ChainValidationResult.IdentityClaims claims = validation.identityClaims();
        if (claims == null || claims.extraData == null) {
            throw new SecurityException("Login chain missing identity claims / extraData");
        }

        String trimmedXuid = claims.extraData.xuid == null ? "" : claims.extraData.xuid.trim();
        if (policy.requireMicrosoftAuth() && !NUMERIC_XUID.matcher(trimmedXuid).matches()) {
            throw new SecurityException("STRICT_MICROSOFT policy requires valid numeric XUID, got=\""
                    + claims.extraData.xuid + "\"");
        }

        PublicKey identityPublicKey;
        try {
            identityPublicKey = claims.parsedIdentityPublicKey();
        } catch (Exception e) {
            throw new SecurityException("Invalid identityPublicKey in auth claims: " + e.getMessage(), e);
        }

        byte[] verifiedClientData;
        try {
            verifiedClientData = EncryptionUtils.verifyClientData(loginPacket.getClientJwt(), identityPublicKey);
        } catch (Exception e) {
            throw new SecurityException("Failed verifying clientJwt signature: " + e.getMessage(), e);
        }
        if (verifiedClientData == null) {
            throw new SecurityException("clientJwt signature does not match chain identityPublicKey");
        }

        ClientDataSummary clientData;
        try {
            clientData = ClientDataSummary.parse(verifiedClientData);
        } catch (Exception e) {
            throw new SecurityException("Malformed verified clientJwt JSON: " + e.getMessage(), e);
        }

        TransportIdentityBinding.State bindingState = TransportIdentityBinding.State.NONE;
        String bindingDetail = "non-NetherNet channel";
        if (channel instanceof NetherNetChildChannel) {
            bindingState = TransportIdentityBinding.state(channel);
            if (bindingState == TransportIdentityBinding.State.NONE) {
                bindingDetail = "no SDP a=identity assertion was validated by signaling";
                if (requireTransportBinding) {
                    throw new SecurityException("TransportIdentityBinding is NONE; rejecting under strict binding");
                }
            } else {
                String mismatch = TransportIdentityBinding.mismatch(channel, identityPublicKey);
                if (mismatch != null) {
                    throw new SecurityException("TransportIdentityBinding mismatch: " + mismatch);
                }
                bindingState = TransportIdentityBinding.state(channel);
                bindingDetail = "LoginPacket identityPublicKey matches SDP cpk";
            }
        }

        return new AuthResult(
                authPayload.getAuthType(),
                signed,
                claims.extraData.displayName,
                claims.extraData.identity,
                trimmedXuid,
                claims.extraData.minecraftId,
                identityPublicKey,
                bindingState,
                bindingDetail,
                clientData
        );
    }
}
