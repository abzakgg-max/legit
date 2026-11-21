package dev.bedrocklanbridge.bridge.transport;

import io.netty.channel.Channel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bedrock Netty pipeline initializer tailored for NetherNetChildChannel and test loopback channels.
 */
public class NetherNetBedrockInitializer<T extends BedrockSession> extends BedrockChannelInitializer<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetherNetBedrockInitializer.class);
    private static final CompressionStrategy NOOP_STRATEGY = new SimpleCompressionStrategy(new NoopCompression());

    private final PacketDirection direction;
    private final SessionFactory<T> sessionCreator;
    private final Consumer<T> sessionConfigurer;

    @FunctionalInterface
    public interface SessionFactory<T extends BedrockSession> {
        T create(BedrockPeer peer, int subClientId);
    }

    public static NetherNetBedrockInitializer<BedrockServerSession> forServer(
            Consumer<BedrockServerSession> sessionConfigurer
    ) {
        return new NetherNetBedrockInitializer<>(
                PacketDirection.CLIENT_BOUND,
                BedrockServerSession::new,
                sessionConfigurer
        );
    }

    public NetherNetBedrockInitializer(
            PacketDirection direction,
            SessionFactory<T> sessionCreator,
            Consumer<T> sessionConfigurer
    ) {
        this.direction = Objects.requireNonNull(direction, "direction");
        this.sessionCreator = Objects.requireNonNull(sessionCreator, "sessionCreator");
        this.sessionConfigurer = Objects.requireNonNull(sessionConfigurer, "sessionConfigurer");
    }

    @Override
    protected void preInitChannel(Channel channel) {
        channel.attr(PacketDirection.ATTRIBUTE).set(this.direction);
        channel.pipeline()
                .addLast(NetherNetFrameCodec.NAME, NetherNetFrameCodec.INSTANCE)
                .addLast(CompressionCodec.NAME, new CompressionCodec(NOOP_STRATEGY, false));
    }

    @Override
    protected void initPacketCodec(Channel channel) {
        channel.pipeline().addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3());
    }

    @Override
    protected BedrockPeer createPeer(Channel channel) {
        return new NetherNetBedrockPeer(channel, this::createSession);
    }

    @Override
    protected T createSession0(BedrockPeer peer, int subClientId) {
        return this.sessionCreator.create(peer, subClientId);
    }

    @Override
    protected void initSession(T session) {
        Channel ch = session.getPeer().getChannel();
        if (ch instanceof NetherNetChildChannel) {
            String connId = ch.attr(NetherNetChildChannel.CONNECTION_ID).get();
            PlayerInfo player = ch.attr(NetherNetChildChannel.PLAYER_INFO).get();
            TransportIdentityBinding.State bindingState = TransportIdentityBinding.state(ch);
            LOGGER.info(
                    "[NetherNetBridge] Accepted NetherNetChildChannel connId={}, remote={}, sdpIdentity={}, transportBinding={}",
                    connId,
                    ch.remoteAddress(),
                    player != null ? player.displayName() + " (xid=" + player.xuid() + ")" : "NONE",
                    bindingState
            );
        }
        this.sessionConfigurer.accept(session);
    }
}
