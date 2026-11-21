package dev.bedrocklanbridge.bridge.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Restores {@code ServerOpList#canBypassPlayerLimit} lookup strictly on {@link GameTestServer}
 * so concurrent control+fault isolation tests work without affecting normal servers or non-op players.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListGameTestMixin {

    @Inject(method = "canBypassPlayerLimit", at = @At("HEAD"), cancellable = true)
    private void nethernetBridge$honourOpBypassOnGameTestServer(
            NameAndId user,
            CallbackInfoReturnable<Boolean> cir
    ) {
        PlayerList self = (PlayerList) (Object) this;
        if (self.getServer() instanceof GameTestServer && self.getOps().canBypassPlayerLimit(user)) {
            cir.setReturnValue(true);
        }
    }
}
