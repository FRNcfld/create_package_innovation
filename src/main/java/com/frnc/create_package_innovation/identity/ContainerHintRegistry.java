package com.frnc.create_package_innovation.identity;

import com.frnc.create_package_innovation.partial.PartialOrderTracker;
import com.frnc.create_package_innovation.pool.OrphanSweep;
import com.frnc.create_package_innovation.pool.SharedPackagePool;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Registry of "where did this pool key last live", used by {@link OrphanSweep} to find pooled
 * entries whose container was removed without our drain hook firing.
 *
 * <h3>Memory + disk</h3>
 *
 * <p>The map here is the fast path (rewritten on every machine tick). It is backed by
 * {@link ContainerHintStore}, so the hints survive a restart: on the first read for a server
 * the in-memory map is seeded from disk, which is what closes the cross-session hole. Without
 * that, a pool orphaned by a missed drain just before a crash could never be resolved — the
 * sweep needs the position to probe, and it had none after a reload.</p>
 *
 * <p>The store is only written when a hint actually appears or changes, so the per-tick
 * {@code remember} calls below do not cause per-tick disk writes.</p>
 *
 * <p><b>Format safety:</b> the hints live in their own SavedData file, and neither
 * {@code SharedPackagePool} nor {@code PartialOrderTracker} changed shape, so an existing
 * world's pooled packages keep their keys across this update.</p>
 *
 * <h3>Threading</h3>
 *
 * <p>Written from {@link VaultIdentity} (which bails out when {@code level.getServer()} is
 * null, i.e. never on the client) and read by the chunk-load sweep — both on the server
 * thread. The outer map is weak-keyed on the server so switching worlds does not leak.</p>
 */
public final class ContainerHintRegistry {

    /**
     * Where a key was last seen, and how to test whether it is still alive:
     * {@code multiblock == true} means the key is a multiblock controller UUID (a surviving
     * part anywhere nearby keeps it alive), {@code false} means it is a position key (the
     * key <em>is</em> the position, so a BlockEntity back in that slot keeps it alive).
     */
    public record Hint(ResourceKey<Level> dimension, BlockPos pos, boolean multiblock) {}

    private static final Map<MinecraftServer, Map<UUID, Hint>> BY_SERVER = new WeakHashMap<>();

    private ContainerHintRegistry() {}

    /**
     * Record (or refresh) where {@code key} was just resolved from, in memory and on disk.
     * No-op without a server (i.e. on the client).
     */
    public static void remember(MinecraftServer server, UUID key, Level level, BlockPos pos, boolean multiblock) {
        if (server == null || key == null || level == null || pos == null) return;
        Hint hint = new Hint(level.dimension(), pos.immutable(), multiblock);
        Hint previous = hints(server).put(key, hint);
        if (!hint.equals(previous)) ContainerHintStore.get(server).put(key, hint);
    }

    /**
     * Live view — read-only from the sweep's perspective; never null. First access for a
     * server seeds it from disk (see {@link ContainerHintStore}).
     */
    public static Map<UUID, Hint> hints(MinecraftServer server) {
        if (server == null) return Map.of();
        return BY_SERVER.computeIfAbsent(server, s -> new HashMap<>(ContainerHintStore.get(s).all()));
    }

    /** Drop a key because its pool has been resolved (drained). Clears memory and disk. */
    public static void forget(MinecraftServer server, UUID key) {
        if (server == null || key == null) return;
        Map<UUID, Hint> map = BY_SERVER.get(server);
        if (map != null) map.remove(key);
        ContainerHintStore.get(server).remove(key);
    }
}
