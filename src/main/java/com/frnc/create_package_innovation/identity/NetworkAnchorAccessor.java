package com.frnc.create_package_innovation.identity;

import net.minecraft.core.BlockPos;

/**
 * Duck-typing interface exposed by compat mixins on <em>other mods'</em> network identifier
 * types, so that {@link VaultIdentity} can key a whole storage network to one pool without
 * referencing any optional-mod class from a plain, non-mixin package.
 *
 * <p>Implemented today by
 * {@code mixin.compat.StorageNetworkIdentifierMixin} (Create: Storage's
 * {@code StorageNetworkIdentifier}, whose record component {@code controllerPos} is the
 * network's anchor).</p>
 *
 * <p><b>Why an anchor position and not the identifier itself:</b> such an identifier is
 * membership-derived (Create: Storage's record carries the whole member-position set), so its
 * value changes the moment a box is added or removed. Keying the pool on it would strand or
 * duplicate the pool exactly like the 0.5.0 BoundingBox key did. The network's
 * <em>controller position</em>, by contrast, does not move when membership changes — so it is
 * the stable anchor, and turning it into an ordinary position key also means no NBT, no UUID
 * minting and no mixin on the other mod's BlockEntity at all.</p>
 */
public interface NetworkAnchorAccessor {

    /**
     * Position of the network's anchor (its controller) block, or {@code null} if this
     * identifier has no resolvable anchor.
     */
    BlockPos createPackageInnovation$networkAnchorPos();
}
