package com.frnc.create_package_innovation.identity;

import com.frnc.create_package_innovation.partial.PartialOrderTracker;
import com.frnc.create_package_innovation.pool.OrphanSweep;
import com.frnc.create_package_innovation.pool.SharedPackagePool;
import com.simibubi.create.api.packager.InventoryIdentifier;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Resolves the stable identity of whatever container a repackager is attached to.
 *
 * <p><b>Two identity strategies, chosen by container kind:</b></p>
 * <ol>
 *   <li><b>Multiblock containers</b> ({@link IMultiBlockEntityContainer} — Create's vault,
 *       Create: Connected's Item Silo, …): use the stable UUID that our adapter mixin
 *       attaches to the controller BE. This is the only reshape-safe identity: a
 *       geometry- or position-derived key changes when the multiblock is reshaped, which
 *       would orphan (or duplicate) pooled packages mid-order.</li>
 *   <li><b>Every other container</b> (vanilla chests, barrels, other mods' storage
 *       blocks, …): use a deterministic {@linkplain #positionKey position key}. No mixin
 *       into the container's class is needed at all, so <b>any</b> mod's single-block
 *       container works out of the box.</li>
 * </ol>
 *
 * <p>A multiblock container that has <em>no</em> adapter mixin deliberately yields
 * {@code null} (no pooling) instead of falling back to a position key: for a multiblock,
 * position keys are not reshape-stable, so pooling there would strand packages.</p>
 *
 * <p>Lives outside the mixin classes because mixin methods get merged into the target
 * class and cannot declare public/static helpers there (§3.9⑤).</p>
 *
 * <p>Returns {@code null} if the repackager has no target inventory or the target can't
 * be resolved. All callers fall back to vanilla behavior in that case.</p>
 */
public final class VaultIdentity {

    /**
     * Namespace prefix for position keys, so they can never be confused with the random
     * UUIDs minted on multiblock controllers (different derivation entirely).
     */
    private static final String POSITION_KEY_NAMESPACE = "create_package_innovation:container@";

    private VaultIdentity() {}

    /**
     * Resolve the identity of the container this packager (or repackager) targets, or null.
     *
     * <p>Path: the container block is either the one the machine's <b>fluid</b> target faces
     * (fluid packagers only — see {@link FluidTargetAccessor}) or the one that
     * {@code targetInventory.getTarget()} gives; its {@code getConnectedPos()} is the
     * container block the repackager faces. For a multiblock we then walk to the controller
     * BE via vanilla {@code getControllerBE()} and read the mixed-in UUID (lazily minting it
     * if this is the first use); for anything else we derive a position key from that
     * block.</p>
     */
    public static UUID vaultIdOf(PackagerBlockEntity r) {
        Level level = r.getLevel();
        if (level == null) return null;

        // A fluid machine's storage is the tank its fluidTarget faces, not the item face that
        // targetInventory resolves to. Only Create: FluidLogistics' fluid packager implements
        // FluidTargetAccessor, so every other packager takes exactly the old path.
        BlockPos fluidPos = r instanceof FluidTargetAccessor fluid
                ? fluid.createPackageInnovation$fluidTargetPos()
                : null;
        boolean fluidSource = fluidPos != null;

        InvManipulationBehaviour tb = r.targetInventory;
        BlockPos vaultPos = fluidPos;
        if (vaultPos == null) {
            if (tb == null) return null;
            BlockFace target = tb.getTarget();
            if (target == null) return null;
            vaultPos = target.getConnectedPos();
        }
        if (vaultPos == null) return null;

        BlockEntity be = level.getBlockEntity(vaultPos);
        if (be == null) return null;

        // ---- (1) multiblock container → stable UUID -----------------------------------
        if (be instanceof IMultiBlockEntityContainer container) {
            BlockEntity controllerBe = container.getControllerBE();
            if (controllerBe == null) controllerBe = be; // footgun guard: parts may report controller==self
            // No adapter mixin → no reshape-safe identity available. Do NOT fall back to a
            // position key here (see class javadoc); just skip pooling for this container.
            if (!(controllerBe instanceof VaultIdAccessor acc)) return null;

            UUID id = acc.createPackageInnovation$getVaultId();
            if (id == null) {
                // Container was just placed / chunk just loaded and notifyMultiUpdated
                // hasn't fired yet. Mint on demand so deposit/poll can proceed.
                id = UUID.randomUUID();
                acc.createPackageInnovation$setVaultId(id);
                controllerBe.setChanged();
            }
            // Remember where this key lives so OrphanSweep can later tell whether the
            // container still exists (safety net for a missed drain). Server-side only —
            // remember() no-ops when there is no server.
            ContainerHintRegistry.remember(level.getServer(), id, level, vaultPos, true);
            return id;
        }

        // ---- (2) single-block container → deterministic position key ------------------
        // Normalise the position through Create's own InventoryIdentifier first, so that a
        // container spanning more than one block resolves to ONE key no matter which of its
        // blocks the repackager faces. The case that matters is the vanilla double chest:
        // Create reports it as InventoryIdentifier.Pair(halfA, halfB) (see
        // AllInventoryIdentifiers), so both halves yield the same identifier. Keying on the
        // raw faced block instead gives the two halves different keys and splits the pool in
        // two — the symptom is "only one repackager ever works" even though several are
        // attached to the same chest.
        BlockPos identityPos = vaultPos;
        // The identifier normalisation below is derived from the ITEM behaviour's view of the
        // world, so it must not be consulted when the container came from the fluid target:
        // there the identifier describes some item container (or nothing at all), and using it
        // would key the tank's pool on the wrong block. The raw fluid target position is the
        // identity in that case — correctness over normalisation.
        InventoryIdentifier identifier = null;
        if (!fluidSource && tb != null) {
            var identified = tb.getIdentifiedInventory();
            identifier = identified == null ? null : identified.identifier();
        }

        // Networked containers: Create: Storage's Simple Storage Network reports ONE
        // InventoryIdentifier for every block of the network (its record carries the member
        // set — membership-derived, so it is NOT usable as a key; see NetworkAnchorAccessor).
        // Keying on the anchor (controller) position instead gives the whole network a single
        // pool, and the ordinary removal hook then does the right thing for free: breaking a
        // box computes that box's position key (no match → nothing dropped), while breaking
        // the controller computes exactly the anchor key (→ pool dropped).
        if (identifier instanceof NetworkAnchorAccessor anchor) {
            BlockPos anchorPos = anchor.createPackageInnovation$networkAnchorPos();
            if (anchorPos != null) {
                UUID key = positionKey(level, anchorPos);
                ContainerHintRegistry.remember(level.getServer(), key, level, anchorPos, false);
                return key;
            }
        }
        if (identifier instanceof InventoryIdentifier.Pair pair) {
            BlockPos first = pair.first();
            BlockPos second = pair.second();
            // Either half is fine, as long as the pick is deterministic for the pair.
            identityPos = first.compareTo(second) <= 0 ? first : second;
        } else if (identifier instanceof InventoryIdentifier.Single single) {
            identityPos = single.pos();
        }
        UUID key = positionKey(level, identityPos);
        ContainerHintRegistry.remember(level.getServer(), key, level, identityPos, false);
        return key;
    }

    /**
     * Deterministic key for a single-block container: UUIDv3 (MD5 name-based) over the
     * dimension id and the block coordinates. Properties that matter here:
     *
     * <ul>
     *   <li><b>Same shape as a multiblock UUID</b>, so {@link SharedPackagePool} and
     *       {@link PartialOrderTracker} keep their existing NBT format — no save
     *       migration needed for this feature.</li>
     *   <li><b>Dimension-scoped</b>, so identical coordinates in different dimensions
     *       never share a pool.</li>
     *   <li><b>Stable across save/load and across a same-block swap</b> at the same
     *       position: if a container is destroyed in a way we missed (or replaced by the
     *       same block), a container placed at the same spot re-derives the same key, so
     *       leftover packages become reachable again instead of being orphaned
     *       forever.</li>
     * </ul>
     */
    public static UUID positionKey(Level level, BlockPos pos) {
        String dimension = level.dimension().location().toString();
        String raw = POSITION_KEY_NAMESPACE + dimension + ':' + pos.getX() + ',' + pos.getY() + ',' + pos.getZ();
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
    }
}
