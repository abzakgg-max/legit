package dev.bedrocklanbridge.bridge.transport;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

import java.net.SocketAddress;
import java.util.Objects;

/**
 * Non-Local virtual Netty {@link EmbeddedChannel} ensuring {@code Connection.isMemoryConnection()}
 * is always {@code false} so per-player packet faults never crash the host server.
 */
public final class VirtualBedrockChannel extends EmbeddedChannel {

    private final VirtualSocketAddress virtualAddress;

    public VirtualBedrockChannel(VirtualSocketAddress virtualAddress, ChannelHandler... handlers) {
        super(false, false, handlers);
        this.virtualAddress = Objects.requireNonNull(virtualAddress, "virtualAddress");
        try {
            register();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to register VirtualBedrockChannel", e);
        }
    }

    public VirtualSocketAddress virtualAddress() {
        return virtualAddress;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return virtualAddress;
    }

    @Override
    protected SocketAddress localAddress0() {
        return virtualAddress;
    }
}
