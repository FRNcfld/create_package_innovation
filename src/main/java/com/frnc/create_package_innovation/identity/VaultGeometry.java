package com.frnc.create_package_innovation.identity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Identity helpers for pooled container multiblocks. Lives outside mixin classes per
 * §3.9⑤ (mixin classes can't host static helpers).
 */
public final class VaultGeometry {

    /**
     * Maximum container dimension in blocks along any axis, used as the neighborhood
     * scan radius. Create's vault is 3×3×3 max (radius up to 3, length up to 9 — see
     * {@code getMaxWidth() == 3} and {@code getMaxLength() == 3*3}); Create: Connected's
     * Item Silo uses the same limits ({@code getMaxWidth() == 3},
     * {@code getMaxLength(radius) == radius*3}) along its vertical (Y) axis. So a
     * container never extends more than 9 blocks from its controller along its long axis
     * and never more than 3 in either cross axis. We use 11 as a safe
     * over-approximation.
     */
    private static final int MAX_CONTAINER_RADIUS = 11;

    private VaultGeometry() {}

    /**
     * Is there any non-removed supported container BE near {@code origin} whose mixed-in
     * UUID ({@link VaultIdAccessor}) equals {@code vaultId}? Used to distinguish "the
     * whole multiblock is gone" (drain the pool) from "this was a partial break, the
     * remaining parts re-formed and inherited the same UUID" (keep the pool).
     *
     * <p>"Supported container" means any BlockEntity whose class we have an adapter mixin
     * for (Create's vault, Create: Connected's Item Silo, …). The test is purely
     * {@code instanceof VaultIdAccessor}, so adding another adapter needs no change here.
     *
     * <p><b>Why a neighborhood scan and not getControllerBE?</b> During teardown
     * {@code getControllerBE()} is the §3.9③ footgun — a broken part's controller
     * field may already point at itself, so its {@code getWidth()/getHeight()}
     * return 1, and a bounding-box scan based on those would miss the real
     * multiblock geometry entirely. Instead we just enumerate a cube of positions
     * around the broken part and ask the level what's at each. Container parts are
     * always physically contiguous, so any surviving part of the same multiblock
     * is within {@code MAX_CONTAINER_RADIUS} of any other part.
     *
     * <p>The broken block has already been removed from the chunk by the time our
     * {@code splitMulti} hook runs (vanilla {@code onRemove} order:
     * dropContents → removeBlockEntity → splitMulti), so it won't be counted.
     * We only need ONE surviving BE with the same UUID to know the multiblock
     * (or a re-formed remnant) is still alive.</p>
     */
    public static boolean anySiblingVaultWithUuidExists(Level level, BlockPos origin, java.util.UUID vaultId) {
        int r = MAX_CONTAINER_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockEntity be = level.getBlockEntity(p);
                    if (!(be instanceof VaultIdAccessor acc)) continue;
                    if (be.isRemoved()) continue;
                    java.util.UUID id = acc.createPackageInnovation$getVaultId();
                    if (vaultId.equals(id)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Last-resort hardening for the "propagation was skipped" hole in the TAIL approach.
     *
     * <p>{@code ConnectivityHandler.splitMultiAndInvalidate} returns early when its
     * {@code getControllerBE()} (or level) guard fails, and in that case it never pushes the
     * old controller's extraData onto the surviving parts. Those parts keep a null UUID, so
     * {@link #anySiblingVaultWithUuidExists} cannot see them and a merely partially-broken
     * multiblock would be treated as fully torn down — its pool dropped. (Hard to hit in
     * normal play: it needs the controller BE to be unresolvable, e.g. its chunk unloaded.)</p>
     *
     * <p>This looks at the <b>immediate neighbours only</b> ({@code ±1}) and adopts the UUID
     * onto any live container part of the same block that has none. Two deliberate choices:</p>
     * <ul>
     *   <li><b>±1, not the full scan radius.</b> Multiblocks are contiguous, so after removing
     *       one block any surviving part of the same multiblock is adjacent to it — a smaller
     *       radius is sufficient. It also keeps us away from an <em>unrelated</em> container of
     *       the same block type sitting further away (Create merges adjacent same-kind
     *       containers into one multiblock, so anything not adjacent is a different container
     *       and must not inherit our UUID).</li>
     *   <li><b>Adopt, don't just peek.</b> Writing the UUID onto the survivors keeps the pool
     *       key stable across the re-form; merely detecting them would leave them to mint a
     *       fresh UUID later, stranding the pool under the old key.</li>
     * </ul>
     *
     * @return true if at least one surviving part was found — the caller must then NOT drain.
     */
    public static boolean adoptUuidOnSurvivingParts(Level level, BlockPos origin, Block block, java.util.UUID vaultId) {
        boolean found = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockEntity be = level.getBlockEntity(p);
                    if (be == null || be.isRemoved()) continue;
                    if (be.getBlockState().getBlock() != block) continue;
                    if (!(be instanceof VaultIdAccessor acc)) continue;
                    found = true;
                    if (acc.createPackageInnovation$getVaultId() == null) {
                        acc.createPackageInnovation$setVaultId(vaultId);
                        be.setChanged();
                    }
                }
            }
        }
        return found;
    }

    // ---------------------------------------------------------------------------
    // Structural (contiguity-based) traversal
    //
    // MAX_CONTAINER_RADIUS above is derived from the VAULT's geometry (max 3×3×9). That is
    // fine for vaults and silos, but NOT for every supported container: Create's fluid tank
    // takes its height from the `fluidTankMaxHeight` server config (default 32) on top of a
    // 3×3 base, so a tank can legally be ~288 parts and far larger than any fixed radius.
    // A tall tank broken part-way up would then have every surviving part outside the scan
    // cube → "fully gone" → a live container's pool dropped.
    //
    // The two methods below answer the same two questions, but by following the container
    // part-by-part instead of scanning a fixed cube, so they are independent of both
    // geometry and config. They are only ever consulted AFTER the radius-based checks said
    // "gone", so they can only ever prevent a wrong drain — never cause one.
    //
    // Traversal is face-contiguous (never diagonal): multiblock parts are always joined
    // face-to-face, so a diagonal neighbour of the same block is a DIFFERENT container and
    // must not inherit our UUID. (The ±1 cube in adoptUuidOnSurvivingParts does include
    // diagonals; it is kept for exactly the cases it already handled, but this walk is the
    // precise version.)
    // ---------------------------------------------------------------------------

    /**
     * Safety valve for the walks below. The largest legal supported container is Create's
     * fluid tank at its configurable maximum (~3×3×32 ≈ 288 parts), so 4096 positions is
     * generous while still guaranteeing termination on corrupted or unexpected data.
     */
    private static final int MAX_WALK_POSITIONS = 4096;

    /** The six face-neighbours. */
    private static final Direction[] SIDES = Direction.values();

    /**
     * Structural counterpart to {@link #anySiblingVaultWithUuidExists}: is there any live
     * part of the same container reachable from {@code origin} by walking face-adjacent
     * same-block parts?
     *
     * <p>The walk starts at {@code origin}'s six neighbours, because {@code origin} is the
     * block that was just removed (so it is no longer a part). Every surviving part of a
     * broken multiblock is face-adjacent to the removed block, and the remaining parts are
     * connected to each other through it, so seeding with the neighbours reaches the whole
     * surviving structure — including a container that the removal split into two remnants.</p>
     *
     * <p>Semantics deliberately match {@code adoptUuidOnSurvivingParts} for the cases that
     * method already covered, and only extend them:</p>
     * <ul>
     *   <li>Any live same-block {@link VaultIdAccessor} part counts as "found" (→ do not
     *       drain), even if it carries a different UUID. That is the conservative answer and
     *       preserves the old ±1 behaviour.</li>
     *   <li>The walk only continues <em>through</em> parts that carry our UUID or no UUID at
     *       all, so it can never wander into an unrelated container and adopt onto it.</li>
     *   <li>With {@code adopt}, a part carrying no UUID has ours written onto it. Without that,
     *       the re-formed multiblock would mint a fresh UUID and strand the pool under the old
     *       key. Containers whose extraData channel is already occupied by Create (fluid tanks
     *       carry a {@code Boolean} window flag there) never receive the UUID by propagation at
     *       all, which makes this adopt step their ONLY route to reshape-safety.</li>
     * </ul>
     *
     * @return true if at least one surviving part was found — the caller must then NOT drain.
     */
    public static boolean anyContiguousPartNearby(Level level, BlockPos origin, Block block,
                                                  java.util.UUID vaultId, boolean adopt) {
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        seen.add(origin);
        for (Direction side : SIDES) queue.addLast(origin.relative(side));

        boolean found = false;
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_WALK_POSITIONS) {
            BlockPos p = queue.pollFirst();
            if (!seen.add(p)) continue;
            visited++;

            BlockEntity be = level.getBlockEntity(p);
            if (be == null || be.isRemoved()) continue;
            if (!(be instanceof VaultIdAccessor acc)) continue;
            // Same-block only: adopting our UUID onto a neighbouring container OF ANOTHER
            // kind would make two containers share one pool key.
            if (be.getBlockState().getBlock() != block) continue;

            found = true;
            java.util.UUID other = acc.createPackageInnovation$getVaultId();
            boolean ours = vaultId.equals(other);
            // A different container (already has a UUID that is not ours): count it as a
            // surviving part (conservative, as before) but do not walk into or write to it.
            if (other != null && !ours) continue;
            if (other == null && adopt) {
                acc.createPackageInnovation$setVaultId(vaultId);
                be.setChanged();
            }
            for (Direction side : SIDES) queue.addLast(p.relative(side));
        }
        return found;
    }

    /**
     * Chunk-safe structural counterpart to {@link #multiblockLivenessNear}: does any live
     * BlockEntity carrying {@code vaultId} remain reachable by walking face-adjacent
     * supported-container parts from {@code origin}?
     *
     * <p>Unlike {@link #anyContiguousPartNearby} this never forces a chunk load — it resolves
     * every position through {@code ChunkSource.getChunkNow}, and it never writes to a BE
     * (a background sweep must not mutate the world). No same-block filter is needed: a UUID
     * match is already unambiguous, since only our own container can carry our key.</p>
     *
     * <p>Seeding matches {@link #anyContiguousPartNearby} (the six neighbours of
     * {@code origin}). That matters here: when the very block the packager was attached to is
     * the piece that got removed, the hint position itself is no longer a part, and only the
     * neighbour seeding lets the surviving parts be found instead of reporting GONE.</p>
     */
    public static Liveness contiguousLivenessNear(Level level, BlockPos origin, java.util.UUID vaultId) {
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        seen.add(origin);
        for (Direction side : SIDES) queue.addLast(origin.relative(side));

        boolean unknown = false;
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_WALK_POSITIONS) {
            BlockPos p = queue.pollFirst();
            visited++;

            LevelChunk chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
            if (chunk == null) {
                // Cannot see this position (and therefore cannot see what it connects to).
                unknown = true;
                continue;
            }
            BlockEntity be = chunk.getBlockEntity(p);
            if (be == null || be.isRemoved()) continue;
            if (!(be instanceof VaultIdAccessor acc)) continue;
            if (vaultId.equals(acc.createPackageInnovation$getVaultId())) return Liveness.ALIVE;
            // A part of some OTHER container (or of ours with no UUID yet): walk through it, so
            // that a re-formed multiblock whose controller is further away is still reached.
            for (Direction side : SIDES) {
                BlockPos n = p.relative(side);
                if (seen.add(n)) queue.addLast(n);
            }
        }
        return unknown ? Liveness.UNKNOWN : Liveness.GONE;
    }

    /** Tri-state result of a chunk-safe liveness probe (see {@link #multiblockLivenessNear}). */
    public enum Liveness {
        /** A live container BlockEntity carrying the key was found. */
        ALIVE,
        /** Every candidate chunk was loaded and no container carrying the key remains. */
        GONE,
        /** At least one candidate chunk was unloaded, so nothing can be concluded yet. */
        UNKNOWN
    }

    /**
     * Chunk-safe liveness probe for a <b>multiblock</b> key: is any live container
     * BlockEntity carrying {@code vaultId} still present within the scan radius?
     *
     * <p>Unlike {@link #anySiblingVaultWithUuidExists}, this never forces a chunk load.
     * {@code Level.getBlockEntity} would synchronously load an unloaded chunk — a performance
     * hazard, and wrong for a background sweep. So the scan walks the (at most four) chunks
     * covering the box and resolves each through {@code ChunkSource.getChunkNow}, which is a
     * map lookup returning null for an unloaded chunk. If any of those chunks is unloaded the
     * answer is {@link Liveness#UNKNOWN} and the caller retries on a later chunk load.</p>
     */
    public static Liveness multiblockLivenessNear(Level level, BlockPos origin, java.util.UUID vaultId) {
        int r = MAX_CONTAINER_RADIUS;
        int minX = origin.getX() - r, maxX = origin.getX() + r;
        int minZ = origin.getZ() - r, maxZ = origin.getZ() + r;
        boolean unknown = false;

        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    unknown = true;
                    continue;
                }
                int x0 = Math.max(minX, cx << 4), x1 = Math.min(maxX, (cx << 4) + 15);
                int z0 = Math.max(minZ, cz << 4), z1 = Math.min(maxZ, (cz << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int dy = -r; dy <= r; dy++) {
                            BlockEntity be = chunk.getBlockEntity(new BlockPos(x, origin.getY() + dy, z));
                            if (be == null || be.isRemoved()) continue;
                            if (!(be instanceof VaultIdAccessor acc)) continue;
                            if (vaultId.equals(acc.createPackageInnovation$getVaultId())) return Liveness.ALIVE;
                        }
                    }
                }
            }
        }
        return unknown ? Liveness.UNKNOWN : Liveness.GONE;
    }
}
