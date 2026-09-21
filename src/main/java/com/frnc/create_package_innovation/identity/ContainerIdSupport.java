package com.frnc.create_package_innovation.identity;

import com.frnc.create_package_innovation.partial.PartialOrderTracker;
import com.frnc.create_package_innovation.pool.SharedPackagePool;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Set;
import java.util.UUID;

/**
 * The container-independent half of the "stable container UUID" mechanism. All logic
 * that used to live inside {@code ItemVaultBlockEntityMixin} sits here, so every
 * supported container only needs a thin adapter mixin holding
 * <ul>
 *   <li>a {@code @Unique UUID cpi$vaultId} field,</li>
 *   <li>a {@code @Unique Set<UUID> cpi$observedIds} field,</li>
 *   <li>the {@link VaultIdAccessor} bridge methods,</li>
 *   <li>the {@code extraData} trio overrides and the {@code read}/{@code write}/
 *       {@code notifyMultiUpdated} hooks — each one delegating to a method below.</li>
 * </ul>
 * The state stays on the BlockEntity (not in a static map) because the UUID has to be
 * persisted in that BE's own NBT and must die with it.
 *
 * <h3>Why a UUID and not geometry</h3>
 * The pool key must survive split / reform / reshape. Create's
 * {@code InventoryIdentifier.Bounds} is geometry-derived and is computed lazily per BE,
 * so different repackagers can observe different snapshots of the same vault mid-reshape
 * — which split one pool into several keys and caused item duplication and loss (the
 * 0.5.0 bug). A UUID carried through Create's
 * {@link IMultiBlockEntityContainer} extraData channel is reshape-invariant.
 *
 * <h3>How the UUID is propagated</h3>
 * Create calls the extraData trio at exactly the moments where identity would otherwise
 * be lost (bytecode-confirmed, see the upstream TECHNICAL.md §3.11):
 * <ul>
 *   <li>{@code ConnectivityHandler.splitMultiAndInvalidate}: for each part,
 *       {@code partControllerBE.getExtraData()} is read and then
 *       {@code part.setExtraData(extraData)} is called <b>before</b>
 *       {@code removeController(true)} — so every part (including the future new
 *       controller) inherits the old controller's UUID.</li>
 *   <li>{@code ConnectivityHandler.tryToFormNewMultiOfWidth}: after reform,
 *       {@code self.notifyMultiUpdated()} fires on the new controller.</li>
 * </ul>
 *
 * <h3>Two-container merge (rare but real)</h3>
 * When two independent containers A and B merge, each part inherits its OWN old
 * controller's UUID, and the new controller only sees the one it inherited itself. So
 * {@link #onSetExtraData} additionally registers every observed UUID in a <b>global</b>
 * merge-participant set (see {@link SharedPackagePool#noteMergeParticipant}). The next
 * {@link #onNotifyMultiUpdated} resolves that set: smallest UUID wins
 * (deterministically), every loser is migrated, and single-container reshape is a no-op
 * because only one participant is ever registered.
 */
public final class ContainerIdSupport {

    /**
     * NBT key for the persisted UUID. Kept as the upstream {@code "GDR_VaultId"} spelling
     * deliberately: it is written into world saves, and renaming it would orphan the UUID
     * of every already-placed container (the pool entry keyed by the old UUID would become
     * unreachable until the container is broken). Each BE only ever reads its own tag, so
     * sharing one key across container types is safe.
     */
    public static final String VAULT_ID_KEY = "GDR_VaultId";

    private ContainerIdSupport() {}

    // ---------------------------------------------------------------------------
    // extraData trio (see class javadoc for the propagation contract)
    // ---------------------------------------------------------------------------

    /**
     * Receives extraData from Create (called on each part before {@code removeController}
     * during a split, and on self after a reform). Records the incoming UUID for merge
     * detection and adopts it if this BE has none yet.
     */
    public static void onSetExtraData(BlockEntity self, VaultIdAccessor acc, Set<UUID> observed, Object data) {
        if (!(data instanceof VaultExtraData ved)) return;
        UUID incoming = ved.vaultId();
        if (incoming == null) return;
        observed.add(incoming);
        if (acc.createPackageInnovation$getVaultId() == null) {
            acc.createPackageInnovation$setVaultId(incoming);
        }
        // Also register globally so a future new-controller notifyMultiUpdated can see
        // UUIDs that landed on sibling parts (not just on this BE).
        Level level = self.getLevel();
        if (level == null || level.isClientSide) return;
        MinecraftServer server = level.getServer();
        if (server == null) return;
        SharedPackagePool.get(server).noteMergeParticipant(incoming);
        PartialOrderTracker.get(server).noteMergeParticipant(incoming);
    }

    // ---------------------------------------------------------------------------
    // notifyMultiUpdated: where merges resolve and lazy UUIDs are minted
    // ---------------------------------------------------------------------------

    /**
     * Called from the {@code notifyMultiUpdated} HEAD hook. Only the controller's UUID
     * minting / merge resolution matters; {@code isController()} returning true as a
     * footgun (controller field already nulled after {@code removeController}) is fine
     * here because the UUID logic is geometry-independent.
     */
    public static void onNotifyMultiUpdated(BlockEntity self, VaultIdAccessor acc, Set<UUID> observed) {
        Level level = self.getLevel();
        if (level == null || level.isClientSide) return;
        if (!(self instanceof IMultiBlockEntityContainer container) || !container.isController()) return;

        MinecraftServer server = level.getServer();
        if (server == null) return;

        SharedPackagePool pool = SharedPackagePool.get(server);
        PartialOrderTracker tracker = PartialOrderTracker.get(server);

        // Lazily mint a UUID if this controller has none (first formation / first query).
        if (acc.createPackageInnovation$getVaultId() == null) {
            acc.createPackageInnovation$setVaultId(UUID.randomUUID());
            self.setChanged();
        }

        // Resolve a two-container merge if one is in flight. The global merge-participant
        // set was populated by onSetExtraData on every part during splitMultiAndInvalidate.
        // Pick winner = smallest UUID (deterministic); migrate all losers to it.
        UUID winner = pool.resolveMergeWinner(tracker);
        UUID current = acc.createPackageInnovation$getVaultId();
        if (winner != null && !winner.equals(current)) {
            // A merge was detected and the deterministic winner differs from our current
            // UUID — adopt the winner so this controller's UUID matches the migrated key.
            acc.createPackageInnovation$setVaultId(winner);
            self.setChanged();
        }

        observed.clear();
    }

    // ---------------------------------------------------------------------------
    // NBT persistence
    // ---------------------------------------------------------------------------

    /** Persist the UUID so it survives chunk unload / restart / {@code /clone}. */
    public static void onWrite(VaultIdAccessor acc, CompoundTag tag) {
        UUID id = acc.createPackageInnovation$getVaultId();
        if (id != null) tag.putUUID(VAULT_ID_KEY, id);
    }

    public static void onRead(VaultIdAccessor acc, CompoundTag tag) {
        if (tag.hasUUID(VAULT_ID_KEY)) acc.createPackageInnovation$setVaultId(tag.getUUID(VAULT_ID_KEY));
    }
}
