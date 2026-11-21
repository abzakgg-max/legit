package dev.bedrocklanbridge.bridge.transport;

import com.mojang.authlib.GameProfile;
import dev.bedrocklanbridge.bridge.NetherNetBridgeMod;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.ServerLoginPacketListener;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.StringUtil;
import org.apache.commons.lang3.Validate;

import java.util.Objects;

/**
 * Pre-authenticated {@link ServerLoginPacketListener} for bridged virtual connections.
 */
public final class VirtualLoginPacketListener implements ServerLoginPacketListener, TickablePacketListener {

    private static final int MAX_TICKS_BEFORE_LOGIN = 600;

    public enum State {
        HELLO,
        VERIFYING,
        WAITING_FOR_DUPE_DISCONNECT,
        PROTOCOL_SWITCHING,
        ACCEPTED
    }

    private final MinecraftServer server;
    private final Connection connection;
    private final GameProfile preAuthenticatedProfile;
    private volatile State state = State.HELLO;
    private int ticks;

    public VirtualLoginPacketListener(
            MinecraftServer server,
            Connection connection,
            GameProfile preAuthenticatedProfile
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.preAuthenticatedProfile = Objects.requireNonNull(preAuthenticatedProfile, "preAuthenticatedProfile");
    }

    @Override
    public void handleHello(ServerboundHelloPacket packet) {
        Validate.validState(state == State.HELLO, "Unexpected hello packet");
        Validate.validState(
                StringUtil.isValidPlayerName(preAuthenticatedProfile.name()),
                "Invalid characters in virtual player name: %s",
                preAuthenticatedProfile.name()
        );
        NetherNetBridgeMod.LOGGER.info(
                "[NetherNetBridge] Virtual Login HELLO accepted for pre-authenticated profile {} ({}) without Yggdrasil",
                preAuthenticatedProfile.name(),
                preAuthenticatedProfile.id()
        );
        state = State.VERIFYING;
    }

    @Override
    public void tick() {
        if (state == State.VERIFYING) {
            verifyLoginAndFinishConnectionSetup();
        }
        if (state == State.WAITING_FOR_DUPE_DISCONNECT && !isPlayerAlreadyInWorld()) {
            finishLoginAndWaitForClient();
        }
        if (++ticks >= MAX_TICKS_BEFORE_LOGIN) {
            disconnect(Component.translatable("multiplayer.disconnect.slow_login"));
        }
    }

    private void verifyLoginAndFinishConnectionSetup() {
        PlayerList playerList = server.getPlayerList();
        Component denyReason = playerList.canPlayerLogin(
                connection.getRemoteAddress(),
                new NameAndId(preAuthenticatedProfile)
        );
        if (denyReason != null) {
            disconnect(denyReason);
            return;
        }

        int threshold = server.getCompressionThreshold();
        if (threshold >= 0 && !connection.isMemoryConnection()) {
            connection.send(
                    new ClientboundLoginCompressionPacket(threshold),
                    PacketSendListener.thenRun(() -> connection.setupCompression(threshold, true))
            );
        }

        boolean waitingForDisconnect = playerList.disconnectAllPlayersWithProfile(preAuthenticatedProfile.id());
        if (waitingForDisconnect) {
            state = State.WAITING_FOR_DUPE_DISCONNECT;
        } else {
            finishLoginAndWaitForClient();
        }
    }

    private boolean isPlayerAlreadyInWorld() {
        return server.getPlayerList().getPlayer(preAuthenticatedProfile.id()) != null;
    }

    private void finishLoginAndWaitForClient() {
        state = State.PROTOCOL_SWITCHING;
        connection.send(new ClientboundLoginFinishedPacket(
                preAuthenticatedProfile,
                server.getConnection().getSessionId()
        ));
    }

    @Override
    public void handleLoginAcknowledgement(ServerboundLoginAcknowledgedPacket packet) {
        Validate.validState(state == State.PROTOCOL_SWITCHING, "Unexpected login acknowledgement packet");
        connection.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(preAuthenticatedProfile, false);
        ServerConfigurationPacketListenerImpl configListener =
                new ServerConfigurationPacketListenerImpl(server, connection, cookie);
        connection.setupInboundProtocol(ConfigurationProtocols.SERVERBOUND, configListener);
        configListener.startConfiguration();
        state = State.ACCEPTED;
        NetherNetBridgeMod.LOGGER.info(
                "[NetherNetBridge] Virtual Login acknowledged -> entered Configuration for {} ({})",
                preAuthenticatedProfile.name(),
                preAuthenticatedProfile.id()
        );
    }

    @Override
    public void handleKey(ServerboundKeyPacket packet) {
        throw new IllegalStateException("Unexpected encryption key packet on pre-authenticated virtual channel");
    }

    @Override
    public void handleCustomQueryPacket(ServerboundCustomQueryAnswerPacket packet) {
        disconnect(Component.translatable("multiplayer.disconnect.unexpected_query_response"));
    }

    @Override
    public void handleCookieResponse(ServerboundCookieResponsePacket packet) {
        disconnect(Component.translatable("multiplayer.disconnect.unexpected_query_response"));
    }

    public void disconnect(Component reason) {
        try {
            NetherNetBridgeMod.LOGGER.info("[NetherNetBridge] Disconnecting virtual login {}: {}",
                    preAuthenticatedProfile.name(), reason.getString());
            connection.send(new ClientboundLoginDisconnectPacket(reason));
            connection.disconnect(reason);
        } catch (Exception e) {
            NetherNetBridgeMod.LOGGER.error("[NetherNetBridge] Error while disconnecting virtual login", e);
        }
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
        NetherNetBridgeMod.LOGGER.info("[NetherNetBridge] Virtual login {} disconnected: {}",
                preAuthenticatedProfile.name(), details.reason().getString());
    }

    @Override
    public boolean isAcceptingMessages() {
        return connection.isConnected();
    }
}
