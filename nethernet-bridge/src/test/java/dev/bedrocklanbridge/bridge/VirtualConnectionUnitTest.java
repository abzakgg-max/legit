package dev.bedrocklanbridge.bridge;

import com.mojang.authlib.GameProfile;
import dev.bedrocklanbridge.bridge.session.VirtualClientSession;
import dev.bedrocklanbridge.bridge.session.VirtualSessionManager;
import dev.bedrocklanbridge.bridge.transport.VirtualBedrockChannel;
import dev.bedrocklanbridge.bridge.transport.VirtualSocketAddress;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.handshake.ServerHandshakePacketListener;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerLoginPacketListener;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.level.ClientInformation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies MC 26.3 Netty framing, {@link VirtualBedrockChannel}, non-memory fault isolation,
 * and compressed Handshake -> Login -> Configuration roundtrip inside nethernet-bridge.
 */
final class VirtualConnectionUnitTest {

    @BeforeAll
    static void initVersion() {
        SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void virtualChannelIsNeverMarkedMemoryConnection() {
        UUID uuid = VirtualSessionManager.deterministicTestBotUuid("Abzakcc");
        VirtualSocketAddress addr = new VirtualSocketAddress("Abzakcc", uuid);
        Connection conn = new Connection(PacketFlow.SERVERBOUND);
        VirtualBedrockChannel ch = new VirtualBedrockChannel(addr, new ChannelInitializer<io.netty.channel.Channel>() {
            @Override
            protected void initChannel(io.netty.channel.Channel c) {
                Connection.configureSerialization(c.pipeline(), PacketFlow.SERVERBOUND, false, null);
                conn.configurePacketHandler(c.pipeline());
            }
        });

        assertTrue(conn.isConnected());
        assertFalse(conn.isMemoryConnection());
        assertEquals(addr, conn.getRemoteAddress());
        ch.close().syncUninterruptibly();
    }

    @Test
    void handshakeLoginCompressionAndConfigurationRoundtrip() {
        UUID uuid = VirtualSessionManager.deterministicTestBotUuid("Abzakcc");
        GameProfile profile = new GameProfile(uuid, "Abzakcc");
        VirtualSocketAddress addr = new VirtualSocketAddress("Abzakcc", uuid);

        AtomicBoolean serverSawHello = new AtomicBoolean();
        AtomicBoolean serverSawBrand = new AtomicBoolean();
        AtomicBoolean serverSawFinishConfig = new AtomicBoolean();
        AtomicReference<String> clientBrand = new AtomicReference<>();

        Connection serverConn = new Connection(PacketFlow.SERVERBOUND);
        VirtualBedrockChannel serverChan = new VirtualBedrockChannel(addr, new ChannelInitializer<io.netty.channel.Channel>() {
            @Override
            protected void initChannel(io.netty.channel.Channel c) {
                ChannelPipeline p = c.pipeline();
                Connection.configureSerialization(p, PacketFlow.SERVERBOUND, false, null);
                serverConn.configurePacketHandler(p);

                ServerConfigurationPacketListener serverConfigListener =
                        (ServerConfigurationPacketListener) Proxy.newProxyInstance(
                                VirtualConnectionUnitTest.class.getClassLoader(),
                                new Class<?>[]{ServerConfigurationPacketListener.class},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "flow":
                                            return PacketFlow.SERVERBOUND;
                                        case "protocol":
                                            return ConnectionProtocol.CONFIGURATION;
                                        case "isAcceptingMessages":
                                        case "shouldHandleMessage":
                                            return serverConn.isConnected();
                                        case "getConnectionType":
                                            return ConnectionType.OTHER;
                                        case "getConnection":
                                            return serverConn;
                                        case "getPacketProcessor":
                                            return null;
                                        case "hasChannel":
                                            return false;
                                        case "send":
                                            if (args != null && args.length >= 1 && args[0] instanceof Packet<?> pkt) {
                                                serverConn.send(pkt);
                                            }
                                            return null;
                                        case "disconnect":
                                            if (args != null && args.length == 1 && args[0] instanceof Component dc) {
                                                serverConn.disconnect(dc);
                                            }
                                            return null;
                                        case "handleSelectKnownPacks":
                                            serverConn.send(ClientboundFinishConfigurationPacket.INSTANCE);
                                            return null;
                                        case "handleConfigurationFinished":
                                            serverSawFinishConfig.set(true);
                                            return null;
                                        case "handleCustomPayload":
                                            if (args != null && args.length == 1
                                                    && args[0] instanceof ServerboundCustomPayloadPacket packet
                                                    && packet.payload() instanceof BrandPayload bp) {
                                                serverSawBrand.set(true);
                                                clientBrand.set(bp.brand());
                                            }
                                            return null;
                                        case "createDisconnectionInfo":
                                            Component reason = (args != null && args.length >= 1 && args[0] instanceof Component comp)
                                                    ? comp
                                                    : Component.literal("Disconnected");
                                            return new DisconnectionDetails(reason);
                                        case "toString":
                                            return "TestServerConfigProxy";
                                        case "hashCode":
                                            return System.identityHashCode(proxy);
                                        case "equals":
                                            return args != null && args.length == 1 && args[0] == proxy;
                                        default:
                                            return method.getReturnType() == boolean.class ? false : null;
                                    }
                                }
                        );

                ServerLoginPacketListener serverLoginListener = new ServerLoginPacketListener() {
                    @Override
                    public void handleHello(ServerboundHelloPacket packet) {
                        serverSawHello.set(true);
                        serverConn.send(
                                new ClientboundLoginCompressionPacket(256),
                                PacketSendListener.thenRun(() -> serverConn.setupCompression(256, true))
                        );
                        serverConn.send(new ClientboundLoginFinishedPacket(profile, UUID.randomUUID()));
                    }

                    @Override
                    public void handleKey(ServerboundKeyPacket packet) {
                    }

                    @Override
                    public void handleCustomQueryPacket(ServerboundCustomQueryAnswerPacket packet) {
                    }

                    @Override
                    public void handleLoginAcknowledgement(ServerboundLoginAcknowledgedPacket packet) {
                        serverConn.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND);
                        serverConn.setupInboundProtocol(ConfigurationProtocols.SERVERBOUND, serverConfigListener);
                        serverConn.send(new ClientboundSelectKnownPacks(List.of()));
                    }

                    @Override
                    public void handleCookieResponse(ServerboundCookieResponsePacket packet) {
                    }

                    @Override
                    public void onDisconnect(DisconnectionDetails details) {
                    }

                    @Override
                    public boolean isAcceptingMessages() {
                        return serverConn.isConnected();
                    }
                };

                serverConn.setListenerForServerboundHandshake(new ServerHandshakePacketListener() {
                    @Override
                    public void handleIntention(ClientIntentionPacket packet) {
                        assertEquals(ClientIntent.LOGIN, packet.intention());
                        serverConn.setupOutboundProtocol(LoginProtocols.CLIENTBOUND);
                        serverConn.setupInboundProtocol(LoginProtocols.SERVERBOUND, serverLoginListener);
                    }

                    @Override
                    public void onDisconnect(DisconnectionDetails details) {
                    }

                    @Override
                    public boolean isAcceptingMessages() {
                        return serverConn.isConnected();
                    }
                });
            }
        });

        Connection clientConn = new Connection(PacketFlow.CLIENTBOUND);
        AtomicBoolean clientFinishedConfig = new AtomicBoolean();
        AtomicBoolean clientCompressionEnabled = new AtomicBoolean();

        EmbeddedChannel clientChan = new EmbeddedChannel(new ChannelInitializer<io.netty.channel.Channel>() {
            @Override
            protected void initChannel(io.netty.channel.Channel c) {
                Connection.configureSerialization(c.pipeline(), PacketFlow.CLIENTBOUND, false, null);
                clientConn.configurePacketHandler(c.pipeline());
            }
        });

        ClientConfigurationPacketListener clientConfigListener =
                (ClientConfigurationPacketListener) Proxy.newProxyInstance(
                        VirtualConnectionUnitTest.class.getClassLoader(),
                        new Class<?>[]{ClientConfigurationPacketListener.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "flow":
                                    return PacketFlow.CLIENTBOUND;
                                case "protocol":
                                    return ConnectionProtocol.CONFIGURATION;
                                case "isAcceptingMessages":
                                case "shouldHandleMessage":
                                    return clientConn.isConnected();
                                case "getConnectionType":
                                    return ConnectionType.OTHER;
                                case "getConnection":
                                    return clientConn;
                                case "getPacketProcessor":
                                    return null;
                                case "hasChannel":
                                    return false;
                                case "send":
                                    if (args != null && args.length >= 1) {
                                        if (args[0] instanceof Packet<?> pkt) {
                                            clientConn.send(pkt);
                                        } else if (args[0] instanceof CustomPacketPayload payload) {
                                            clientConn.send(new ServerboundCustomPayloadPacket(payload));
                                        }
                                    }
                                    return null;
                                case "disconnect":
                                    if (args != null && args.length == 1 && args[0] instanceof Component dc) {
                                        clientConn.disconnect(dc);
                                    }
                                    return null;
                                case "handleConfigurationFinished":
                                    clientConn.send(ServerboundFinishConfigurationPacket.INSTANCE);
                                    clientFinishedConfig.set(true);
                                    return null;
                                case "handleSelectKnownPacks":
                                    clientConn.send(new ServerboundSelectKnownPacks(List.of()));
                                    return null;
                                case "handleKeepAlive":
                                    if (args != null && args.length == 1 && args[0] instanceof ClientboundKeepAlivePacket p) {
                                        clientConn.send(new ServerboundKeepAlivePacket(p.getId()));
                                    }
                                    return null;
                                case "handlePing":
                                    if (args != null && args.length == 1 && args[0] instanceof ClientboundPingPacket p) {
                                        clientConn.send(new ServerboundPongPacket(p.getId()));
                                    }
                                    return null;
                                case "toString":
                                    return "TestClientConfigProxy";
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "equals":
                                    return args != null && args.length == 1 && args[0] == proxy;
                                default:
                                    return method.getReturnType() == boolean.class ? false : null;
                            }
                        }
                );

        ClientLoginPacketListener clientLoginListener = new ClientLoginPacketListener() {
            @Override
            public void handleHello(ClientboundHelloPacket packet) {
                throw new AssertionError("Should skip Yggdrasil RSA hello");
            }

            @Override
            public void handleLoginFinished(ClientboundLoginFinishedPacket packet) {
                clientConn.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
                clientConn.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
                clientConn.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, clientConfigListener);
                clientConn.send(new ServerboundCustomPayloadPacket(new BrandPayload("vanilla")));
                clientConn.send(new ServerboundClientInformationPacket(ClientInformation.createDefault()));
            }

            @Override
            public void handleDisconnect(ClientboundLoginDisconnectPacket packet) {
            }

            @Override
            public void handleCompression(ClientboundLoginCompressionPacket packet) {
                clientCompressionEnabled.set(true);
                clientConn.setupCompression(packet.getCompressionThreshold(), false);
            }

            @Override
            public void handleCustomQuery(ClientboundCustomQueryPacket packet) {
            }

            @Override
            public void handleRequestCookie(ClientboundCookieRequestPacket packet) {
            }

            @Override
            public void onDisconnect(DisconnectionDetails details) {
            }

            @Override
            public boolean isAcceptingMessages() {
                return clientConn.isConnected();
            }
        };

        clientConn.setupOutboundProtocol(HandshakeProtocols.SERVERBOUND);
        clientConn.send(new ClientIntentionPacket(
                SharedConstants.getCurrentVersion().protocolVersion(),
                "nethernet-bridge",
                0,
                ClientIntent.LOGIN
        ));
        clientConn.setupInboundProtocol(LoginProtocols.CLIENTBOUND, clientLoginListener);
        clientConn.setupOutboundProtocol(LoginProtocols.SERVERBOUND);
        clientConn.send(new ServerboundHelloPacket(profile.name(), profile.id()));

        for (int i = 0; i < 8; i++) {
            clientChan.flush();
            Object msg;
            while ((msg = clientChan.readOutbound()) != null) {
                serverChan.writeInbound(msg);
                serverChan.runPendingTasks();
            }
            serverChan.flush();
            while ((msg = serverChan.readOutbound()) != null) {
                clientChan.writeInbound(msg);
                clientChan.runPendingTasks();
            }
        }

        assertTrue(serverSawHello.get());
        assertTrue(clientCompressionEnabled.get());
        assertTrue(serverSawBrand.get());
        assertEquals("vanilla", clientBrand.get());
        assertTrue(clientFinishedConfig.get());
        assertTrue(serverSawFinishConfig.get());

        clientChan.close().syncUninterruptibly();
        serverChan.close().syncUninterruptibly();
    }

    @Test
    void malformedInboundFrameDisconnectsOnlyVirtualChannel() {
        UUID uuid = VirtualSessionManager.deterministicTestBotUuid("FaultyGuest");
        VirtualSocketAddress addr = new VirtualSocketAddress("FaultyGuest", uuid);
        Connection serverConn = new Connection(PacketFlow.SERVERBOUND);
        VirtualBedrockChannel serverChan = new VirtualBedrockChannel(addr, new ChannelInitializer<io.netty.channel.Channel>() {
            @Override
            protected void initChannel(io.netty.channel.Channel c) {
                Connection.configureSerialization(c.pipeline(), PacketFlow.SERVERBOUND, false, null);
                serverConn.configurePacketHandler(c.pipeline());
                serverConn.setListenerForServerboundHandshake(new ServerHandshakePacketListener() {
                    @Override
                    public void handleIntention(ClientIntentionPacket packet) {
                    }

                    @Override
                    public void onDisconnect(DisconnectionDetails details) {
                    }

                    @Override
                    public boolean isAcceptingMessages() {
                        return serverConn.isConnected();
                    }
                });
            }
        });

        ByteBuf bogus = Unpooled.buffer();
        bogus.writeByte(0x04);
        bogus.writeByte(0x7F);
        bogus.writeBytes("BAD".getBytes(StandardCharsets.US_ASCII));

        serverChan.writeInbound(bogus);
        serverChan.runPendingTasks();

        assertFalse(serverConn.isMemoryConnection());
        assertNotNull(serverConn.getDisconnectionDetails());
        assertFalse(serverConn.isConnected());
        serverChan.close().syncUninterruptibly();
    }

    @Test
    void compressedMalformedCompressionHeaderDisconnectsOnlyFaultyChannel() {
        CompressedTestPair control = createCompressedConfigPair("ControlHdr");
        CompressedTestPair faulty = createCompressedConfigPair("FaultHdr");

        VirtualClientSession.FaultInjectionResult result = VirtualClientSession.injectFaultFrame(
                faulty.serverChan,
                faulty.serverConn,
                256,
                VirtualClientSession.FaultMode.MALFORMED_COMPRESSION_HEADER
        );

        assertTrue(result.disconnected());
        assertNotNull(result.disconnectionDetails());
        assertFalse(faulty.serverConn.isConnected());
        assertFalse(faulty.serverChan.isOpen());

        control.serverConn.send(new ClientboundPingPacket(701));
        control.pump(4);

        assertTrue(control.serverConn.isConnected());
        assertTrue(control.clientConn.isConnected());
        assertEquals(1, control.serverPongCount.get());

        control.close();
        faulty.close();
    }

    @Test
    void compressedInvalidPacketIdDisconnectsOnlyFaultyChannel() {
        CompressedTestPair control = createCompressedConfigPair("ControlId");
        CompressedTestPair faulty = createCompressedConfigPair("FaultId");

        VirtualClientSession.FaultInjectionResult result = VirtualClientSession.injectFaultFrame(
                faulty.serverChan,
                faulty.serverConn,
                256,
                VirtualClientSession.FaultMode.INVALID_PACKET_ID_COMPRESSED
        );

        assertTrue(result.disconnected());
        assertNotNull(result.disconnectionDetails());
        assertFalse(faulty.serverConn.isConnected());
        assertFalse(faulty.serverChan.isOpen());

        control.serverConn.send(new ClientboundPingPacket(702));
        control.pump(4);

        assertTrue(control.serverConn.isConnected());
        assertTrue(control.clientConn.isConnected());
        assertEquals(1, control.serverPongCount.get());

        control.close();
        faulty.close();
    }

    private static CompressedTestPair createCompressedConfigPair(String playerName) {
        UUID uuid = VirtualSessionManager.deterministicTestBotUuid(playerName);
        GameProfile profile = new GameProfile(uuid, playerName);
        VirtualSocketAddress addr = new VirtualSocketAddress(playerName, uuid);

        AtomicBoolean serverEnteredConfig = new AtomicBoolean();
        AtomicBoolean clientCompressionEnabled = new AtomicBoolean();
        AtomicInteger serverPongCount = new AtomicInteger();

        Connection serverConn = new Connection(PacketFlow.SERVERBOUND);
        VirtualBedrockChannel serverChan = new VirtualBedrockChannel(addr, new ChannelInitializer<io.netty.channel.Channel>() {
            @Override
            protected void initChannel(io.netty.channel.Channel c) {
                ChannelPipeline p = c.pipeline();
                Connection.configureSerialization(p, PacketFlow.SERVERBOUND, false, null);
                serverConn.configurePacketHandler(p);

                ServerConfigurationPacketListener serverConfigListener =
                        (ServerConfigurationPacketListener) Proxy.newProxyInstance(
                                VirtualConnectionUnitTest.class.getClassLoader(),
                                new Class<?>[]{ServerConfigurationPacketListener.class},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "flow":
                                            return PacketFlow.SERVERBOUND;
                                        case "protocol":
                                            return ConnectionProtocol.CONFIGURATION;
                                        case "isAcceptingMessages":
                                        case "shouldHandleMessage":
                                            return serverConn.isConnected();
                                        case "getConnectionType":
                                            return ConnectionType.OTHER;
                                        case "getConnection":
                                            return serverConn;
                                        case "getPacketProcessor":
                                            return null;
                                        case "hasChannel":
                                            return false;
                                        case "send":
                                            if (args != null && args.length >= 1 && args[0] instanceof Packet<?> pkt) {
                                                serverConn.send(pkt);
                                            }
                                            return null;
                                        case "disconnect":
                                            if (args != null && args.length == 1 && args[0] instanceof Component dc) {
                                                serverConn.disconnect(dc);
                                            }
                                            return null;
                                        case "handlePong":
                                            serverPongCount.incrementAndGet();
                                            return null;
                                        case "createDisconnectionInfo":
                                            Component reason = (args != null && args.length >= 1 && args[0] instanceof Component comp)
                                                    ? comp
                                                    : Component.literal("Disconnected");
                                            return new DisconnectionDetails(reason);
                                        case "toString":
                                            return "TestCompressedServerConfigProxy";
                                        case "hashCode":
                                            return System.identityHashCode(proxy);
                                        case "equals":
                                            return args != null && args.length == 1 && args[0] == proxy;
                                        default:
                                            return method.getReturnType() == boolean.class ? false : null;
                                    }
                                }
                        );

                ServerLoginPacketListener serverLoginListener = new ServerLoginPacketListener() {
                    @Override
                    public void handleHello(ServerboundHelloPacket packet) {
                        serverConn.send(
                                new ClientboundLoginCompressionPacket(256),
                                PacketSendListener.thenRun(() -> serverConn.setupCompression(256, true))
                        );
                        serverConn.send(new ClientboundLoginFinishedPacket(profile, UUID.randomUUID()));
                    }

                    @Override
                    public void handleKey(ServerboundKeyPacket packet) {
                    }

                    @Override
                    public void handleCustomQueryPacket(ServerboundCustomQueryAnswerPacket packet) {
                    }

                    @Override
                    public void handleLoginAcknowledgement(ServerboundLoginAcknowledgedPacket packet) {
                        serverConn.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND);
                        serverConn.setupInboundProtocol(ConfigurationProtocols.SERVERBOUND, serverConfigListener);
                        serverEnteredConfig.set(true);
                    }

                    @Override
                    public void handleCookieResponse(ServerboundCookieResponsePacket packet) {
                    }

                    @Override
                    public void onDisconnect(DisconnectionDetails details) {
                    }

                    @Override
                    public boolean isAcceptingMessages() {
                        return serverConn.isConnected();
                    }
                };

                serverConn.setListenerForServerboundHandshake(new ServerHandshakePacketListener() {
                    @Override
                    public void handleIntention(ClientIntentionPacket packet) {
                        serverConn.setupOutboundProtocol(LoginProtocols.CLIENTBOUND);
                        serverConn.setupInboundProtocol(LoginProtocols.SERVERBOUND, serverLoginListener);
                    }

                    @Override
                    public void onDisconnect(DisconnectionDetails details) {
                    }

                    @Override
                    public boolean isAcceptingMessages() {
                        return serverConn.isConnected();
                    }
                });
            }
        });

        Connection clientConn = new Connection(PacketFlow.CLIENTBOUND);
        EmbeddedChannel clientChan = new EmbeddedChannel(new ChannelInitializer<io.netty.channel.Channel>() {
            @Override
            protected void initChannel(io.netty.channel.Channel c) {
                Connection.configureSerialization(c.pipeline(), PacketFlow.CLIENTBOUND, false, null);
                clientConn.configurePacketHandler(c.pipeline());
            }
        });

        ClientConfigurationPacketListener clientConfigListener =
                (ClientConfigurationPacketListener) Proxy.newProxyInstance(
                        VirtualConnectionUnitTest.class.getClassLoader(),
                        new Class<?>[]{ClientConfigurationPacketListener.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "flow":
                                    return PacketFlow.CLIENTBOUND;
                                case "protocol":
                                    return ConnectionProtocol.CONFIGURATION;
                                case "isAcceptingMessages":
                                case "shouldHandleMessage":
                                    return clientConn.isConnected();
                                case "getConnectionType":
                                    return ConnectionType.OTHER;
                                case "getConnection":
                                    return clientConn;
                                case "getPacketProcessor":
                                    return null;
                                case "hasChannel":
                                    return false;
                                case "handlePing":
                                    if (args != null && args.length == 1 && args[0] instanceof ClientboundPingPacket p) {
                                        clientConn.send(new ServerboundPongPacket(p.getId()));
                                    }
                                    return null;
                                case "handleKeepAlive":
                                    if (args != null && args.length == 1 && args[0] instanceof ClientboundKeepAlivePacket p) {
                                        clientConn.send(new ServerboundKeepAlivePacket(p.getId()));
                                    }
                                    return null;
                                case "createDisconnectionInfo":
                                    Component reason = (args != null && args.length >= 1 && args[0] instanceof Component comp)
                                            ? comp
                                            : Component.literal("Disconnected");
                                    return new DisconnectionDetails(reason);
                                case "toString":
                                    return "TestCompressedClientConfigProxy";
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "equals":
                                    return args != null && args.length == 1 && args[0] == proxy;
                                default:
                                    return method.getReturnType() == boolean.class ? false : null;
                            }
                        }
                );

        ClientLoginPacketListener clientLoginListener = new ClientLoginPacketListener() {
            @Override
            public void handleHello(ClientboundHelloPacket packet) {
                throw new AssertionError("Unexpected hello");
            }

            @Override
            public void handleLoginFinished(ClientboundLoginFinishedPacket packet) {
                clientConn.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
                clientConn.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
                clientConn.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, clientConfigListener);
            }

            @Override
            public void handleDisconnect(ClientboundLoginDisconnectPacket packet) {
            }

            @Override
            public void handleCompression(ClientboundLoginCompressionPacket packet) {
                clientCompressionEnabled.set(true);
                clientConn.setupCompression(packet.getCompressionThreshold(), false);
            }

            @Override
            public void handleCustomQuery(ClientboundCustomQueryPacket packet) {
            }

            @Override
            public void handleRequestCookie(ClientboundCookieRequestPacket packet) {
            }

            @Override
            public void onDisconnect(DisconnectionDetails details) {
            }

            @Override
            public boolean isAcceptingMessages() {
                return clientConn.isConnected();
            }
        };

        clientConn.setupOutboundProtocol(HandshakeProtocols.SERVERBOUND);
        clientConn.send(new ClientIntentionPacket(
                SharedConstants.getCurrentVersion().protocolVersion(),
                "nethernet-bridge",
                0,
                ClientIntent.LOGIN
        ));
        clientConn.setupInboundProtocol(LoginProtocols.CLIENTBOUND, clientLoginListener);
        clientConn.setupOutboundProtocol(LoginProtocols.SERVERBOUND);
        clientConn.send(new ServerboundHelloPacket(profile.name(), profile.id()));

        CompressedTestPair pair = new CompressedTestPair(serverConn, serverChan, clientConn, clientChan, serverPongCount);
        pair.pump(6);

        assertTrue(clientCompressionEnabled.get());
        assertTrue(serverEnteredConfig.get());
        assertNotNull(serverChan.pipeline().get("decompress"));
        return pair;
    }

    private record CompressedTestPair(
            Connection serverConn,
            VirtualBedrockChannel serverChan,
            Connection clientConn,
            EmbeddedChannel clientChan,
            AtomicInteger serverPongCount
    ) {
        void pump(int waves) {
            for (int i = 0; i < waves; i++) {
                clientChan.flush();
                Object msg;
                while ((msg = clientChan.readOutbound()) != null) {
                    if (serverChan.isOpen()) {
                        serverChan.writeInbound(msg);
                        serverChan.runPendingTasks();
                    }
                }
                serverChan.flush();
                while ((msg = serverChan.readOutbound()) != null) {
                    if (clientChan.isOpen()) {
                        clientChan.writeInbound(msg);
                        clientChan.runPendingTasks();
                    }
                }
            }
        }

        void close() {
            try {
                if (clientChan.isOpen()) {
                    clientChan.close().syncUninterruptibly();
                }
            } catch (Exception ignored) {
            }
            try {
                if (serverChan.isOpen()) {
                    serverChan.close().syncUninterruptibly();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
