package dev.bedrocklanbridge.bridge.auth;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.StoredUserEntry;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Abstracts server-side live and cached player identity lookups so {@link BedrockProfileMapper}
 * can enforce collision-free naming both in live NeoForge servers and in standalone unit tests.
 */
public interface IdentityLookup {

    record InFlightIdentity(String xuid, String javaName) {
    }

    Optional<UUID> findOnlineJavaUuidByNameIgnoreCase(String name);

    Optional<UUID> findCachedJavaUuidByNameIgnoreCase(String name);

    Optional<String> findInFlightBedrockXuidByNameIgnoreCase(String name);

    static IdentityLookup empty() {
        return new IdentityLookup() {
            @Override
            public Optional<UUID> findOnlineJavaUuidByNameIgnoreCase(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<UUID> findCachedJavaUuidByNameIgnoreCase(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<String> findInFlightBedrockXuidByNameIgnoreCase(String name) {
                return Optional.empty();
            }
        };
    }

    static IdentityLookup fromServer(
            MinecraftServer server,
            Supplier<? extends Collection<InFlightIdentity>> inFlightSupplier
    ) {
        return new IdentityLookup() {
            @Override
            public Optional<UUID> findOnlineJavaUuidByNameIgnoreCase(String name) {
                PlayerList playerList = server.getPlayerList();
                for (ServerPlayer player : playerList.getPlayers()) {
                    if (player.getGameProfile().name().equalsIgnoreCase(name)) {
                        return Optional.of(player.getUUID());
                    }
                }
                return Optional.empty();
            }

            @Override
            public Optional<UUID> findCachedJavaUuidByNameIgnoreCase(String name) {
                Optional<NameAndId> cached = server.services().nameToIdCache().get(name);
                if (cached.isPresent()) {
                    return Optional.of(cached.get().id());
                }
                PlayerList playerList = server.getPlayerList();
                Optional<UUID> fromOps = findInStoredEntries(playerList.getOps().getEntries(), name);
                if (fromOps.isPresent()) {
                    return fromOps;
                }
                return findInStoredEntries(playerList.getWhiteList().getEntries(), name);
            }

            @Override
            public Optional<String> findInFlightBedrockXuidByNameIgnoreCase(String name) {
                Collection<InFlightIdentity> active = inFlightSupplier != null ? inFlightSupplier.get() : List.of();
                for (InFlightIdentity entry : active) {
                    if (entry != null && entry.javaName() != null && entry.javaName().equalsIgnoreCase(name)) {
                        return Optional.ofNullable(entry.xuid());
                    }
                }
                return Optional.empty();
            }

            private static Optional<UUID> findInStoredEntries(
                    Collection<? extends StoredUserEntry<NameAndId>> entries,
                    String targetName
            ) {
                for (StoredUserEntry<NameAndId> entry : entries) {
                    NameAndId user = entry.getUser();
                    if (user != null && user.name().equalsIgnoreCase(targetName)) {
                        return Optional.of(user.id());
                    }
                }
                return Optional.empty();
            }
        };
    }
}
