package dev.bedrocklanbridge.bridge.transport;

import io.netty.channel.Channel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannel;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;

/**
 * BedrockPeer specialization for NetherNet WebRTC DTLS channels.
 */
public class NetherNetBedrockPeer extends BedrockPeer {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetherNetBedrockPeer.class);
    public static final int EQUIVALENT_RAKNET_VERSION = 11;

    public NetherNetBedrockPeer(Channel channel, BedrockSessionFactory sessionFactory) {
        super(channel, sessionFactory);
    }

    @Override
    public int getRakVersion() {
        return EQUIVALENT_RAKNET_VERSION;
    }

    @Override
    public void enableEncryption(SecretKey secretKey) {
        if (getChannel() instanceof NetherNetChannel) {
            LOGGER.debug("[NetherNetBridge] Skipping Bedrock AES encryption layer for {} (DTLS active)",
                    getSocketAddress());
            return;
        }
        super.enableEncryption(secretKey);
    }
}
