package dev.bedrocklanbridge.bridge.session;

import dev.bedrocklanbridge.bridge.NetherNetBridgeMod;
import dev.bedrocklanbridge.bridge.auth.BedrockIdentityStore;
import dev.bedrocklanbridge.bridge.auth.BedrockProfileMapper;
import dev.bedrocklanbridge.bridge.auth.IdentityLookup;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates active {@link VirtualClientSession} instances, server-scoped {@link BedrockIdentityStore},
 * and {@code IntegratedServer} Open-to-LAN state transitions.
 */
public final class VirtualSessionManager {

    private final Map<UUID, VirtualClientSession> sessionsByUuid = new ConcurrentHashMap<>();
    private final Map<String, UUID> uuidByLowerName = new ConcurrentHashMap<>();
    private volatile BedrockIdentityStore identityStore = BedrockIdentityStore.inMemory();
    private volatile BedrockProfileMapper profileMapper = new BedrockProfileMapper(identityStore);
    private volatile boolean lastPublishedState;
    private volatile int lastPublishedPort = -1;

    public void initializeForServer(MinecraftServer server) {
        Path storePath = server.getServerDirectory().resolve("config").resolve("nethernet-bridge-identities.json");
        BedrockIdentityStore loaded = BedrockIdentityStore.open(storePath);
        this.identityStore = loaded;
        this.profileMapper = new BedrockProfileMapper(loaded);
        NetherNetBridgeMod.LOGGER.info(
                "[NetherNetBridge] Loaded BedrockIdentityStore ({} entries) from {}",
                loaded.snapshot().size(),
                storePath
        );
    }

    public BedrockProfileMapper profileMapper() {
        return profileMapper;
    }

    public IdentityLookup createServerLookup(MinecraftServer server) {
        return IdentityLookup.fromServer(server, () -> {
            List<IdentityLookup.InFlightIdentity> list = new ArrayList<>();
            for (VirtualClientSession session : sessionsByUuid.values()) {
                identityStore.findXuidByJavaNameIgnoreCase(session.profile().name())
                        .ifPresent(xuid -> list.add(new IdentityLookup.InFlightIdentity(xuid, session.profile().name())));
            }
            return list;
        });
    }

    public VirtualClientSession spawnVirtualPlayer(MinecraftServer server, String playerName) {
        UUID uuid = deterministicTestBotUuid(playerName);
        VirtualClientSession existing = sessionsByUuid.remove(uuid);
        if (existing != null) {
            existing.close("Replaced by new test session");
        }
        VirtualClientSession session = VirtualClientSession.createAndAttach(server, playerName, uuid);
        sessionsByUuid.put(uuid, session);
        uuidByLowerName.put(playerName.toLowerCase(), uuid);
        return session;
    }

    public Optional<VirtualClientSession> findByName(String playerName) {
        UUID uuid = uuidByLowerName.get(playerName.toLowerCase());
        return uuid == null ? Optional.empty() : Optional.ofNullable(sessionsByUuid.get(uuid));
    }

    public boolean disconnectByName(String playerName, String reason) {
        UUID uuid = uuidByLowerName.remove(playerName.toLowerCase());
        if (uuid == null) {
            return false;
        }
        VirtualClientSession session = sessionsByUuid.remove(uuid);
        if (session == null) {
            return false;
        }
        session.close(reason);
        return true;
    }

    public boolean injectFaultByName(String playerName) {
        Optional<VirtualClientSession> found = findByName(playerName);
        if (found.isEmpty()) {
            return false;
        }
        found.get().injectMalformedFrameFault();
        return true;
    }

    public void tickAll(MinecraftServer server) {
        if (!server.isDedicatedServer()) {
            LanPublishInspector.observeLanTransition(server, this);
        }

        List<UUID> closed = new ArrayList<>();
        for (Map.Entry<UUID, VirtualClientSession> entry : sessionsByUuid.entrySet()) {
            VirtualClientSession session = entry.getValue();
            session.pumpOnce();
            if (session.stage() == VirtualClientSession.Stage.DISCONNECTED) {
                closed.add(entry.getKey());
            }
        }
        for (UUID uuid : closed) {
            VirtualClientSession removed = sessionsByUuid.remove(uuid);
            if (removed != null) {
                uuidByLowerName.remove(removed.profile().name().toLowerCase());
            }
        }
    }

    public void shutdownAll() {
        for (VirtualClientSession session : sessionsByUuid.values()) {
            session.close("Server shutting down");
        }
        sessionsByUuid.clear();
        uuidByLowerName.clear();
        lastPublishedState = false;
        lastPublishedPort = -1;
    }

    public Collection<VirtualClientSession> activeSessions() {
        return Collections.unmodifiableCollection(sessionsByUuid.values());
    }

    public String describeStatus(MinecraftServer server) {
        List<Connection> connections = server.getConnection().getConnections();
        long memoryConns = connections.stream().filter(Connection::isMemoryConnection).count();
        long nonMemoryConns = connections.size() - memoryConns;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        String lanState = server.isDedicatedServer()
                ? "dedicated"
                : LanPublishInspector.describe(server);

        return String.format(
                "NetherNetBridge Status | server=%s | connections=%d (memory=%d, nonMemory=%d) | players=%d | virtualSessions=%d | mappedIdentities=%d",
                lanState,
                connections.size(),
                memoryConns,
                nonMemoryConns,
                players.size(),
                sessionsByUuid.size(),
                identityStore.snapshot().size()
        );
    }

    /**
     * Separate namespace for synthetic GameTest/command test bots so they never collide with real XUIDs.
     */
    public static UUID deterministicTestBotUuid(String botName) {
        return UUID.nameUUIDFromBytes(("NetherNetBridge-TestBot:" + botName).getBytes(StandardCharsets.UTF_8));
    }

    void recordLanTransition(boolean published, int port, String scopeName) {
        if (published != lastPublishedState || port != lastPublishedPort) {
            lastPublishedState = published;
            lastPublishedPort = port;
            NetherNetBridgeMod.LOGGER.info(
                    "[NetherNetBridge] IntegratedServer Open-to-LAN state changed: published={}, scope={}, port={}",
                    published,
                    scopeName,
                    port
            );
        }
    }

    private static final class LanPublishInspector {
        static void observeLanTransition(MinecraftServer server, VirtualSessionManager manager) {
            if (server instanceof IntegratedServer integrated) {
                boolean published = integrated.isPublished();
                int port = published ? integrated.getPort() : -1;
                String scope = String.valueOf(integrated.getMultiplayerScope());
                manager.recordLanTransition(published, port, scope);
            }
        }

        static String describe(MinecraftServer server) {
            if (server instanceof IntegratedServer integrated) {
                return "IntegratedServer(published=" + integrated.isPublished()
                        + ", scope=" + integrated.getMultiplayerScope()
                        + ", port=" + integrated.getPort() + ")";
            }
            return server.getClass().getSimpleName();
        }
    }
}
