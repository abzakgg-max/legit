package dev.bedrocklanbridge.bridge.transport;

import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.ServerHandshakePacketListener;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;

/**
 * Serverbound Handshake listener for pre-authenticated virtual Bedrock connections.
 */
public final class VirtualHandshakePacketListener implements ServerHandshakePacketListener {

    private final MinecraftServer server;
    private final Connection connection;
    private final GameProfile preAuthenticatedProfile;

    public VirtualHandshakePacketListener(
            MinecraftServer server,
            Connection connection,
            GameProfile preAuthenticatedProfile
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.preAuthenticatedProfile = Objects.requireNonNull(preAuthenticatedProfile, "preAuthenticatedProfile");
    }

    @Override
    public void handleIntention(ClientIntentionPacket packet) {
        if (packet.intention() != ClientIntent.LOGIN) {
            connection.disconnect(Component.literal("Virtual Bedrock channel only supports LOGIN intent"));
            return;
        }
        connection.setupOutboundProtocol(LoginProtocols.CLIENTBOUND);
        int expectedProtocol = SharedConstants.getCurrentVersion().protocolVersion();
        if (packet.protocolVersion() != expectedProtocol) {
            Component reason = Component.literal(
                    "Protocol mismatch: expected " + expectedProtocol + " got " + packet.protocolVersion()
            );
            connection.send(new ClientboundLoginDisconnectPacket(reason));
            connection.disconnect(reason);
            return;
        }
        connection.setupInboundProtocol(
                LoginProtocols.SERVERBOUND,
                new VirtualLoginPacketListener(server, connection, preAuthenticatedProfile)
        );
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
    }

    @Override
    public boolean isAcceptingMessages() {
        return connection.isConnected();
    }
}
