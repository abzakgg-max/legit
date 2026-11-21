package dev.bedrocklanbridge.bridge;

import dev.bedrocklanbridge.bridge.auth.AuthPolicy;
import dev.bedrocklanbridge.bridge.auth.AuthResult;
import dev.bedrocklanbridge.bridge.auth.BedrockAuthVerifier;
import dev.bedrocklanbridge.bridge.codec.CodecRegistry;
import dev.bedrocklanbridge.bridge.session.BridgeLoginPacketHandler;
import dev.bedrocklanbridge.bridge.session.LoginStage;
import dev.bedrocklanbridge.bridge.transport.NetherNetBedrockInitializer;
import dev.bedrocklanbridge.bridge.transport.NetherNetBedrockPeer;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetDiscoverySignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.util.nethernet.IdentityUtils;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.compat.BedrockCompat;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.encryption.BedrockEncryptionDecoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.encryption.BedrockEncryptionEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tel.schich.libdatachannel.PeerConnectionConfiguration;

import javax.crypto.SecretKey;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies CloudburstMC Bedrock Protocol, NetherNet WebRTC JNI natives, Jose4j JWT/ECDH,
 * and NeoForge Netty 4.2 compatibility inside the unified nethernet-bridge Gradle runtime.
 */
final class NetherNetRuntimeCompatibilityUnitTest {

    private static BedrockCodec codec2225;

    @BeforeAll
    static void verifyRuntimeDependencies() {
        DependencyCompatibilityCheck.Report report =
                DependencyCompatibilityCheck.verifyRuntime(LoggerFactory.getLogger(NetherNetRuntimeCompatibilityUnitTest.class));
        assertTrue(report.nativeLoaded());
        assertTrue(report.ecdhAvailable());
        codec2225 = CodecRegistry.resolve(2225);
    }

    @Test
    void loginPacketEncodingAndAuthVerifierEnforceStrictPolicy() throws Exception {
        KeyPair kp = generateP384KeyPair();
        KeyPair wrongKp = generateP384KeyPair();

        String selfSignedToken = createSelfSignedTokenJwt(kp, "2535400000000003", "OfflineTester");
        String validClientJwt = createClientDataJwt(kp, "26.66");
        String tamperedClientJwt = createClientDataJwt(wrongKp, "26.66");

        LoginPacket packet = new LoginPacket();
        packet.setProtocolVersion(2225);
        packet.setAuthPayload(new TokenPayload(selfSignedToken, AuthType.SELF_SIGNED));
        packet.setClientJwt(validClientJwt);

        BedrockCodecHelper helper = codec2225.createHelper();
        ByteBuf buf = Unpooled.buffer();
        try {
            codec2225.tryEncode(helper, buf, packet);
            LoginPacket decoded = (LoginPacket) codec2225.tryDecode(helper, buf, 1);
            assertEquals(2225, decoded.getProtocolVersion());
            assertInstanceOf(TokenPayload.class, decoded.getAuthPayload());
        } finally {
            buf.release();
        }

        assertThrows(SecurityException.class, () ->
                BedrockAuthVerifier.verify(packet, null, AuthPolicy.STRICT_MICROSOFT, true));

        LoginPacket tampered = new LoginPacket();
        tampered.setProtocolVersion(2225);
        tampered.setAuthPayload(new TokenPayload(selfSignedToken, AuthType.SELF_SIGNED));
        tampered.setClientJwt(tamperedClientJwt);

        assertThrows(SecurityException.class, () ->
                BedrockAuthVerifier.verify(tampered, null, AuthPolicy.ALLOW_SELF_SIGNED_TEST, false));

        AuthResult ok = BedrockAuthVerifier.verify(packet, null, AuthPolicy.ALLOW_SELF_SIGNED_TEST, false);
        assertFalse(ok.chainSigned());
        assertEquals("2535400000000003", ok.xuid());
        assertEquals("OfflineTester", ok.displayName());
    }

