package dev.bedrocklanbridge.bridge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.bedrocklanbridge.bridge.gametest.BridgeGameTests;
import dev.bedrocklanbridge.bridge.session.VirtualClientSession;
import dev.bedrocklanbridge.bridge.session.VirtualSessionManager;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Unified NetherNet Bridge entry point on NeoForge 26.3 (Stage 2 skeleton + identity/profile foundation).
 */
@Mod(NetherNetBridgeMod.MODID)
public final class NetherNetBridgeMod {

    public static final String MODID = "nethernet_bridge";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final VirtualSessionManager SESSIONS = new VirtualSessionManager();

    public NetherNetBridgeMod(IEventBus modEventBus) {
        BridgeGameTests.TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(BridgeGameTests::onRegisterGameTests);

        NeoForge.EVENT_BUS.addListener(NetherNetBridgeMod::onServerStarted);
        NeoForge.EVENT_BUS.addListener(NetherNetBridgeMod::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(NetherNetBridgeMod::onServerStopping);
        NeoForge.EVENT_BUS.addListener(NetherNetBridgeMod::onRegisterCommands);

        LOGGER.info("[NetherNetBridge] Initialized {} on NeoForge 26.3", MODID);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        SESSIONS.initializeForServer(server);
        DependencyCompatibilityCheck.verifyRuntime(LOGGER);
        LOGGER.info(
                "[NetherNetBridge] Server started ({}, usesAuth={}, compressionThreshold={})",
                server.getClass().getSimpleName(),
                server.usesAuthentication(),
                server.getCompressionThreshold()
        );
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        SESSIONS.tickAll(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        SESSIONS.shutdownAll();
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("nethernet")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("status").executes(ctx -> {
                            String summary = SESSIONS.describeStatus(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal(summary), false);
                            return 1;
                        }))
                        .then(Commands.literal("checkdeps").executes(ctx -> {
                            DependencyCompatibilityCheck.Report report = DependencyCompatibilityCheck.verifyRuntime(LOGGER);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("[NetherNetBridge] " + report.formatSummary()),
                                    false
                            );
                            return 1;
                        }))
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    MinecraftServer server = ctx.getSource().getServer();
                                    VirtualClientSession session = SESSIONS.spawnVirtualPlayer(server, name);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("[NetherNetBridge] Spawning virtual player '" + name
                                                    + "' (" + session.virtualAddress() + ")"),
                                            false
                                    );
                                    return 1;
                                })))
                        .then(Commands.literal("inspect")
                                .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    MinecraftServer server = ctx.getSource().getServer();
                                    return SESSIONS.findByName(name).map(session -> {
                                        ServerPlayer player = server.getPlayerList().getPlayer(session.profile().id());
                                        String msg = String.format(
                                                "[NetherNetBridge] Virtual '%s' | stage=%s | isMemory=%s | entityId=%d | inWorld=%s | tx=%d rx=%d",
                                                name,
                                                session.stage(),
                                                session.serverConnection().isMemoryConnection(),
                                                session.assignedEntityId(),
                                                player != null,
                                                session.packetsToServer(),
                                                session.packetsFromServer()
                                        );
                                        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                                        return 1;
                                    }).orElseGet(() -> {
                                        ctx.getSource().sendFailure(
                                                Component.literal("[NetherNetBridge] No virtual session named '" + name + "'")
                                        );
                                        return 0;
                                    });
                                })))
                        .then(Commands.literal("fault")
                                .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (SESSIONS.injectFaultByName(name)) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("[NetherNetBridge] Injected malformed frame into '" + name + "'"),
                                                false
                                        );
                                        return 1;
                                    }
                                    ctx.getSource().sendFailure(
                                            Component.literal("[NetherNetBridge] No active virtual session named '" + name + "'")
                                    );
                                    return 0;
                                })))
                        .then(Commands.literal("disconnect")
                                .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (SESSIONS.disconnectByName(name, "Disconnected via /nethernet")) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("[NetherNetBridge] Disconnected virtual player '" + name + "'"),
                                                false
                                        );
                                        return 1;
                                    }
                                    ctx.getSource().sendFailure(
                                            Component.literal("[NetherNetBridge] No active virtual session named '" + name + "'")
                                    );
                                    return 0;
                                })))
        );
    }
}
