package com.frnc.create_package_innovation.mixin;

import com.frnc.create_package_innovation.identity.ContainerHintRegistry;
import com.frnc.create_package_innovation.identity.VaultIdentity;
import com.frnc.create_package_innovation.partial.PartialOrderTracker;
import com.frnc.create_package_innovation.pool.SharedPackagePool;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Drains the shared pool of a <b>single-block</b> container when that block is actually
 * removed, so pooled packages drop as items instead of being stranded in SavedData.
 *
 * <p>Multiblock containers are NOT handled here — they go through
 * {@code ConnectivityHandlerMixin} on {@code ConnectivityHandler.splitMulti}, which also
 * has to distinguish a partial break (re-formed multiblock inherits the UUID → keep the
 * pool) from a full teardown (drain).</p>
 *
 * <h3>Why {@code LevelChunk.removeBlockEntity} and not {@code BlockEntity.setRemoved}</h3>
 *
 * <p>{@code BlockEntity.setRemoved()} looks like the obvious universal hook, but it is
 * <b>also called on chunk unload</b>: {@code LevelChunk.clearAllBlockEntities()} iterates
 * the block-entity map and calls it on every entry (bytecode-verified against
 * 1.20.1-47.4.10). Draining there would drop pooled packages every time the player walks
 * away from the container — catastrophic.</p>
 *
 * <p>{@code LevelChunk.removeBlockEntity(BlockPos)} is only reached from the
 * block-replacement path ({@code setBlockState} → {@code removeBlockEntity}); the unload
 * path clears the map without calling it. So this hook fires exactly once, for a genuine
 * removal of the block, regardless of how it was removed (break, explosion,
 * {@code /setblock}, world-edit, another mod).</p>
 *
 * <p><b>Remap note:</b> the target is a Minecraft class, so this mixin must stay
 * remappable (no {@code remap = false}) — the generated refmap translates
 * {@code removeBlockEntity} to its production (SRG) name. The {@code remap = false}
 * mixins in this package target <em>Create</em> classes, whose own method names are never
 * obfuscated; that reasoning does not apply to a vanilla target.</p>
 *
 * <p>This mixin lives in the main config ({@code required = true},
 * {@code defaultRequire = 1}) on purpose: if it ever fails to apply, pooling would keep
 * working but nothing would drain it, which silently loses items on container removal.
 * Failing loudly is the correct outcome.</p>
 */
@Mixin(LevelChunk.class)
public class LevelChunkRemovalMixin {

    @Inject(method = "removeBlockEntity(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"))
    private void createPackageInnovation$onContainerRemoved(BlockPos pos, CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide) return;

        // Still present in the chunk's BE map at HEAD (the map removal is this method's
        // first action), so we can inspect it.
        BlockEntity be = self.getBlockEntity(pos);
        if (be == null) return;

        // Multiblock containers are handled by ConnectivityHandlerMixin.splitMulti, which
        // additionally distinguishes partial break from full teardown. Their pool is keyed
        // by UUID, so a position lookup here would be a no-op anyway — this is explicit.
        if (be instanceof IMultiBlockEntityContainer) return;

        // Same-block replacement (e.g. the same container block placed back at the same
        // position): the position key is unchanged, so whatever is pooled stays reachable
        // through the new BlockEntity. Do not drop it.
        if (self.getBlockState(pos).getBlock() == be.getBlockState().getBlock()) return;

        MinecraftServer server = level.getServer();
        if (server == null) return;

        UUID key = VaultIdentity.positionKey(level, pos);
        SharedPackagePool.get(server).drainAndDrop(key, level, pos);
        PartialOrderTracker.get(server).drainAndDrop(key, level, pos);
        // The key is resolved now, so drop its hint from memory AND disk. Without this the
        // persisted hint would linger until some future chunk load probed it and found nothing
        // (it would self-clean then, but only by keeping a dead entry in the save until that
        // happens).
        ContainerHintRegistry.forget(server, key);
    }
}
