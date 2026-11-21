package dev.bedrocklanbridge.bridge.session;

import dev.bedrocklanbridge.bridge.auth.AuthPolicy;
import dev.bedrocklanbridge.bridge.auth.AuthResult;
import dev.bedrocklanbridge.bridge.auth.BedrockAuthVerifier;
import dev.bedrocklanbridge.bridge.codec.CodecRegistry;
import io.netty.channel.Channel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannel;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.compat.BedrockCompat;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PacketViolationWarningPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bedrock login & session establishment handler enforcing strict stage transitions,
 * AuthPolicy verification, TransportIdentityBinding, and WebRTC DTLS awareness.
 */
public class BridgeLoginPacketHandler implements BedrockPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BridgeLoginPacketHandler.class);

    public interface SessionObserver {
        default void onStageChanged(LoginStage newStage) {
        }

        default void onAuthenticated(AuthResult result) {
        }

        default void onSessionEstablished(AuthResult result, boolean resourcePacksConfirmed) {
        }

        default void onFailure(String reason, Throwable error) {
        }
    }

    private final BedrockServerSession session;
    private final AuthPolicy authPolicy;
    private final boolean requireTransportBinding;
    private final boolean sendResourcePacksHandshake;
    private final boolean disconnectAfterHandshake;
    private final SessionObserver observer;

    private LoginStage stage = LoginStage.NETWORK_SETTINGS;
    private int negotiatedProtocol;
    private AuthResult authResult;

    public BridgeLoginPacketHandler(
            BedrockServerSession session,
            AuthPolicy authPolicy,
            boolean requireTransportBinding,
            boolean sendResourcePacksHandshake,
            boolean disconnectAfterHandshake,
            SessionObserver observer
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.authPolicy = Objects.requireNonNull(authPolicy, "authPolicy");
        this.requireTransportBinding = requireTransportBinding;
        this.sendResourcePacksHandshake = sendResourcePacksHandshake;
        this.disconnectAfterHandshake = disconnectAfterHandshake;
        this.observer = observer != null ? observer : new SessionObserver() {};
    }

    public LoginStage getStage() {
        return stage;
    }

    public AuthResult getAuthResult() {
        return authResult;
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        if (!session.isConnected()) {
            return PacketSignal.HANDLED;
        }
        if (!isPacketAllowedInStage(packet)) {
            failAndDisconnect("Unexpected packet " + packet.getClass().getSimpleName() + " in stage " + stage, null);
            return PacketSignal.HANDLED;
        }
        return BedrockPacketHandler.super.handlePacket(packet);
    }

    private boolean isPacketAllowedInStage(BedrockPacket packet) {
        return switch (stage) {
            case NETWORK_SETTINGS -> packet instanceof RequestNetworkSettingsPacket;
            case LOGIN -> packet instanceof LoginPacket
                    || packet instanceof PacketViolationWarningPacket;
            case HANDSHAKE -> packet instanceof ClientToServerHandshakePacket
                    || packet instanceof PacketViolationWarningPacket;
            case RESOURCE_PACKS -> packet instanceof ResourcePackClientResponsePacket
                    || packet instanceof ClientCacheStatusPacket
                    || packet instanceof PacketViolationWarningPacket;
            case LOGIN_SUCCESS, COMPLETED -> true;
            case FAILED_OR_DISCONNECTED -> false;
        };
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        int clientProtocol = packet.getProtocolVersion();
        LOGGER.debug("[NetherNetBridge] [{}] [{}] <<< RequestNetworkSettings protocol={}",
                session.getSocketAddress(), stage, clientProtocol);

        Optional<BedrockCodec> codecOpt = CodecRegistry.find(clientProtocol);
        if (codecOpt.isEmpty()) {
            session.setCodec(BedrockCompat.disconnectCompat(clientProtocol));
            PlayStatusPacket status = new PlayStatusPacket();
            status.setStatus(clientProtocol < CodecRegistry.latestProtocol()
                    ? PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD
                    : PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD);
            session.sendPacketImmediately(status);
            failAndDisconnect("Unsupported Bedrock protocol " + clientProtocol
                    + " (supported=" + CodecRegistry.all().keySet() + ")", null);
            return PacketSignal.HANDLED;
        }

        BedrockCodec codec = codecOpt.get();
        this.negotiatedProtocol = clientProtocol;
        session.setCodec(codec);

        NetworkSettingsPacket response = new NetworkSettingsPacket();
        response.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);
        response.setCompressionThreshold(512);
        response.setClientThrottleEnabled(false);
        response.setClientThrottleThreshold(0);
        response.setClientThrottleScalar(0f);

        session.sendPacketImmediately(response);
        session.setCompression(PacketCompressionAlgorithm.ZLIB);

        transition(LoginStage.LOGIN);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        if (packet.getProtocolVersion() != negotiatedProtocol) {
            failAndDisconnect("Protocol mismatch between RequestNetworkSettings (" + negotiatedProtocol
                    + ") and LoginPacket (" + packet.getProtocolVersion() + ")", null);
            return PacketSignal.HANDLED;
        }

        AuthResult verified;
        try {
            Channel channel = session.getPeer().getChannel();
            verified = BedrockAuthVerifier.verify(packet, channel, authPolicy, requireTransportBinding);
        } catch (SecurityException ex) {
            failAndDisconnect(ex.getMessage(), ex);
            return PacketSignal.HANDLED;
        }

        this.authResult = verified;
        observer.onAuthenticated(verified);
        LOGGER.info("[NetherNetBridge] Authenticated Bedrock client: {}", verified.formatSummary());

        Channel channel = session.getPeer().getChannel();
        if (channel instanceof NetherNetChannel) {
            completeLoginSuccess();
            return PacketSignal.HANDLED;
        }

        try {
            KeyPair serverKeyPair = EncryptionUtils.createKeyPair();
            byte[] token = EncryptionUtils.generateRandomToken();
            ServerToClientHandshakePacket handshake = new ServerToClientHandshakePacket();
            handshake.setJwt(EncryptionUtils.createHandshakeJwt(serverKeyPair, token));
            session.sendPacketImmediately(handshake);
            SecretKey secretKey = EncryptionUtils.getSecretKey(
                    serverKeyPair.getPrivate(), verified.identityPublicKey(), token);
            session.enableEncryption(secretKey);
            transition(LoginStage.HANDSHAKE);
        } catch (Exception e) {
            failAndDisconnect("Failed starting encryption handshake: " + e.getMessage(), e);
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ClientToServerHandshakePacket packet) {
        completeLoginSuccess();
        return PacketSignal.HANDLED;
    }

    private void completeLoginSuccess() {
        PlayStatusPacket playStatus = new PlayStatusPacket();
        playStatus.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
        session.sendPacketImmediately(playStatus);
        transition(LoginStage.LOGIN_SUCCESS);

        if (!sendResourcePacksHandshake) {
            transition(LoginStage.COMPLETED);
            observer.onSessionEstablished(authResult, false);
            return;
        }

        ResourcePacksInfoPacket info = new ResourcePacksInfoPacket();
        info.setForcedToAccept(false);
        info.setHasAddonPacks(false);
        info.setScriptingEnabled(false);
        info.setWorldTemplateId(new UUID(0L, 0L));
        info.setWorldTemplateVersion("");
        info.setVibrantVisualsForceDisabled(false);
        session.sendPacketImmediately(info);
        transition(LoginStage.RESOURCE_PACKS);
    }

    @Override
    public PacketSignal handle(ClientCacheStatusPacket packet) {
        LOGGER.debug("[NetherNetBridge] ClientCacheStatus supported={}", packet.isSupported());
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(PacketViolationWarningPacket packet) {
        LOGGER.warn("[NetherNetBridge] PacketViolationWarning type={} severity={} causeId={} context={}",
                packet.getType(), packet.getSeverity(), packet.getPacketCauseId(), packet.getContext());
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePackClientResponsePacket packet) {
        switch (packet.getStatus()) {
            case HAVE_ALL_PACKS -> {
                ResourcePackStackPacket stack = new ResourcePackStackPacket();
                stack.setForcedToAccept(false);
                stack.setGameVersion("*");
                stack.setExperimentsPreviouslyToggled(false);
                stack.setHasEditorPacks(false);
                session.sendPacketImmediately(stack);
            }
            case COMPLETED -> {
                transition(LoginStage.COMPLETED);
                observer.onSessionEstablished(authResult, true);
                if (disconnectAfterHandshake) {
                    session.disconnect("NetherNet Bridge Stage-2 Verification Complete");
                }
            }
            case REFUSED -> failAndDisconnect("Client refused resource packs", null);
            default -> LOGGER.debug("[NetherNetBridge] Ignoring ResourcePack status {}", packet.getStatus());
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        LOGGER.info("[NetherNetBridge] Session {} disconnected ({}): {}",
                session.getSocketAddress(), stage, reason);
        if (stage != LoginStage.COMPLETED) {
            transition(LoginStage.FAILED_OR_DISCONNECTED);
        }
    }

    private void transition(LoginStage next) {
        this.stage = next;
        observer.onStageChanged(next);
    }

    private void failAndDisconnect(String reason, Throwable error) {
        transition(LoginStage.FAILED_OR_DISCONNECTED);
        LOGGER.warn("[NetherNetBridge] Session {} rejected: {}", session.getSocketAddress(), reason, error);
        observer.onFailure(reason, error);
        if (session.isConnected()) {
            session.disconnect("NetherNet Auth/Session rejected: " + reason);
        }
    }
}
