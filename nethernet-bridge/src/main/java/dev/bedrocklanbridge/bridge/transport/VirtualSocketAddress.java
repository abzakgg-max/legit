package dev.bedrocklanbridge.bridge.transport;

import java.net.SocketAddress;
import java.util.Objects;
import java.util.UUID;

/**
 * Virtual {@link SocketAddress} formatted cleanly for Minecraft's {@code IpBanList} and diagnostics.
 */
public final class VirtualSocketAddress extends SocketAddress {

    private final String playerName;
    private final UUID playerUuid;
    private final String formatted;

    public VirtualSocketAddress(String playerName, UUID playerUuid) {
        this.playerName = Objects.requireNonNull(playerName, "playerName");
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        String uuidPrefix = playerUuid.toString().substring(0, 8);
        this.formatted = "bedrock-virtual/" + playerName + ":" + uuidPrefix;
    }

    public String playerName() {
        return playerName;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    @Override
    public String toString() {
        return formatted;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VirtualSocketAddress that)) {
            return false;
        }
        return playerName.equals(that.playerName) && playerUuid.equals(that.playerUuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerName, playerUuid);
    }
}
