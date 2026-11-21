package dev.bedrocklanbridge.bridge.session;

import com.mojang.authlib.GameProfile;
import dev.bedrocklanbridge.bridge.NetherNetBridgeMod;
import dev.bedrocklanbridge.bridge.transport.VirtualBedrockChannel;
import dev.bedrocklanbridge.bridge.transport.VirtualHandshakePacketListener;
import dev.bedrocklanbridge.bridge.transport.VirtualSocketAddress;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundAcceptCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.world.entity.PositionMoveRotation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Isolated virtual Bedrock-to-Java session backed by paired Netty {@link EmbeddedChannel}s.
 */
public final class VirtualClientSession {

    public enum Stage {
        CREATED,
        HANDSHAKE,
        LOGIN,
        CONFIGURATION,
        PLAY,
        DISCONNECTED
    }

    public enum FaultMode {
        MALFORMED_COMPRESSION_HEADER,
        INVALID_PACKET_ID_COMPRESSED
    }

    public record FaultInjectionResult(
            FaultMode mode,
            String caughtExceptionClass,
            String caughtExceptionMessage,
            boolean disconnected,
            String disconnectionDetails
    ) {
    }

    private static final Logger LOGGER = NetherNetBridgeMod.LOGGER;

    private final GameProfile profile;
    private final VirtualSocketAddress virtualAddress;
    private final Connection serverConnection;
    private final VirtualBedrockChannel serverChannel;
    private final Connection clientConnection;
    private final EmbeddedChannel clientChannel;
    private final RegistryAccess registryAccess;

    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.CREATED);
    private final AtomicReference<String> disconnectReason = new AtomicReference<>();
    private final AtomicInteger packetsToServer = new AtomicInteger();
    private final AtomicInteger packetsFromServer = new AtomicInteger();
    private final AtomicInteger compressionThreshold = new AtomicInteger(-1);
    private final AtomicInteger registryPacketsReceived = new AtomicInteger();
    private final AtomicInteger keepAlivesReceived = new AtomicInteger();
    private volatile String lastClientStep = "init";
    private volatile boolean sentPlayerLoaded;
    private volatile int assignedEntityId = -1;

    private VirtualClientSession(
            GameProfile profile,
            VirtualSocketAddress virtualAddress,
            Connection serverConnection,
            VirtualBedrockChannel serverChannel,
            Connection clientConnection,
            EmbeddedChannel clientChannel,
            RegistryAccess registryAccess
    ) {
        this.profile = profile;
        this.virtualAddress = virtualAddress;
        this.serverConnection = serverConnection;
        this.serverChannel = serverChannel;
        this.clientConnection = clientConnection;
        this.clientChannel = clientChannel;
        this.registryAccess = registryAccess;
    }

    public static VirtualClientSession createAndAttach(MinecraftServer server, String playerName, UUID uuid) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(uuid, "uuid");

        GameProfile profile = new GameProfile(uuid, playerName);
        VirtualSocketAddress address = new VirtualSocketAddress(playerName, uuid);

        Connection serverConn = new Connection(PacketFlow.SERVERBOUND);
        VirtualBedrockChannel serverChan = new VirtualBedrockChannel(address, new ChannelInitializer<io.netty.channel.Channel>() {
            @Override
            protected void initChannel(io.netty.channel.Channel ch) {
                ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast("timeout", new ReadTimeoutHandler(30));
                Connection.configureSerialization(pipeline, PacketFlow.SERVERBOUND, false, null);
                serverConn.configurePacketHandler(pipeline);
                serverConn.setListenerForServerboundHandshake(
                        new VirtualHandshakePacketListener(server, serverConn, profile)
                );
            }
        });

        Connection clientConn = new Connection(PacketFlow.CLIENTBOUND);
        EmbeddedChannel clientChan = new EmbeddedChannel(new ChannelInitializer<io.netty.channel.Channel>() {
            @Override
            protected void initChannel(io.netty.channel.Channel ch) {
                ChannelPipeline pipeline = ch.pipeline();
                Connection.configureSerialization(pipeline, PacketFlow.CLIENTBOUND, false, null);
                clientConn.configurePacketHandler(pipeline);
            }
        });

        VirtualClientSession session = new VirtualClientSession(
                profile,
                address,
                serverConn,
                serverChan,
                clientConn,
                clientChan,
                server.registryAccess()
        );

        server.execute(() -> {
            if (!serverConn.isConnected()) {
                return;
            }
            server.getConnection().getConnections().add(serverConn);
            LOGGER.info(
                    "[NetherNetBridge] Injected virtual Connection (address={}, isMemoryConnection={})",
                    serverConn.getRemoteAddress(),
                    serverConn.isMemoryConnection()
            );
            session.startHandshakeAndHello();
        });

        return session;
    }

    public void startHandshakeAndHello() {
        stage.set(Stage.HANDSHAKE);
        int protocolVersion = SharedConstants.getCurrentVersion().protocolVersion();
        ClientLoginPacketListener loginListener = createClientLoginListener();

        clientConnection.setupOutboundProtocol(HandshakeProtocols.SERVERBOUND);
        clientConnection.send(new ClientIntentionPacket(protocolVersion, "nethernet-bridge", 0, ClientIntent.LOGIN));

        clientConnection.setupInboundProtocol(LoginProtocols.CLIENTBOUND, loginListener);
        clientConnection.setupOutboundProtocol(LoginProtocols.SERVERBOUND);
        stage.set(Stage.LOGIN);
        clientConnection.send(new ServerboundHelloPacket(profile.name(), profile.id()));
        pumpOnce();
    }

    public void pumpOnce() {
        if (!serverChannel.isOpen() || !clientChannel.isOpen()) {
            if (stage.get() != Stage.DISCONNECTED) {
                stage.set(Stage.DISCONNECTED);
            }
            return;
        }

        for (int wave = 0; wave < 16; wave++) {
            boolean moved = false;

            clientChannel.flush();
            Object outToServer;
            while ((outToServer = clientChannel.readOutbound()) != null) {
                moved = true;
                packetsToServer.incrementAndGet();
                serverChannel.writeInbound(outToServer);
                serverChannel.runPendingTasks();
            }

            serverChannel.flush();
            Object outToClient;
            while ((outToClient = serverChannel.readOutbound()) != null) {
                moved = true;
                packetsFromServer.incrementAndGet();
                clientChannel.writeInbound(outToClient);
                clientChannel.runPendingTasks();
            }

            clientConnection.tick();
            if (!moved) {
                break;
            }
        }

        if (!serverChannel.isOpen() || !clientChannel.isOpen()) {
            stage.set(Stage.DISCONNECTED);
        }
    }

    public void injectMalformedFrameFault() {
        injectMalformedFrameFault(FaultMode.MALFORMED_COMPRESSION_HEADER);
    }

    public FaultInjectionResult injectMalformedFrameFault(FaultMode mode) {
        FaultInjectionResult result = injectFaultFrame(
                serverChannel,
                serverConnection,
                compressionThreshold.get(),
                mode
        );
        disconnectReason.compareAndSet(null, result.disconnectionDetails());
        pumpOnce();
        return result;
    }

    public static FaultInjectionResult injectFaultFrame(
            EmbeddedChannel serverChannel,
            Connection serverConnection,
            int activeCompressionThreshold,
            FaultMode mode
    ) {
        Objects.requireNonNull(serverChannel, "serverChannel");
        Objects.requireNonNull(serverConnection, "serverConnection");
        Objects.requireNonNull(mode, "mode");

        ByteBuf frame = Unpooled.buffer();
        switch (mode) {
            case MALFORMED_COMPRESSION_HEADER -> {
                frame.writeByte(0x06);
                frame.writeByte(0x7F);
                frame.writeBytes("FAULT".getBytes(StandardCharsets.US_ASCII));
            }
            case INVALID_PACKET_ID_COMPRESSED -> {
                if (activeCompressionThreshold >= 0) {
                    frame.writeByte(0x06);
                    frame.writeByte(0x00);
                    frame.writeByte(0x7F);
                    frame.writeBytes("FAUL".getBytes(StandardCharsets.US_ASCII));
                } else {
                    frame.writeByte(0x05);
                    frame.writeByte(0x7F);
                    frame.writeBytes("FAUL".getBytes(StandardCharsets.US_ASCII));
                }
            }
        }

        DecoderException caught = null;
        try {
            serverChannel.writeInbound(frame);
        } catch (DecoderException ex) {
            caught = ex;
        } finally {
            try {
                serverChannel.runPendingTasks();
                serverChannel.flush();
                if (serverConnection.isConnected() && serverConnection.getDisconnectionDetails() == null) {
                    serverConnection.tick();
                    serverChannel.runPendingTasks();
                    serverChannel.flush();
                }
            } catch (Exception ignored) {
            }
        }

        DisconnectionDetails details = serverConnection.getDisconnectionDetails();
        boolean disconnected = !serverConnection.isConnected() && !serverChannel.isOpen();

        if (caught != null && (!disconnected || details == null)) {
            throw new IllegalStateException(
                    "EmbeddedChannel surfaced DecoderException for " + mode
                            + " without disconnecting target session cleanly",
                    caught
            );
        }
        if (!disconnected || details == null) {
            throw new IllegalStateException("Fault mode " + mode + " failed to disconnect target virtual session cleanly");
        }

        return new FaultInjectionResult(
                mode,
                caught == null ? null : caught.getClass().getName(),
                caught == null ? null : caught.getMessage(),
                disconnected,
                details.reason().getString()
        );
    }

    public void close(String reason) {
        disconnectReason.compareAndSet(null, reason);
        stage.set(Stage.DISCONNECTED);
        try {
            if (clientChannel.isOpen()) {
                clientConnection.disconnect(Component.literal(reason));
                clientChannel.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (serverChannel.isOpen()) {
                serverConnection.disconnect(Component.literal(reason));
                serverChannel.close();
            }
        } catch (Exception ignored) {
        }
    }

    public GameProfile profile() {
        return profile;
    }

    public VirtualSocketAddress virtualAddress() {
        return virtualAddress;
    }

    public Connection serverConnection() {
        return serverConnection;
    }

    public Stage stage() {
        return stage.get();
    }

    public boolean isInPlay() {
        return stage.get() == Stage.PLAY && serverConnection.isConnected();
    }

    public int packetsToServer() {
        return packetsToServer.get();
    }

    public int packetsFromServer() {
        return packetsFromServer.get();
    }

    public int compressionThreshold() {
        return compressionThreshold.get();
    }

    public int assignedEntityId() {
        return assignedEntityId;
    }

    public String disconnectReason() {
        return disconnectReason.get();
    }

    public boolean isWaitingInPrepareSpawn() {
        Object listener = serverConnection.getPacketListener();
        if (!(listener instanceof ServerConfigurationPacketListenerImpl configListener)) {
            return false;
        }
        return readField(configListener, ServerConfigurationPacketListenerImpl.class, "currentTask") instanceof PrepareSpawnTask;
    }

    public boolean isWaitingInJoinWorld() {
        String taskId = currentConfigurationTaskId();
        return taskId != null && (taskId.equals("join_world") || taskId.endsWith(":join_world"));
    }

    public boolean hasActiveConfigurationTask() {
        return currentConfigurationTaskId() != null;
    }

    public String currentConfigurationTaskId() {
        Object listener = serverConnection.getPacketListener();
        if (!(listener instanceof ServerConfigurationPacketListenerImpl configListener)) {
            return null;
        }
        Object currentTask = readField(configListener, ServerConfigurationPacketListenerImpl.class, "currentTask");
        if (currentTask instanceof ConfigurationTask ct) {
            return String.valueOf(ct.type().id());
        }
        return null;
    }

    public String describeDiagnostics() {
        return String.format(
                "stage=%s, lastStep=%s, tx=%d, rx=%d, regPkts=%d, keepAlives=%d, serverOpen=%s, clientOpen=%s, serverConnected=%s, serverListener=%s, serverDc=%s, clientDc=%s",
                stage.get(),
                lastClientStep,
                packetsToServer.get(),
                packetsFromServer.get(),
                registryPacketsReceived.get(),
                keepAlivesReceived.get(),
                serverChannel.isOpen(),
                clientChannel.isOpen(),
                serverConnection.isConnected(),
                serverConnection.getPacketListener() == null ? "null" : serverConnection.getPacketListener().getClass().getSimpleName(),
                serverConnection.getDisconnectionDetails(),
                disconnectReason.get()
        );
    }

    private static Object readField(Object target, Class<?> owner, String fieldName) {
        Class<?> cls = owner;
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private ClientLoginPacketListener createClientLoginListener() {
        return new ClientLoginPacketListener() {
            @Override
            public void handleHello(ClientboundHelloPacket packet) {
                throw new IllegalStateException("Unexpected Yggdrasil RSA ClientboundHelloPacket on virtual channel");
            }

            @Override
            public void handleLoginFinished(ClientboundLoginFinishedPacket packet) {
                clientConnection.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
                clientConnection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
                clientConnection.setupInboundProtocol(
                        ConfigurationProtocols.CLIENTBOUND,
                        createClientConfigurationListener()
                );
                stage.set(Stage.CONFIGURATION);
                clientConnection.send(new ServerboundCustomPayloadPacket(new BrandPayload("vanilla")));
                clientConnection.send(new ServerboundClientInformationPacket(ClientInformation.createDefault()));
            }

            @Override
            public void handleDisconnect(ClientboundLoginDisconnectPacket packet) {
                disconnectReason.compareAndSet(null, packet.reason().getString());
                stage.set(Stage.DISCONNECTED);
            }

            @Override
            public void handleCompression(ClientboundLoginCompressionPacket packet) {
                int threshold = packet.getCompressionThreshold();
                compressionThreshold.set(threshold);
                clientConnection.setupCompression(threshold, false);
            }

            @Override
            public void handleCustomQuery(ClientboundCustomQueryPacket packet) {
            }

            @Override
            public void handleRequestCookie(ClientboundCookieRequestPacket packet) {
            }

            @Override
            public void onDisconnect(DisconnectionDetails details) {
                disconnectReason.compareAndSet(null, details.reason().getString());
                stage.set(Stage.DISCONNECTED);
            }

            @Override
            public boolean isAcceptingMessages() {
                return clientConnection.isConnected();
            }
        };
    }

    private ClientConfigurationPacketListener createClientConfigurationListener() {
        return (ClientConfigurationPacketListener) Proxy.newProxyInstance(
                VirtualClientSession.class.getClassLoader(),
                new Class<?>[]{ClientConfigurationPacketListener.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    switch (name) {
                        case "flow":
                            return PacketFlow.CLIENTBOUND;
                        case "protocol":
                            return ConnectionProtocol.CONFIGURATION;
                        case "isAcceptingMessages":
                        case "shouldHandleMessage":
                            return clientConnection.isConnected();
                        case "getConnectionType":
                            return ConnectionType.OTHER;
                        case "getConnection":
                            return clientConnection;
                        case "getPacketProcessor":
                            return null;
                        case "hasChannel":
                            return false;
                        case "send":
                            if (args != null && args.length >= 1) {
                                if (args[0] instanceof Packet<?> pkt) {
                                    clientConnection.send(pkt);
                                } else if (args[0] instanceof CustomPacketPayload payload) {
                                    clientConnection.send(new ServerboundCustomPayloadPacket(payload));
                                }
                            }
                            return null;
                        case "disconnect":
                            if (args != null && args.length == 1 && args[0] instanceof Component c) {
                                clientConnection.disconnect(c);
                            }
                            return null;
                        case "handleCodeOfConduct":
                            lastClientStep = "config:code_of_conduct";
                            clientConnection.send(ServerboundAcceptCodeOfConductPacket.INSTANCE);
                            return null;
                        case "handleConfigurationFinished":
                            lastClientStep = "config:finished->play";
                            clientConnection.send(ServerboundFinishConfigurationPacket.INSTANCE);
                            GameProtocols.Context context = new GameProtocols.Context() {
                                @Override
                                public boolean hasInfiniteMaterials() {
                                    return false;
                                }

                                @Override
                                public boolean canUseCommandBlocks() {
                                    return false;
                                }
                            };
                            clientConnection.setupOutboundProtocol(
                                    GameProtocols.SERVERBOUND_TEMPLATE.bind(
                                            RegistryFriendlyByteBuf.decorator(registryAccess),
                                            context
                                    )
                            );
                            clientConnection.setupInboundProtocol(
                                    GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                                            RegistryFriendlyByteBuf.decorator(registryAccess)
                                    ),
                                    createClientGameProxy()
                            );
                            stage.set(Stage.PLAY);
                            return null;
                        case "handleSelectKnownPacks":
                            lastClientStep = "config:known_packs";
                            clientConnection.send(new ServerboundSelectKnownPacks(List.of()));
                            return null;
                        case "handleRegistryData":
                            registryPacketsReceived.incrementAndGet();
                            lastClientStep = "config:registry_data(" + registryPacketsReceived.get() + ")";
                            return null;
                        case "handleKeepAlive":
                            if (args != null && args.length == 1 && args[0] instanceof ClientboundKeepAlivePacket keepAlive) {
                                keepAlivesReceived.incrementAndGet();
                                clientConnection.send(new ServerboundKeepAlivePacket(keepAlive.getId()));
                            }
                            return null;
                        case "handlePing":
                            if (args != null && args.length == 1 && args[0] instanceof ClientboundPingPacket ping) {
                                clientConnection.send(new ServerboundPongPacket(ping.getId()));
                            }
                            return null;
                        case "handleResourcePackPush":
                            if (args != null && args.length == 1 && args[0] instanceof ClientboundResourcePackPushPacket pack) {
                                clientConnection.send(new ServerboundResourcePackPacket(
                                        pack.id(),
                                        ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED
                                ));
                            }
                            return null;
                        case "handleDisconnect":
                            if (args != null && args.length == 1 && args[0] instanceof ClientboundDisconnectPacket dc) {
                                disconnectReason.compareAndSet(null, dc.reason().getString());
                            }
                            stage.set(Stage.DISCONNECTED);
                            return null;
                        case "onDisconnect":
                            if (args != null && args.length == 1 && args[0] instanceof DisconnectionDetails details) {
                                disconnectReason.compareAndSet(null, details.reason().getString());
                            }
                            stage.set(Stage.DISCONNECTED);
                            return null;
                        case "createDisconnectionInfo":
                            Component reason = (args != null && args.length >= 1 && args[0] instanceof Component c)
                                    ? c
                                    : Component.literal("Disconnected");
                            return new DisconnectionDetails(reason);
                        case "toString":
                            return "VirtualClientConfigProxy(" + profile.name() + ")";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return args != null && args.length == 1 && args[0] == proxy;
                        default:
                            if (method.getReturnType() == boolean.class) {
                                return false;
                            }
                            if (method.getReturnType() == int.class) {
                                return 0;
                            }
                            return null;
                    }
                }
        );
    }

    private ClientGamePacketListener createClientGameProxy() {
        return (ClientGamePacketListener) Proxy.newProxyInstance(
                VirtualClientSession.class.getClassLoader(),
                new Class<?>[]{ClientGamePacketListener.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    switch (name) {
                        case "flow":
                            return PacketFlow.CLIENTBOUND;
                        case "protocol":
                            return ConnectionProtocol.PLAY;
                        case "isAcceptingMessages":
                        case "shouldHandleMessage":
                            return clientConnection.isConnected();
                        case "getConnectionType":
                            return ConnectionType.OTHER;
                        case "getConnection":
                            return clientConnection;
                        case "getPacketProcessor":
                            return null;
                        case "hasChannel":
                            return false;
                        case "send":
                            if (args != null && args.length >= 1) {
                                if (args[0] instanceof Packet<?> pkt) {
                                    clientConnection.send(pkt);
                                } else if (args[0] instanceof CustomPacketPayload payload) {
                                    clientConnection.send(new ServerboundCustomPayloadPacket(payload));
                                }
                            }
                            return null;
                        case "disconnect":
                            if (args != null && args.length == 1 && args[0] instanceof Component c) {
                                clientConnection.disconnect(c);
                            }
                            return null;
                        case "onDisconnect":
                            if (args != null && args.length == 1 && args[0] instanceof DisconnectionDetails details) {
                                disconnectReason.compareAndSet(null, details.reason().getString());
                            }
                            stage.set(Stage.DISCONNECTED);
                            return null;
                        case "handleLogin":
                            if (args != null && args.length == 1 && args[0] instanceof ClientboundLoginPacket login) {
                                assignedEntityId = login.playerId();
                                if (!sentPlayerLoaded) {
                                    sentPlayerLoaded = true;
                                    clientConnection.send(new ServerboundPlayerLoadedPacket());
                                }
                            }
                            return null;
                        case "handleKeepAlive":
                            if (args != null && args.length == 1 && args[0] instanceof ClientboundKeepAlivePacket keepAlive) {
                                clientConnection.send(new ServerboundKeepAlivePacket(keepAlive.getId()));
                            }
                            return null;
                        case "handlePing":
                            if (args != null && args.length == 1 && args[0] instanceof ClientboundPingPacket ping) {
                                clientConnection.send(new ServerboundPongPacket(ping.getId()));
                            }
                            return null;
                        case "handleMovePlayer":
                            if (args != null && args.length == 1 && args[0] instanceof ClientboundPlayerPositionPacket pos) {
                                PositionMoveRotation change = pos.change();
                                clientConnection.send(new ServerboundAcceptTeleportationPacket(
                                        pos.id(),
                                        change.position().x,
                                        change.position().y,
                                        change.position().z,
                                        change.yRot(),
                                        change.xRot()
                                ));
                            }
                            return null;
                        case "handleChunkBatchFinished":
                            clientConnection.send(new ServerboundChunkBatchReceivedPacket(25.0f));
                            return null;
                        case "handleDisconnect":
                            if (args != null && args.length == 1 && args[0] instanceof ClientboundDisconnectPacket dc) {
                                disconnectReason.compareAndSet(null, dc.reason().getString());
                            }
                            stage.set(Stage.DISCONNECTED);
                            return null;
                        case "createDisconnectionInfo":
                            Component reason = (args != null && args.length >= 1 && args[0] instanceof Component c)
                                    ? c
                                    : Component.literal("Disconnected");
                            return new DisconnectionDetails(reason);
                        case "toString":
                            return "VirtualClientGameProxy(" + profile.name() + ")";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return args != null && args.length == 1 && args[0] == proxy;
                        default:
                            if (method.getReturnType() == boolean.class) {
                                return false;
                            }
                            if (method.getReturnType() == int.class) {
                                return 0;
                            }
                            return null;
                    }
                }
        );
    }
}
