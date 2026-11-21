package dev.bedrocklanbridge.bridge.gametest;

import com.mojang.authlib.GameProfile;
import dev.bedrocklanbridge.bridge.DependencyCompatibilityCheck;
import dev.bedrocklanbridge.bridge.NetherNetBridgeMod;
import dev.bedrocklanbridge.bridge.session.VirtualClientSession;
import dev.bedrocklanbridge.bridge.session.VirtualSessionManager;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Automated NeoForge 26.3 GameTests verifying Cloudburst/NetherNet runtime compatibility
 * alongside isolated virtual lifecycle and malformed compressed frame isolation.
 */
public final class BridgeGameTests {

    public static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, NetherNetBridgeMod.MODID);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> VIRTUAL_LIFECYCLE_FN =
            TEST_FUNCTIONS.register("virtual_connection_lifecycle", () -> BridgeGameTests::runVirtualLifecycleTest);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> MALFORMED_ISOLATION_FN =
            TEST_FUNCTIONS.register("malformed_frame_isolation", () -> BridgeGameTests::runMalformedFrameIsolationTest);

    private BridgeGameTests() {
    }

    public static void onRegisterGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> lifecycleEnv = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(NetherNetBridgeMod.MODID, "lifecycle")
        );
        Holder<TestEnvironmentDefinition<?>> isolationEnv = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(NetherNetBridgeMod.MODID, "isolation")
        );

        TestData<Holder<TestEnvironmentDefinition<?>>> lifecycleData = new TestData<>(
                lifecycleEnv,
                Identifier.withDefaultNamespace("empty"),
                12000,
                0,
                true
        );
        TestData<Holder<TestEnvironmentDefinition<?>>> isolationData = new TestData<>(
                isolationEnv,
                Identifier.withDefaultNamespace("empty"),
                12000,
                0,
                true
        );

        event.registerTest(
                Identifier.fromNamespaceAndPath(NetherNetBridgeMod.MODID, "virtual_connection_lifecycle"),
                new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, VIRTUAL_LIFECYCLE_FN.getId()),
                        lifecycleData
                )
        );
        event.registerTest(
                Identifier.fromNamespaceAndPath(NetherNetBridgeMod.MODID, "malformed_frame_isolation"),
                new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, MALFORMED_ISOLATION_FN.getId()),
                        isolationData
                )
        );
    }

    private static void runVirtualLifecycleTest(GameTestHelper helper) {
        DependencyCompatibilityCheck.Report compat = DependencyCompatibilityCheck.verifyRuntime(NetherNetBridgeMod.LOGGER);
        if (!compat.nativeLoaded() || !compat.ecdhAvailable()) {
            helper.fail("Dependency compatibility check failed inside NeoForge runtime: " + compat.formatSummary());
            return;
        }

        MinecraftServer server = helper.getLevel().getServer();
        PlayerList playerList = server.getPlayerList();
        String playerName = "BridgeVirtual";
        UUID uuid = VirtualSessionManager.deterministicTestBotUuid(playerName);

        VirtualClientSession session = NetherNetBridgeMod.SESSIONS.spawnVirtualPlayer(server, playerName);
        final long startNanos = System.nanoTime();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger ticks = new AtomicInteger();
        AtomicInteger cleanupStartTick = new AtomicInteger(-1);
        ConfigProgressTracker configTracker = new ConfigProgressTracker();

        helper.onEachTick(() -> {
            if (finished.get()) {
                return;
            }
            int tick = ticks.incrementAndGet();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            if (cleanupStartTick.get() < 0) {
                if (session.stage() == VirtualClientSession.Stage.DISCONNECTED) {
                    failAndCleanup(helper, finished, "Virtual session disconnected before PLAY: "
                            + session.describeDiagnostics(), session);
                    return;
                }

                ServerPlayer spawned = playerList.getPlayer(uuid);
                if (spawned != null && session.isInPlay()) {
                    if (session.serverConnection().isMemoryConnection()) {
                        failAndCleanup(helper, finished, "VirtualBedrockChannel must NOT be marked isMemoryConnection()", session);
                        return;
                    }
                    int serverThreshold = server.getCompressionThreshold();
                    if (serverThreshold >= 0 && session.compressionThreshold() != serverThreshold) {
                        failAndCleanup(helper, finished, "Expected compression threshold " + serverThreshold
                                + " but observed " + session.compressionThreshold(), session);
                        return;
                    }
                    session.close("NetherNetBridge lifecycle verification complete");
                    cleanupStartTick.set(tick);
                    return;
                }

                String stallReason = configTracker.checkProgress(session, tick);
                if (stallReason != null) {
                    failAndCleanup(helper, finished, stallReason + " (" + elapsedMs + " ms) ("
                            + session.describeDiagnostics() + ")", session);
                    return;
                }

                if (elapsedMs >= 15_000L || tick >= 11_500) {
                    failAndCleanup(helper, finished, "Timed out waiting for virtual ServerPlayer '" + playerName
                            + "' (" + session.describeDiagnostics() + ")", session);
                }
            } else {
                ServerPlayer remaining = playerList.getPlayer(uuid);
                boolean connRemoved = !server.getConnection().getConnections().contains(session.serverConnection());
                if (remaining == null
                        && playerList.getPlayers().isEmpty()
                        && connRemoved
                        && session.stage() == VirtualClientSession.Stage.DISCONNECTED) {
                    if (finished.compareAndSet(false, true)) {
                        helper.succeed();
                    }
                } else if (tick - cleanupStartTick.get() >= 40) {
                    failAndCleanup(helper, finished, "Virtual player '" + playerName
                            + "' was not removed cleanly", session);
                }
            }
        });
    }

    private enum IsolationPhase {
        WAIT_INITIAL_PLAY,
        VERIFY_FAULT1_AND_WAIT_SECOND,
        VERIFY_FAULT2,
        WAIT_CONTROL_CLEANUP
    }

    private static void runMalformedFrameIsolationTest(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerList playerList = server.getPlayerList();

        if (!playerList.getPlayers().isEmpty()) {
            helper.fail("Expected empty PlayerList at start of malformed_frame_isolation, found "
                    + playerList.getPlayers().size() + " leftover player(s)");
            return;
        }

        String controlName = "BridgeControl";
        String faultHdrName = "BridgeFaultHdr";
        String faultIdName = "BridgeFaultId";

        UUID controlUuid = VirtualSessionManager.deterministicTestBotUuid(controlName);
        UUID faultHdrUuid = VirtualSessionManager.deterministicTestBotUuid(faultHdrName);
        UUID faultIdUuid = VirtualSessionManager.deterministicTestBotUuid(faultIdName);

        NameAndId[] isolationProfiles = new NameAndId[]{
                new NameAndId(new GameProfile(controlUuid, controlName)),
                new NameAndId(new GameProfile(faultHdrUuid, faultHdrName)),
                new NameAndId(new GameProfile(faultIdUuid, faultIdName))
        };
        boolean grantedBypass = false;
        if (playerList.getMaxPlayers() < 2) {
            for (NameAndId profile : isolationProfiles) {
                playerList.op(profile, Optional.empty(), Optional.of(Boolean.TRUE));
            }
            grantedBypass = true;
            boolean controlBypass = playerList.canBypassPlayerLimit(isolationProfiles[0]);
            NameAndId unoppedProbe = new NameAndId(new GameProfile(
                    VirtualSessionManager.deterministicTestBotUuid("BridgeUnopped"),
                    "BridgeUnopped"
            ));
            boolean unoppedBypass = playerList.canBypassPlayerLimit(unoppedProbe);
            if (!controlBypass || unoppedBypass) {
                revokeBypassOps(playerList, isolationProfiles);
                helper.fail("Expected bypass=true only for opped isolation profiles");
                return;
            }
        }
        final NameAndId[] profilesToRevoke = grantedBypass ? isolationProfiles : new NameAndId[0];

        VirtualClientSession control = NetherNetBridgeMod.SESSIONS.spawnVirtualPlayer(server, controlName);
        VirtualClientSession faultHdr = NetherNetBridgeMod.SESSIONS.spawnVirtualPlayer(server, faultHdrName);
        AtomicReference<VirtualClientSession> faultIdRef = new AtomicReference<>();

        final long startNanos = System.nanoTime();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger ticks = new AtomicInteger();
        AtomicReference<IsolationPhase> phase = new AtomicReference<>(IsolationPhase.WAIT_INITIAL_PLAY);

        ConfigProgressTracker controlTracker = new ConfigProgressTracker();
        ConfigProgressTracker faultHdrTracker = new ConfigProgressTracker();
        ConfigProgressTracker faultIdTracker = new ConfigProgressTracker();

        AtomicInteger fault1Tick = new AtomicInteger(-1);
        AtomicInteger fault2Tick = new AtomicInteger(-1);
        AtomicInteger cleanupTick = new AtomicInteger(-1);
        AtomicInteger controlTxBaseline = new AtomicInteger();
        AtomicInteger controlRxBaseline = new AtomicInteger();
        AtomicBoolean controlExchange1Verified = new AtomicBoolean();

        helper.onEachTick(() -> {
            if (finished.get()) {
                return;
            }
            int tick = ticks.incrementAndGet();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            switch (phase.get()) {
                case WAIT_INITIAL_PLAY -> {
                    if (control.stage() == VirtualClientSession.Stage.DISCONNECTED) {
                        failAndCleanup(helper, finished, "Control session disconnected before PLAY: "
                                + control.describeDiagnostics(), playerList, profilesToRevoke, control, faultHdr, faultIdRef.get());
                        return;
                    }
                    if (faultHdr.stage() == VirtualClientSession.Stage.DISCONNECTED) {
                        failAndCleanup(helper, finished, "FaultHdr session disconnected before PLAY: "
                                + faultHdr.describeDiagnostics(), playerList, profilesToRevoke, control, faultHdr, faultIdRef.get());
                        return;
                    }

                    ServerPlayer controlPlayer = playerList.getPlayer(controlUuid);
                    ServerPlayer faultHdrPlayer = playerList.getPlayer(faultHdrUuid);

                    if (controlPlayer != null && control.isInPlay()
                            && faultHdrPlayer != null && faultHdr.isInPlay()) {
                        VirtualClientSession.FaultInjectionResult result1 = faultHdr.injectMalformedFrameFault(
                                VirtualClientSession.FaultMode.MALFORMED_COMPRESSION_HEADER
                        );
                        if (!result1.disconnected() || result1.disconnectionDetails() == null) {
                            failAndCleanup(helper, finished, "Fault 1 failed to disconnect",
                                    playerList, profilesToRevoke, control, faultHdr, faultIdRef.get());
                            return;
                        }
                        controlTxBaseline.set(control.packetsToServer());
                        controlRxBaseline.set(control.packetsFromServer());
                        controlPlayer.connection.send(new ClientboundPingPacket(2001));
                        fault1Tick.set(tick);
                        faultIdRef.set(NetherNetBridgeMod.SESSIONS.spawnVirtualPlayer(server, faultIdName));
                        phase.set(IsolationPhase.VERIFY_FAULT1_AND_WAIT_SECOND);
                        return;
                    }

                    String controlStall = controlTracker.checkProgress(control, tick);
                    if (controlStall != null) {
                        failAndCleanup(helper, finished, "Control session " + controlStall,
                                playerList, profilesToRevoke, control, faultHdr, faultIdRef.get());
                        return;
                    }
                    String hdrStall = faultHdrTracker.checkProgress(faultHdr, tick);
                    if (hdrStall != null) {
                        failAndCleanup(helper, finished, "FaultHdr session " + hdrStall,
                                playerList, profilesToRevoke, control, faultHdr, faultIdRef.get());
                        return;
                    }
                }

                case VERIFY_FAULT1_AND_WAIT_SECOND -> {
                    ServerPlayer controlPlayer = playerList.getPlayer(controlUuid);
                    if (controlPlayer == null || !control.isInPlay()) {
                        failAndCleanup(helper, finished, "Control session dropped after Fault 1",
                                playerList, profilesToRevoke, control, faultHdr, faultIdRef.get());
                        return;
                    }
                    int sinceFault1 = tick - fault1Tick.get();
                    if (!controlExchange1Verified.get()) {
                        boolean hdrRemoved = playerList.getPlayer(faultHdrUuid) == null;
                        boolean exchanged = control.packetsFromServer() > controlRxBaseline.get()
                                && control.packetsToServer() > controlTxBaseline.get();
                        if (sinceFault1 >= 5 && hdrRemoved && exchanged) {
                            controlExchange1Verified.set(true);
                        } else if (sinceFault1 >= 40) {
                            failAndCleanup(helper, finished, "Fault 1 post-check failed",
                                    playerList, profilesToRevoke, control, faultHdr, faultIdRef.get());
                            return;
                        }
                    }

                    VirtualClientSession faultId = faultIdRef.get();
                    if (faultId == null) {
                        return;
                    }
                    if (faultId.stage() == VirtualClientSession.Stage.DISCONNECTED) {
                        failAndCleanup(helper, finished, "FaultId session disconnected before PLAY",
                                playerList, profilesToRevoke, control, faultHdr, faultId);
                        return;
                    }
                    if (controlExchange1Verified.get() && playerList.getPlayer(faultIdUuid) != null && faultId.isInPlay()) {
                        VirtualClientSession.FaultInjectionResult result2 = faultId.injectMalformedFrameFault(
                                VirtualClientSession.FaultMode.INVALID_PACKET_ID_COMPRESSED
                        );
                        if (!result2.disconnected() || result2.disconnectionDetails() == null) {
                            failAndCleanup(helper, finished, "Fault 2 failed to disconnect",
                                    playerList, profilesToRevoke, control, faultHdr, faultId);
                            return;
                        }
                        controlTxBaseline.set(control.packetsToServer());
                        controlRxBaseline.set(control.packetsFromServer());
                        controlPlayer.connection.send(new ClientboundPingPacket(2002));
                        fault2Tick.set(tick);
                        phase.set(IsolationPhase.VERIFY_FAULT2);
                        return;
                    }
                    String idStall = faultIdTracker.checkProgress(faultId, sinceFault1);
                    if (idStall != null) {
                        failAndCleanup(helper, finished, "FaultId session " + idStall,
                                playerList, profilesToRevoke, control, faultHdr, faultId);
                        return;
                    }
                }

                case VERIFY_FAULT2 -> {
                    if (playerList.getPlayer(controlUuid) == null || !control.isInPlay()) {
                        failAndCleanup(helper, finished, "Control session dropped after Fault 2",
                                playerList, profilesToRevoke, control, faultHdr, faultIdRef.get());
                        return;
                    }
                    int sinceFault2 = tick - fault2Tick.get();
                    boolean idRemoved = playerList.getPlayer(faultIdUuid) == null;
                    boolean exchanged = control.packetsFromServer() > controlRxBaseline.get()
                            && control.packetsToServer() > controlTxBaseline.get();
                    if (sinceFault2 >= 5 && idRemoved && exchanged) {
                        control.close("NetherNetBridge fault isolation complete");
                        cleanupTick.set(tick);
                        phase.set(IsolationPhase.WAIT_CONTROL_CLEANUP);
                        return;
                    }
                    if (sinceFault2 >= 40) {
                        failAndCleanup(helper, finished, "Fault 2 post-check failed",
                                playerList, profilesToRevoke, control, faultHdr, faultIdRef.get());
                        return;
                    }
                }

                case WAIT_CONTROL_CLEANUP -> {
                    boolean controlConnRemoved = !server.getConnection().getConnections().contains(control.serverConnection());
                    if (playerList.getPlayer(controlUuid) == null
                            && playerList.getPlayers().isEmpty()
                            && controlConnRemoved
                            && control.stage() == VirtualClientSession.Stage.DISCONNECTED) {
                        revokeBypassOps(playerList, profilesToRevoke);
                        if (finished.compareAndSet(false, true)) {
                            helper.succeed();
                        }
                    } else if (tick - cleanupTick.get() >= 40) {
                        failAndCleanup(helper, finished, "Control session was not removed on close",
                                playerList, profilesToRevoke, control, faultHdr, faultIdRef.get());
                    }
                    return;
                }
            }

            if (elapsedMs >= 30_000L || tick >= 11_500) {
                failAndCleanup(helper, finished, "Timed out in malformed_frame_isolation",
                        playerList, profilesToRevoke, control, faultHdr, faultIdRef.get());
            }
        });
    }

    private static void revokeBypassOps(PlayerList playerList, NameAndId[] profiles) {
        for (NameAndId profile : profiles) {
            try {
                playerList.deop(profile);
            } catch (Exception ignored) {
            }
        }
    }

    private static void failAndCleanup(
            GameTestHelper helper,
            AtomicBoolean finished,
            String message,
            VirtualClientSession... sessions
    ) {
        failAndCleanup(helper, finished, message, null, new NameAndId[0], sessions);
    }

    private static void failAndCleanup(
            GameTestHelper helper,
            AtomicBoolean finished,
            String message,
            PlayerList playerList,
            NameAndId[] profilesToRevoke,
            VirtualClientSession... sessions
    ) {
        for (VirtualClientSession session : sessions) {
            if (session != null) {
                session.close("GameTest failure cleanup");
            }
        }
        if (playerList != null && profilesToRevoke.length > 0) {
            revokeBypassOps(playerList, profilesToRevoke);
        }
        if (finished.compareAndSet(false, true)) {
            helper.fail(message);
        }
    }

    private static final class ConfigProgressTracker {
        private int consecutiveJoinWorldTicks;
        private int consecutiveNoTaskConfigTicks;
        private int consecutiveOtherConfigTaskTicks;

        String checkProgress(VirtualClientSession session, int sessionWaitTicks) {
            VirtualClientSession.Stage stage = session.stage();
            if (stage == VirtualClientSession.Stage.PLAY) {
                consecutiveJoinWorldTicks = 0;
                consecutiveNoTaskConfigTicks = 0;
                consecutiveOtherConfigTaskTicks = 0;
                return null;
            }
            if (stage != VirtualClientSession.Stage.CONFIGURATION) {
                return sessionWaitTicks >= 120 ? "stalled before Configuration (stage=" + stage + ")" : null;
            }
            if (session.isWaitingInJoinWorld()) {
                consecutiveNoTaskConfigTicks = 0;
                consecutiveOtherConfigTaskTicks = 0;
                if (++consecutiveJoinWorldTicks > 20) {
                    return "stalled in JoinWorldTask";
                }
                return null;
            }
            consecutiveJoinWorldTicks = 0;
            if (session.isWaitingInPrepareSpawn()) {
                consecutiveNoTaskConfigTicks = 0;
                consecutiveOtherConfigTaskTicks = 0;
                return null;
            }
            if (sessionWaitTicks < 120) {
                return null;
            }
            if (!session.hasActiveConfigurationTask()) {
                consecutiveOtherConfigTaskTicks = 0;
                return ++consecutiveNoTaskConfigTicks > 10 ? "stalled in CONFIGURATION without task" : null;
            }
            consecutiveNoTaskConfigTicks = 0;
            return ++consecutiveOtherConfigTaskTicks > 60 ? "stalled in task " + session.currentConfigurationTaskId() : null;
        }
    }
}