    @Test
    void encryptionAesCtrRoundtripAndTamperRejection() throws Exception {
        KeyPair serverKp = EncryptionUtils.createKeyPair();
        KeyPair clientKp = EncryptionUtils.createKeyPair();
        byte[] salt = EncryptionUtils.generateRandomToken();

        SecretKey serverKey = EncryptionUtils.getSecretKey(serverKp.getPrivate(), clientKp.getPublic(), salt);
        SecretKey clientKey = EncryptionUtils.getSecretKey(clientKp.getPrivate(), serverKp.getPublic(), salt);
        assertArrayEquals(serverKey.getEncoded(), clientKey.getEncoded());

        EmbeddedChannel encoderChannel = new EmbeddedChannel(
                new BedrockEncryptionEncoder(serverKey, EncryptionUtils.createCipher(true, true, serverKey))
        );
        EmbeddedChannel decoderChannel = new EmbeddedChannel(
                new BedrockEncryptionDecoder(clientKey, EncryptionUtils.createCipher(true, false, clientKey))
        );

        byte[] plaintext = "nethernet-bridge-stage2-crypto-check".getBytes(StandardCharsets.UTF_8);
        BedrockBatchWrapper outbound = BedrockBatchWrapper.newInstance(Unpooled.wrappedBuffer(plaintext), null);
        assertTrue(encoderChannel.writeOutbound(outbound));

        BedrockBatchWrapper encrypted = encoderChannel.readOutbound();
        assertNotNull(encrypted);
        assertTrue(decoderChannel.writeInbound(encrypted.retain()));
        BedrockBatchWrapper decrypted = decoderChannel.readInbound();
        assertNotNull(decrypted);
        byte[] outBytes = new byte[decrypted.getCompressed().readableBytes()];
        decrypted.getCompressed().readBytes(outBytes);
        assertArrayEquals(plaintext, outBytes);

        decrypted.release();
        encrypted.release();

        BedrockBatchWrapper outbound2 = BedrockBatchWrapper.newInstance(Unpooled.wrappedBuffer(plaintext), null);
        assertTrue(encoderChannel.writeOutbound(outbound2));
        BedrockBatchWrapper tampered = encoderChannel.readOutbound();
        ByteBuf comp = tampered.getCompressed();
        comp.setByte(0, comp.getByte(0) ^ 0xFF);
        assertThrows(CorruptedFrameException.class, () -> decoderChannel.writeInbound(tampered));

        encoderChannel.finishAndReleaseAll();
        decoderChannel.finishAndReleaseAll();
    }

    @Test
    void netherNetWebRtcLoopbackAndTransportBindingWorkUnderNeoForgeNetty() throws Exception {
        int port = ThreadLocalRandom.current().nextInt(32000, 48000);
        OperatorIdentity serverIdentity = OperatorIdentity.generate("bridge-junit-server");
        KeyPair clientKp = generateP384KeyPair();
        KeyPair attackerKp = generateP384KeyPair();

        String clientToken = createSelfSignedTokenJwt(clientKp, "2535400000000009", "RealSdpMatchedPlayer");
        String clientJwt = createClientDataJwt(clientKp, "26.66");
        OperatorIdentity clientIdentity = OperatorIdentity.fromToken(clientKp, clientToken, "bridge-junit-client");

        AuthResult established = runLoopbackAttempt(port, serverIdentity, clientIdentity, clientToken, clientJwt);
        assertEquals("RealSdpMatchedPlayer", established.displayName());
        assertEquals(TransportIdentityBinding.State.ACCEPTED, established.transportBindingState());

        String attackerToken = createSelfSignedTokenJwt(attackerKp, "2535400000000999", "ReplayAttacker");
        String attackerClientJwt = createClientDataJwt(attackerKp, "26.66");
        assertThrows(Exception.class, () ->
                runLoopbackAttempt(port + 1, serverIdentity, clientIdentity, attackerToken, attackerClientJwt));
    }

    @Test
    void stageGuardRejectsOutOfOrderLoginPacket() {
        EmbeddedChannel channel = new EmbeddedChannel(new BedrockPacketCodec_v3());
        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND);
        NetherNetBedrockPeer peer = new NetherNetBedrockPeer(channel, BedrockServerSession::new);
        assertEquals(11, peer.getRakVersion());

