package com.frnc.create_package_innovation.mixin.compat;

import com.frnc.create_package_innovation.identity.NetworkAnchorAccessor;
import com.frnc.create_package_innovation.identity.VaultIdentity;
import net.fxnt.fxntstorage.storage_network.StorageNetworkIdentifier;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adapter mixin for <b>Create: Storage</b>: exposes the network's anchor position so the pool
 * can be keyed per storage network instead of per block.
 *
 * <p>Create: Storage registers its blocks with Create's {@code InventoryIdentifier} registry,
 * so {@code InvManipulationBehaviour.getIdentifiedInventory().identifier()} returns a
 * {@link StorageNetworkIdentifier} for <em>any</em> block of the network (boxes, controller,
 * interface). Its record components are {@code (controllerPos, memberPositions)}: the member
 * set is membership-derived and therefore unusable as a pool key, but {@code controllerPos}
 * is exactly the stable anchor we want.</p>
 *
 * <p>Consequences, all of which fall out of the ordinary position-key machinery in
 * {@link com.frnc.create_package_innovation.identity.VaultIdentity}: every block of one network maps to
 * one pool (so several repackagers on <em>different</em> boxes of the same network also share
 * work), breaking a box drains nothing (the anchor still exists), and breaking the controller
 * drains the pool (the anchor position is gone, and {@code LevelChunkRemovalMixin} computes
 * exactly that position key).</p>
 *
 * <p><b>Optional dependency.</b> Lives in {@code create_package_innovation.compat.mixins.json}
 * ({@code required = false}, {@code defaultRequire = 0}), so a player without Create: Storage
 * gets a warning at most — never a crash, and never a change to core behaviour.</p>
 */
@Mixin(value = StorageNetworkIdentifier.class, remap = false)
public class StorageNetworkIdentifierMixin implements NetworkAnchorAccessor {

    public BlockPos createPackageInnovation$networkAnchorPos() {
        return ((StorageNetworkIdentifier) (Object) this).controllerPos();
    }
}
