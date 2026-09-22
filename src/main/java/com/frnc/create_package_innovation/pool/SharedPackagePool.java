package com.frnc.create_package_innovation.pool;

import com.frnc.create_package_innovation.CreatePackageInnovation;
import com.frnc.create_package_innovation.partial.PartialOrderTracker;
import com.simibubi.create.content.logistics.BigItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-vault shared package pool, stored as world-level SavedData and keyed by the
 * vault's stable {@link UUID} (see TECHNICAL.md §3.11).
 *
 * <h3>Architecture (0.4.0 + 0.5.1 identity fix)</h3>
 *
 * Repackaged batches go here instead of into each repackager's private
 * {@code queuedExitingPackages}. Idle machines poll from the pool each tick (see
 * {@code PackagerBlockEntityMixin}).
 *
 * <p><b>0.5.1 change:</b> the key was a {@link net.minecraft.world.level.levelgen.structure.BoundingBox}
 * in 0.4.0–0.5.0, which was geometry-derived and raced during reshape (different
 * repackagers saw different BoundingBoxes from vanilla's lazily-recomputed
 * {@code InventoryIdentifier.Bounds}). The key is now the vault's UUID, which is
 * geometry-independent and propagated through Create's extraData trio — reshape can no
 * longer split or duplicate the pool.</p>
 *
 * <h3>Routing by producer kind (§3.21)</h3>
 *
 * Each entry remembers whether a <b>理包机</b> (repackager) or a <b>打包机</b> (packager)
 * produced it, and a machine only ships entries of its own kind ({@link Origin#shippableBy}).
 * Without this the pool was purely container-keyed, so a packager attached to the same vault
 * would take the ordered packages a repackager had produced and send them out of <em>its</em>
 * output face. Parallelism is preserved where it matters: machines of the same kind still share
 * one queue (N repackagers ≈ N packages/second).
 *
 * <p>Entries written before the origin tag existed load as {@link Origin#UNKNOWN} and stay
 * shippable by anyone, so an old save behaves exactly as before and nothing is stranded.</p>
 *
 * <h3>Lifetime model (vault-centric)</h3>
 *
 * The pool is independent of any BlockEntity. Breaking a repackager does NOT drop the
 * pool (it survives in SavedData; place the repackager back and it resumes). Only when
 * the vault itself is destroyed does {@code ConnectivityHandlerMixin} drain the pool
 * and drop it as items. Reshape does <b>not</b> drop — the UUID key is unchanged.
 *
 * <p>Consequence of the routing rule, worth knowing: if a container has no machine of the kind
 * that produced some entries, those entries wait (they are neither shipped nor lost) until such a
 * machine is placed again, or until the container is destroyed and they drop as items.</p>
 *
 * <h3>count semantics</h3>
 *
 * A {@link BigItemStack}'s {@code count} is how many times that package ships, not how
 * many entries. {@code poll()} pops one shipment unit at a time (splitting the head
 * entry when {@code count > 1}); {@code deposit()} appends whole entries. See
 * TECHNICAL.md §3.5.
 *
 * <h3>Two-vault merge support</h3>
 *
 * {@link #noteMergeParticipant(UUID)} and {@link #resolveMergeWinner} implement the
 * "loser pool migrates to winner UUID" rule for the rare case of two independent vaults
 * fusing into one. See TECHNICAL.md §3.11 "Two-vault merge" subsection.
 */
public class SharedPackagePool extends SavedData {

    private static final String DATA_ID = "gdr_shared_package_pool";

    /**
     * NBT key for the per-entry origin tag. This is a <b>new</b> key: adding keys is fine, whereas
     * the persisted keys listed in AGENTS.md §4 must never be renamed. Absent on entries written
     * before routing existed → they load as {@link Origin#UNKNOWN} (shippable by anyone).
     */
    private static final String ORIGIN_KEY = "CpiOrigin";

    /**
     * Which kind of machine produced a pooled package; see the class javadoc.
     *
     * <p>The codes are <b>persisted</b> — never reorder or reuse them, and never change
     * {@link Origin#UNKNOWN}'s meaning (it is the "no tag" value that keeps old saves shippable).</p>
     */
    public enum Origin {
        /** No tag: written before routing existed. Shippable by any machine (back-compat). */
        UNKNOWN(0),
        /** Produced by a 理包机 — {@code RepackagerLike} (Create's repackager and its variants). */
        REPACKAGER(1),
        /** Produced by a 打包机 — any other {@code PackagerBlockEntity}. */
        PACKAGER(2);

        private final byte code;

        Origin(int code) {
            this.code = (byte) code;
        }

        byte code() {
            return code;
        }

        static Origin fromCode(byte code) {
            for (Origin origin : values()) if (origin.code == code) return origin;
            return UNKNOWN;
        }

        /** May a machine that produces {@code requester} ship an entry of this origin? */
        boolean shippableBy(Origin requester) {
            return this == UNKNOWN || this == requester;
        }
    }

    /** One pooled package plus the kind of machine that produced it. */
    private record Entry(BigItemStack stack, Origin origin) {}

    private final Map<UUID, Deque<Entry>> pools = new HashMap<>();

    /**
     * Transient merge-participant set: every UUID seen via
     * {@code ItemVaultBlockEntityMixin.setExtraData} during an in-flight reshape/merge.
     * Cleared by the new controller's {@code notifyMultiUpdated} after resolving the
     * merge (single-vault reshape = no-op, two-vault merge = migrate losers). Not
     * persisted. Server-thread-only access — no synchronization needed.
     */
    private final Set<UUID> mergeParticipants = new HashSet<>();

    public SharedPackagePool() {}

    /** Load from NBT. Detects the legacy BoundingBox-keyed format and clears on upgrade. */
    public static SharedPackagePool load(CompoundTag root) {
        SharedPackagePool pool = new SharedPackagePool();
        ListTag vaultList = root.getList("Vaults", Tag.TAG_COMPOUND);
        if (vaultList.isEmpty()) return pool;

        // Legacy-format probe: 0.5.0 wrote MinX/MaxX/etc per vault entry; 0.5.1 writes VaultId.
        CompoundTag sample = vaultList.getCompound(0);
        if (sample.contains("MinX")) {
            // Legacy BoundingBox-keyed data from 0.5.0. User-visible alpha break: we drop
            // these on upgrade (changelog calls this out). In-flight packages are lost;
            // players should let active orders finish before updating.
            int lostVaults = vaultList.size();
            int lostPackages = 0;
            for (int i = 0; i < vaultList.size(); i++) {
                lostPackages += vaultList.getCompound(i).getList("Packages", Tag.TAG_COMPOUND).size();
            }
            CreatePackageInnovation.LOGGER.warn(
                    "[CPI-POOL] detected legacy BoundingBox-keyed SavedData ({} vault(s), ~{} package(s)); "
                            + "clearing pool on upgrade to 0.5.1 (alpha break — see changelog)",
                    lostVaults, lostPackages);
            return pool;
        }

        // 0.5.1+ UUID-keyed format. Entries may or may not carry the origin tag (routing added
        // later); a missing tag means UNKNOWN, which behaves exactly like the pre-routing pool.
        for (int i = 0; i < vaultList.size(); i++) {
            CompoundTag vaultEntry = vaultList.getCompound(i);
            if (!vaultEntry.hasUUID("VaultId")) continue;
            UUID id = vaultEntry.getUUID("VaultId");
            ListTag items = vaultEntry.getList("Packages", Tag.TAG_COMPOUND);
            Deque<Entry> deque = new ArrayDeque<>();
            for (int j = 0; j < items.size(); j++) {
                CompoundTag itemTag = items.getCompound(j);
                Origin origin = itemTag.contains(ORIGIN_KEY)
                        ? Origin.fromCode(itemTag.getByte(ORIGIN_KEY))
                        : Origin.UNKNOWN;
                deque.addLast(new Entry(BigItemStack.read(itemTag), origin));
            }
            if (!deque.isEmpty()) pool.pools.put(id, deque);
        }
        return pool;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag vaultList = new ListTag();
        for (Map.Entry<UUID, Deque<Entry>> e : pools.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag vaultEntry = new CompoundTag();
            vaultEntry.putUUID("VaultId", e.getKey());
            ListTag items = new ListTag();
            for (Entry entry : e.getValue()) {
                CompoundTag itemTag = entry.stack().write();
                itemTag.putByte(ORIGIN_KEY, entry.origin().code());
                items.add(itemTag);
            }
            vaultEntry.put("Packages", items);
            vaultList.add(vaultEntry);
        }
        root.put("Vaults", vaultList);
        return root;
    }

    public static SharedPackagePool get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                SharedPackagePool::load, SharedPackagePool::new, DATA_ID);
    }

    /**
     * A machine of kind {@code origin} deposits a whole batch (to tail, FIFO preserved). Only
     * {@code count > 0} entries are accepted — the last gate against conjuring items (§3.10④).
     */
    public void deposit(UUID vault, List<BigItemStack> batch, Origin origin) {
        if (batch.isEmpty()) return;
        Deque<Entry> deque = pools.computeIfAbsent(vault, k -> new ArrayDeque<>());
        for (BigItemStack bis : batch) if (bis.count > 0) deque.addLast(new Entry(bis, origin));
        setDirty();
    }

    /**
     * Poll one shipment unit for a machine of kind {@code requester}. Returns null if nothing this
     * machine is allowed to ship is queued.
     *
     * <p>Routing is class-scoped (§3.21): entries produced by the other kind of machine are skipped
     * rather than taken, so a 打包机 cannot carry off the ordered packages a 理包机 produced (nor
     * the reverse). {@link Origin#UNKNOWN} entries — written before routing existed — are shippable
     * by anyone, which keeps old saves working. If the entry has count&gt;1, one unit is split off
     * and the remainder stays put.</p>
     */
    public BigItemStack poll(UUID vault, Origin requester) {
        Deque<Entry> deque = pools.get(vault);
        if (deque == null || deque.isEmpty()) return null;
        for (Iterator<Entry> it = deque.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (!entry.origin().shippableBy(requester)) continue;
            BigItemStack head = entry.stack();
            if (head.count <= 1) {
                it.remove();
                if (deque.isEmpty()) pools.remove(vault);
                setDirty();
                if (head.count <= 0) {
                    // Defensive: a zero-count entry should never exist, but skip it safely and keep
                    // looking for something this machine may ship.
                    continue;
                }
                return head;
            }
            head.count--;
            setDirty();
            return new BigItemStack(head.stack.copy(), 1);
        }
        return null;
    }

    /** Total number of packages waiting under this key, counting every {@link Origin}. */
    public int pending(UUID vault) {
        Deque<Entry> deque = pools.get(vault);
        if (deque == null) return 0;
        int sum = 0;
        for (Entry entry : deque) sum += Math.max(0, entry.stack().count);
        return sum;
    }

    /**
     * Vault destroyed: drain this vault's pool and drop as item entities. Dropping is
     * origin-agnostic on purpose — when the container is gone there is no machine left to
     * route to, and the packages must reach the player. Idempotent — safe if the pool was
     * already drained or never existed.
     */
    public void drainAndDrop(UUID vault, Level level, BlockPos pos) {
        Deque<Entry> deque = pools.remove(vault);
        if (deque == null || deque.isEmpty()) return;
        Vec3 dropPos = Vec3.atCenterOf(pos);
        int dropped = 0;
        for (Entry entry : deque) {
            BigItemStack bis = entry.stack();
            int n = Math.max(0, bis.count);
            for (int i = 0; i < n; i++) {
                ItemStack stack = bis.stack.copy();
                if (!stack.isEmpty()) {
                    ItemEntity entity = new ItemEntity(level, dropPos.x, dropPos.y + 0.5, dropPos.z, stack);
                    entity.setDefaultPickUpDelay();
                    level.addFreshEntity(entity);
                    dropped++;
                }
            }
        }
        deque.clear();
        setDirty();
        if (dropped > 0 && CreatePackageInnovation.DEBUG_LOGGING) {
            CreatePackageInnovation.LOGGER.info(
                    "[CPI-POOL] drained & dropped {} package(s) at vault {}", dropped, pos);
        }
    }

    // ---------------------------------------------------------------------------
    // Two-vault merge support. See TECHNICAL.md §3.11 "Two-vault merge".
    // ---------------------------------------------------------------------------

    /**
     * Called from {@code ItemVaultBlockEntityMixin.setExtraData} each time a vault BE
     * receives a UUID via Create's extraData channel during an in-flight split/reform.
     * Single-vault reshape registers the same UUID many times (idempotent); two-vault
     * merge registers both UUIDs.
     */
    public void noteMergeParticipant(UUID id) {
        if (id != null) mergeParticipants.add(id);
    }

    /**
     * Called from the new controller's {@code notifyMultiUpdated}. If two or more
     * distinct UUIDs are pending, this is a two-vault merge: pick the deterministic
     * winner (smallest UUID), migrate every other participant's pool entries to the
     * winner, and tell the caller (which will adopt the winning UUID). Returns null
     * for single-vault reshape (no merge in flight, or only one participant).
     *
     * @param tracker same lifecycle tracker, migrated in lockstep with this pool.
     * @return the winning UUID if a merge was resolved, null otherwise.
     */
    public UUID resolveMergeWinner(PartialOrderTracker tracker) {
        if (mergeParticipants.size() < 2) {
            mergeParticipants.clear();
            return null;
        }
        UUID winner = Collections.min(mergeParticipants);
        int migrated = 0;
        for (UUID loser : new ArrayList<>(mergeParticipants)) {
            if (loser.equals(winner)) continue;
            migrated += migrateKey(loser, winner);
            tracker.migrateKey(loser, winner);
        }
        mergeParticipants.clear();
        if (migrated > 0 && CreatePackageInnovation.DEBUG_LOGGING) {
            CreatePackageInnovation.LOGGER.info(
                    "[CPI-POOL] two-vault merge resolved: winner={}, migrated {} package(s) to winner",
                    winner, migrated);
        }
        return winner;
    }

    /**
     * Move a vault's pool from one UUID to another (used by merge resolution).
     * Appends the old deque to the new key's tail (FIFO: old-first stays first).
     * Entries keep their origin tags. No-op if old key has no pool.
     *
     * @return number of package entries moved (not package count — entries).
     */
    private int migrateKey(UUID oldId, UUID newId) {
        if (oldId.equals(newId)) return 0;
        Deque<Entry> oldDeque = pools.remove(oldId);
        if (oldDeque == null || oldDeque.isEmpty()) return 0;
        Deque<Entry> newDeque = pools.computeIfAbsent(newId, k -> new ArrayDeque<>());
        int moved = 0;
        for (Entry entry : oldDeque) {
            if (entry.stack().count > 0) {
                newDeque.addLast(entry);
                moved++;
            }
        }
        setDirty();
        return moved;
    }
}
