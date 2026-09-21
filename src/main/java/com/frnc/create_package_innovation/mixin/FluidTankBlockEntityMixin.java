package com.frnc.create_package_innovation.mixin;

import com.frnc.create_package_innovation.identity.ContainerIdSupport;
import com.frnc.create_package_innovation.identity.FluidTargetAccessor;
import com.frnc.create_package_innovation.identity.VaultGeometry;
import com.frnc.create_package_innovation.identity.VaultIdAccessor;
import com.frnc.create_package_innovation.identity.VaultIdentity;
import com.frnc.create_package_innovation.pool.OrphanSweep;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Adapter mixin for Create's <b>fluid tank</b> — the container Create: FluidLogistics'
 * fluid packager serves (see {@link com.frnc.create_package_innovation.identity.FluidTargetAccessor}).
 *
 * <p>{@code FluidTankBlockEntity} implements {@code IMultiBlockEntityContainer.Fluid}, so
 * once it carries a {@link VaultIdAccessor} UUID it plugs into the whole existing
 * mechanism unchanged: {@code VaultIdentity} keys the pool on that UUID, the split hook in
 * {@code ConnectivityHandlerMixin} decides partial-break-vs-teardown, and
 * {@code OrphanSweep} uses the container hint. Nothing container-specific is needed beyond
 * the state field below and the two NBT hooks.</p>
 *
 * <h3>Two deliberate omissions (do not "fix" these)</h3>
 *
 * <p><b>1. The extraData trio is NOT overridden here.</b> Unlike the vault and the silo,
 * this BE already occupies Create's extraData channel itself: {@code getExtraData} returns
 * {@code Boolean.valueOf(window)} and {@code setExtraData} reads a {@code Boolean} back
 * into its {@code window} field (bytecode-verified on Create 6.0.8-289). Declaring the trio
 * here would silently replace Create's implementation and break the tank's own window-state
 * propagation. The channel is therefore unavailable, and the tank's UUID is propagated by
 * the structural adopt walk in {@code VaultGeometry.anyContiguousPartNearby} instead, which
 * the split hook calls — see the next point.</p>
 *
 * <p><b>2. {@code notifyMultiUpdated} is NOT hooked.</b> On the vault, extraData hands the
 * surviving parts the old UUID <i>before</i> the re-formed controller's
 * {@code notifyMultiUpdated} runs, so minting there is safe. For a tank there is no such
 * hand-over: if we minted in {@code notifyMultiUpdated}, the re-formed controller would
 * already hold a fresh UUID by the time the split hook runs, the adopt walk would refuse to
 * overwrite a non-null UUID, and the pool would be stranded under the old key. So a tank
 * mints lazily on first use only ({@code VaultIdentity} mints on the controller when it
 * reads a null UUID), which leaves the re-formed controller empty for the adopt walk to
 * fill. The cost is that two tanks merging into one are not reconciled the way two vaults
 * are; the losing key's pool is then recovered as dropped items by {@code OrphanSweep}
 * rather than migrated.</p>
 *
 * <p>Known boundary: the UUID is written by {@code write} only, not {@code writeSafe}, so a
 * tank picked up by a contraption comes back with a fresh key (the old pool is then dropped
 * as items by the sweep). This matches the vault adapter's behaviour.</p>
 */
@Mixin(value = FluidTankBlockEntity.class, remap = false)
public class FluidTankBlockEntityMixin implements VaultIdAccessor {

    /** Stable per-container UUID. Lazily minted (see VaultIdentity), NBT-persisted. */
    @Unique
    private UUID cpi$vaultId;

    // ---------------------------------------------------------------------------
    // VaultIdAccessor bridges — public so the merged target satisfies the interface.
    // ---------------------------------------------------------------------------

    public UUID createPackageInnovation$getVaultId() {
        return cpi$vaultId;
    }

    public void createPackageInnovation$setVaultId(UUID id) {
        cpi$vaultId = id;
    }

    // ---------------------------------------------------------------------------
    // NBT persistence
    // ---------------------------------------------------------------------------

    @Inject(method = "write(Lnet/minecraft/nbt/CompoundTag;Z)V", at = @At("RETURN"))
    private void createPackageInnovation$writeVaultId(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        ContainerIdSupport.onWrite(this, tag);
    }

    @Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Z)V", at = @At("RETURN"))
    private void createPackageInnovation$readVaultId(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        ContainerIdSupport.onRead(this, tag);
    }
}
