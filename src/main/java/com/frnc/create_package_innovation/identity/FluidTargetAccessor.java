package com.frnc.create_package_innovation.identity;

import net.minecraft.core.BlockPos;

/**
 * Duck interface for machines whose real storage target is a <b>fluid</b> handler rather
 * than the item inventory that {@code PackagerBlockEntity.targetInventory} resolves to.
 *
 * <p><b>Why this exists.</b> {@link VaultIdentity#vaultIdOf} keys the shared pool on the
 * container a packager serves, and it reads that container from {@code targetInventory}.
 * That is correct for every item packager, but Create: FluidLogistics' fluid packager is a
 * {@code PackagerBlockEntity} whose {@code targetInventory} is only its <i>item</i> face —
 * its filter even excludes portable fluid interfaces — while the storage it actually
 * serves is the tank that its separate {@code fluidTarget} behaviour faces. Keyed on the
 * item face, fluid storage either resolves to some unrelated container's key or to no key
 * at all, which is exactly why fluid packagers never took part in pooling.</p>
 *
 * <p><b>Why an interface and not a direct check.</b> Same discipline as
 * {@link VaultIdAccessor} and {@link NetworkAnchorAccessor}: this type lives in the root
 * package and mentions nothing mod-specific, so it is always loadable. The implementation
 * for the optional mod lives in {@code mixin.compat} only. A machine that does not
 * implement it keeps the original item-target behaviour, so vanilla packagers and other
 * mods' packagers are completely unaffected.</p>
 */
public interface FluidTargetAccessor {

    /**
     * The block position the machine's fluid target faces (the tank it drains and fills),
     * or {@code null} if it has no fluid target or none is linked yet. Callers must treat
     * {@code null} as "fall back to the ordinary item target".
     */
    BlockPos createPackageInnovation$fluidTargetPos();
}