        BedrockServerSession session = new BedrockServerSession(peer, 0);
        session.setCodec(BedrockCompat.CODEC);

        CompletableFuture<String> failure = new CompletableFuture<>();
        BridgeLoginPacketHandler handler = new BridgeLoginPacketHandler(
                session,
                AuthPolicy.ALLOW_SELF_SIGNED_TEST,
                false,
                false,
                false,
                new BridgeLoginPacketHandler.SessionObserver() {
                    @Override
                    public void onFailure(String reason, Throwable error) {
                        failure.complete(reason);
                    }
                }
        );

        handler.handlePacket(new LoginPacket());
        assertEquals(LoginStage.FAILED_OR_DISCONNECTED, handler.getStage());
        assertTrue(failure.isDone());
        channel.finishAndReleaseAll();
    }

    private static AuthResult runLoopbackAttempt(
            int port,
            OperatorIdentity serverIdentity,
            OperatorIdentity clientIdentity,
            String loginTokenJwt,
            String clientDataJwt
    ) throws Exception {
        CompletableFuture<AuthResult> serverResult = new CompletableFuture<>();
        CompletableFuture<Void> clientDone = new CompletableFuture<>();

        InetAddress loopback = InetAddress.getLoopbackAddress();
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(1);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        NetherNetDiscoverySignaling serverSignaling = new NetherNetDiscoverySignaling(
                ThreadLocalRandom.current().nextLong(),
                new InetSocketAddress(port)
        );
        serverSignaling.setTokenTrust(TokenTrust.ANY);
        serverSignaling.setServerIdentity(serverIdentity);
        PongData pong = new PongData.Builder()
                .setDataVersion(7)
                .setServerName("Bridge Test")
                .setLevelName("Bridge World")
                .setProtocol(2225)
                .setVersion("26.66")
                .setPlayerCount(0)
                .setMaxPlayerCount(8)
                .setGameType(0)
                .setTransportLayer(2)
                .setConnectionType(4)
                .build();
        serverSignaling.setMotdProvider((host, remoteAddress, client) -> pong);

        NetherNetDiscoverySignaling clientSignaling = new NetherNetDiscoverySignaling();
        Channel serverChannel = null;
        Channel clientChannel = null;

        try {
            NetherNetBedrockInitializer<?> serverInit = NetherNetBedrockInitializer.forServer(session ->
                    session.setPacketHandler(new BridgeLoginPacketHandler(
                            session,
                            AuthPolicy.ALLOW_SELF_SIGNED_TEST,
                            true,
                            true,
                            false,
                            new BridgeLoginPacketHandler.SessionObserver() {
                                @Override
                                public void onSessionEstablished(AuthResult result, boolean resourcePacksConfirmed) {
                                    serverResult.complete(result);
                                }

                                @Override
                                public void onFailure(String reason, Throwable error) {
                                    serverResult.completeExceptionally(new SecurityException(reason, error));
                                }
                            }
                    ))
            );

            serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channelFactory(NetherNetChannelFactory.server(serverSignaling))
                    .option(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG,
                            PeerConnectionConfiguration.DEFAULT.withBindAddress(loopback))
                    .childHandler(serverInit)
                    .bind(new InetSocketAddress(port))
                    .sync()
                    .channel();

            NetherNetBedrockInitializer<BedrockClientSession> clientInit = new NetherNetBedrockInitializer<>(
                    PacketDirection.SERVER_BOUND,
                    BedrockClientSession::new,
                    clientSession -> {
                        clientSession.setCodec(codec2225);
                        clientSession.setPacketHandler(new BedrockPacketHandler() {
                            @Override
                            public PacketSignal handle(NetworkSettingsPacket packet) {
                                clientSession.setCodec(codec2225);
                                clientSession.setCompression(PacketCompressionAlgorithm.ZLIB);
                                LoginPacket login = new LoginPacket();
                                login.setProtocolVersion(2225);
                                login.setAuthPayload(new TokenPayload(loginTokenJwt, AuthType.SELF_SIGNED));
                                login.setClientJwt(clientDataJwt);
                                clientSession.sendPacketImmediately(login);
                                return PacketSignal.HANDLED;
                            }

                            @Override
                            public PacketSignal handle(PlayStatusPacket packet) {
                                return PacketSignal.HANDLED;
                            }

                            @Override
                            public PacketSignal handle(ResourcePacksInfoPacket packet) {
                                ClientCacheStatusPacket cache = new ClientCacheStatusPacket();
                                cache.setSupported(false);
                                clientSession.sendPacketImmediately(cache);
                                ResourcePackClientResponsePacket resp = new ResourcePackClientResponsePacket();
                                resp.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
                                clientSession.sendPacketImmediately(resp);
                                return PacketSignal.HANDLED;
                            }

                            @Override
                            public PacketSignal handle(ResourcePackStackPacket packet) {
                                ResourcePackClientResponsePacket resp = new ResourcePackClientResponsePacket();
                                resp.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
                                clientSession.sendPacketImmediately(resp);
                                clientDone.complete(null);
                                return PacketSignal.HANDLED;
                            }

                            @Override
                            public void onDisconnect(CharSequence reason) {
                                if (!clientDone.isDone()) {
                                    clientDone.completeExceptionally(new SecurityException("Disconnected: " + reason));
                                }
                            }
                        });
                    }
            );

            clientChannel = new Bootstrap()
                    .group(clientGroup)
                    .channelFactory(NetherNetChannelFactory.client(clientSignaling))
                    .option(NetherChannelOption.NETHER_CLIENT_IDENTITY, clientIdentity)
                    .option(NetherChannelOption.NETHER_CLIENT_SERVER_TRUST, TokenTrust.pinnedTo(serverIdentity.publicKey()))
                    .option(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG,
                            PeerConnectionConfiguration.DEFAULT.withBindAddress(loopback))
                    .handler(clientInit)
                    .connect(new InetSocketAddress(loopback, port))
                    .sync()
                    .channel();

            RequestNetworkSettingsPacket req = new RequestNetworkSettingsPacket();
            req.setProtocolVersion(2225);
            clientChannel.pipeline().get(BedrockPeer.class).sendPacketImmediately(0, 0, req);

            AuthResult result = serverResult.get(10, TimeUnit.SECONDS);
            clientDone.get(10, TimeUnit.SECONDS);
            return result;
        } finally {
            if (clientChannel != null) {
                clientChannel.close().syncUninterruptibly();
            }
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            clientSignaling.close();
            serverSignaling.close();
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    private static KeyPair generateP384KeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp384r1"));
        return kpg.generateKeyPair();
    }

    private static String createSelfSignedTokenJwt(KeyPair keyPair, String xuid, String name) throws Exception {
        JwtClaims claims = new JwtClaims();
        claims.setClaim("cpk", IdentityUtils.encodePublicKey(keyPair.getPublic()));
        claims.setClaim("xid", xuid);
        claims.setClaim("xname", name);
        claims.setClaim("mid", "junit-mid");
        claims.setIssuedAtToNow();
        claims.setExpirationTime(NumericDate.fromMilliseconds(Instant.now().plusSeconds(3600).toEpochMilli()));

        JsonWebSignature jws = new JsonWebSignature();
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        jws.setPayload(claims.toJson());
        jws.setKey(keyPair.getPrivate());
        return jws.getCompactSerialization();
    }

    private static String createClientDataJwt(KeyPair keyPair, String gameVersion) throws Exception {
        JwtClaims claims = new JwtClaims();
        claims.setClaim("GameVersion", gameVersion);
        claims.setClaim("DeviceModel", "JUnit-Bedrock");
        claims.setClaim("DeviceOS", 1);
        claims.setClaim("LanguageCode", "en_US");

        JsonWebSignature jws = new JsonWebSignature();
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        jws.setHeader("x5u", IdentityUtils.encodePublicKey(keyPair.getPublic()));
        jws.setPayload(claims.toJson());
        jws.setKey(keyPair.getPrivate());
        return jws.getCompactSerialization();
    }
}
