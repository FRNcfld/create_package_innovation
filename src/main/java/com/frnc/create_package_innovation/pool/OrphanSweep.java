package com.frnc.create_package_innovation.pool;

import com.frnc.create_package_innovation.CreatePackageInnovation;
import com.frnc.create_package_innovation.identity.ContainerHintRegistry;
import com.frnc.create_package_innovation.identity.ContainerHintStore;
import com.frnc.create_package_innovation.identity.VaultGeometry;
import com.frnc.create_package_innovation.identity.VaultIdAccessor;
import com.frnc.create_package_innovation.partial.PartialOrderTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Safety net for pooled entries whose container was removed without any drain firing.
 *
 * <p>The normal path is event-driven: {@code LevelChunkRemovalMixin} drains a single-block
 * container when its block is removed, and {@code ConnectivityHandlerMixin} drains a
 * multiblock on full teardown. This sweep only exists for the residual holes (a block
 * removed through some path we do not hook, a save that was killed right after a missed
 * drain, …). Without it those entries would sit in SavedData forever — i.e. the packages and
 * leftover materials would be silently lost rather than dropped back to the player.</p>
 *
 * <h3>Why {@code ChunkEvent.Load} and not server start</h3>
 *
 * <p>Deciding "is the container still there" needs to look at that position in the world,
 * and {@code Level.getBlockEntity} <b>synchronously loads an unloaded chunk</b>. At server
 * start almost every chunk is unloaded, so a startup sweep would either force-load the world
 * or be unable to decide anything. Hooking the chunk load instead means the chunk is already
 * in memory by construction, so every lookup is a plain map access — and each entry gets
 * re-probed the next time its chunk loads, so an undecidable entry simply resolves later.</p>
 *
 * <p>Position hints are persisted ({@link ContainerHintRegistry} backed by
 * {@link ContainerHintStore}) precisely so this sweep also resolves orphans left over from a
 * previous session: the first read after a restart seeds the in-memory map from disk, and
 * every chunk load after that can probe them. The hints live in their own SavedData file, so
 * nothing here touches the pool or tracker format and existing pools keep their keys.</p>
 */
@Mod.EventBusSubscriber(modid = CreatePackageInnovation.MOD_ID)
public final class OrphanSweep {

    private OrphanSweep() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        MinecraftServer server = serverLevel.getServer();
        if (server == null) return;

        Map<UUID, ContainerHintRegistry.Hint> hints = ContainerHintRegistry.hints(server);
        if (hints.isEmpty()) return;

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        List<UUID> orphans = null;

        for (Map.Entry<UUID, ContainerHintRegistry.Hint> entry : hints.entrySet()) {
            UUID key = entry.getKey();
            ContainerHintRegistry.Hint hint = entry.getValue();
            if (!hint.dimension().equals(serverLevel.dimension())) continue;

            BlockPos pos = hint.pos();
            if ((pos.getX() >> 4) != chunkX || (pos.getZ() >> 4) != chunkZ) continue;

            // Cheap verdict first: the chunk is loaded (that is what this event means), so
            // this is a map lookup and can never trigger a chunk load.
            BlockEntity be = chunk.getBlockEntity(pos);
            if (be != null) {
                if (!hint.multiblock()) continue; // position key: slot re-occupied → pool reachable
                if (be instanceof VaultIdAccessor acc && key.equals(acc.createPackageInnovation$getVaultId())) {
                    continue; // the very same multiblock is still here
                }
                // Occupied by something else (e.g. a new container built on the old spot) —
                // fall through to the full probe: a multiblock controller carrying this UUID
                // may still live elsewhere within the scan radius.
            }

            if (hint.multiblock()) {
                VaultGeometry.Liveness liveness = VaultGeometry.multiblockLivenessNear(serverLevel, pos, key);
                if (liveness == VaultGeometry.Liveness.GONE) {
                    // Same reasoning as in ConnectivityHandlerMixin: the radius-based probe is
                    // sized from the vault's geometry and under-covers containers that are
                    // larger than it (Create's fluid tank is config-height, default 32, on a
                    // 3×3 base), which would drop the pool of a container that is still alive.
                    // Follow the container part-by-part instead. Still fully chunk-safe (never
                    // force-loads) and read-only (never writes to a BlockEntity).
                    liveness = VaultGeometry.contiguousLivenessNear(serverLevel, pos, key);
                }
                // ALIVE → still exists (partial break, or re-formed elsewhere).
                // UNKNOWN → a candidate chunk is unloaded; decide on a later chunk load.
                if (liveness != VaultGeometry.Liveness.GONE) continue;
            }

            if (orphans == null) orphans = new ArrayList<>();
            orphans.add(key);
        }

        if (orphans == null) return;

        for (UUID key : orphans) {
            ContainerHintRegistry.Hint hint = hints.get(key);
            if (hint == null) continue;
            BlockPos pos = hint.pos();
            SharedPackagePool.get(server).drainAndDrop(key, serverLevel, pos);
            PartialOrderTracker.get(server).drainAndDrop(key, serverLevel, pos);
            ContainerHintRegistry.forget(server, key);
            // Rare (a missed drain) and worth knowing about: items were recovered rather than
            // staying stranded in SavedData, so this one is logged unconditionally.
            CreatePackageInnovation.LOGGER.info(
                    "[CPI-POOL] orphan sweep dropped a pool whose container is gone (dim={}, pos={})",
                    hint.dimension().location(), pos);
        }
    }
}
