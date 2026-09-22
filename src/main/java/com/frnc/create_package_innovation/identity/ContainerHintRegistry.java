package com.frnc.create_package_innovation.identity;

import com.frnc.create_package_innovation.partial.PartialOrderTracker;
import com.frnc.create_package_innovation.pool.OrphanSweep;
import com.frnc.create_package_innovation.pool.SharedPackagePool;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * the in-memory state is seeded from disk, which is what closes the cross-session hole. Without
 * that, a pool orphaned by a missed drain just before a crash could never be resolved — the
 * sweep needs the position to probe, and it had none after a reload.</p>
 *
 * <p>The store is only written when a hint actually appears, moves or changes, so the per-tick
 * {@code remember} calls below do not cause per-tick disk writes.</p>
 *
 * <p><b>Format safety:</b> the hints live in their own SavedData file, and neither
 * {@code SharedPackagePool} nor {@code PartialOrderTracker} changed shape, so an existing
 * world's pooled packages keep their keys across this update.</p>
 *
 * <h3>Chunk-scoped lookup</h3>
 *
 * <p>Besides the key→hint map, each server keeps a second index that buckets keys by
 * (dimension, chunk) of their hint position. {@link OrphanSweep} runs on every chunk load and
 * only ever cares about hints whose position is inside the chunk that just loaded, so it asks
 * for exactly that bucket ({@link #keysInChunk}) instead of walking the whole registry. Walking
 * everything made each chunk load O(total hints), which grows with the number of containers a
 * player has ever attached a machine to.</p>
 *
 * <p>The index is derived state: it is rebuilt from the key→hint map when the in-memory state
 * is seeded from disk, and is never persisted. The on-disk format is unchanged.</p>
 *
 * <h3>Lazy eviction</h3>
 *
 * <p>A hint is only useful while something is still recoverable under its key. Because
 * {@link VaultIdentity} records a hint on <em>every</em> key resolution (i.e. for every
 * container that merely has a machine attached, whether or not it ever holds a package), hints
 * used to be removed only when a drain happened to resolve the key — so a container whose
 * packager was removed, or one that simply finished shipping, kept its hint in memory and on
 * disk forever.</p>
 *
 * <p>{@link OrphanSweep} therefore drops a hint whose key has neither pooled packages nor
 * tracked orders. That is safe by construction: with nothing under the key there is nothing the
 * hint could still rescue, and the next deposit under it records the hint again (the deposit
 * paths resolve the key through {@link VaultIdentity} <em>before</em> depositing).</p>
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

    /**
     * Per-server state: the public key→hint map plus the derived chunk index over it. The two
     * are kept in sync by {@link #remember} and {@link #forget}; nothing else may mutate them.
     */
    private static final class State {
        /** The public key→hint map (what {@link ContainerHintRegistry#hints} exposes). */
        private final Map<UUID, Hint> byKey = new HashMap<>();
        /** dimension → chunkKey → keys whose current hint lies in that chunk. */
        private final Map<ResourceKey<Level>, Map<Long, Set<UUID>>> byChunk = new HashMap<>();
    }

    private static final Map<MinecraftServer, State> BY_SERVER = new WeakHashMap<>();

    private ContainerHintRegistry() {}

    /**
     * Record (or refresh) where {@code key} was just resolved from, in memory and on disk.
     * No-op without a server (i.e. on the client).
     */
    public static void remember(MinecraftServer server, UUID key, Level level, BlockPos pos, boolean multiblock) {
        if (server == null || key == null || level == null || pos == null) return;
        Hint hint = new Hint(level.dimension(), pos.immutable(), multiblock);
        State state = state(server);
        Hint previous = state.byKey.put(key, hint);
        if (hint.equals(previous)) return;
        // The position (and therefore the chunk bucket) is part of the hint: a reshaped
        // multiblock can report a different faced part for the same UUID, so an update may have
        // to move the key to another bucket.
        if (previous != null) unindex(state, key, previous);
        index(state, key, hint);
        ContainerHintStore.get(server).put(key, hint);
    }

    /**
     * Live view — read-only from the sweep's perspective; never null. First access for a
     * server seeds it from disk (see {@link ContainerHintStore}).
     *
     * <p>Note for callers that poll this per event: iterating the whole map is O(total hints).
     * The chunk-load sweep uses {@link #keysInChunk} instead.</p>
     */
    public static Map<UUID, Hint> hints(MinecraftServer server) {
        if (server == null) return Map.of();
        return state(server).byKey;
    }

    /** Hint for one key, or null if it is unknown (or already evicted). */
    public static Hint hint(MinecraftServer server, UUID key) {
        if (server == null || key == null) return null;
        return state(server).byKey.get(key);
    }

    /**
     * Keys whose hint position lies in the given (dimension, chunk), as a snapshot the caller
     * may mutate the registry while walking. Empty when nothing is registered there — which is
     * the common case and the whole point of the index.
     */
    public static List<UUID> keysInChunk(MinecraftServer server, ResourceKey<Level> dimension,
                                         int chunkX, int chunkZ) {
        if (server == null || dimension == null) return List.of();
        Map<Long, Set<UUID>> chunks = state(server).byChunk.get(dimension);
        if (chunks == null) return List.of();
        Set<UUID> bucket = chunks.get(chunkKey(chunkX, chunkZ));
        if (bucket == null || bucket.isEmpty()) return List.of();
        // Copy: the sweep may forget keys (including evicting dead ones) while it walks them.
        return new ArrayList<>(bucket);
    }

    /** Drop a key because its pool has been resolved (drained, or nothing left to guard). */
    public static void forget(MinecraftServer server, UUID key) {
        if (server == null || key == null) return;
        State state = state(server);
        Hint previous = state.byKey.remove(key);
        if (previous != null) unindex(state, key, previous);
        ContainerHintStore.get(server).remove(key);
    }

    // ---------------------------------------------------------------------------
    // Chunk index maintenance
    // ---------------------------------------------------------------------------

    /**
     * Get (seeding from disk on first access) the state for a server. Seeding also builds the
     * chunk index, so the index is complete before any lookup can happen.
     */
    private static State state(MinecraftServer server) {
        return BY_SERVER.computeIfAbsent(server, s -> {
            State state = new State();
            for (Map.Entry<UUID, Hint> entry : ContainerHintStore.get(s).all().entrySet()) {
                state.byKey.put(entry.getKey(), entry.getValue());
                index(state, entry.getKey(), entry.getValue());
            }
            return state;
        });
    }

    /**
     * Pack a chunk position into a long. Deliberately hand-rolled instead of
     * {@code ChunkPos.asLong} so the layout is explicit here: high 32 bits = chunk x (signed),
     * low 32 bits = chunk z. Chunk coordinates are small, so this is collision-free in
     * practice, and it matches {@code ChunkPos#asLong} anyway.
     */
    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /** Chunk of a block position (the very shift {@code ChunkPos} uses). */
    private static long chunkKey(BlockPos pos) {
        return chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static void index(State state, UUID key, Hint hint) {
        state.byChunk
                .computeIfAbsent(hint.dimension(), d -> new HashMap<>())
                .computeIfAbsent(chunkKey(hint.pos()), c -> new HashSet<>())
                .add(key);
    }

    private static void unindex(State state, UUID key, Hint hint) {
        Map<Long, Set<UUID>> chunks = state.byChunk.get(hint.dimension());
        if (chunks == null) return;
        long chunk = chunkKey(hint.pos());
        Set<UUID> bucket = chunks.get(chunk);
        if (bucket == null) return;
        bucket.remove(key);
        if (bucket.isEmpty()) chunks.remove(chunk);
        if (chunks.isEmpty()) state.byChunk.remove(hint.dimension());
    }
}
