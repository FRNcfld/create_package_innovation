package com.frnc.create_package_innovation.mixin.compat;

import com.frnc.create_package_innovation.identity.ContainerIdSupport;
import com.frnc.create_package_innovation.identity.VaultExtraData;
import com.frnc.create_package_innovation.identity.VaultIdAccessor;
import com.hlysine.create_connected.content.itemsilo.ItemSiloBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter mixin for <b>Create: Connected</b>'s vertical item vault ("Item Silo",
 * 纵向物品保险库).
 *
 * <p>Why this needs its own mixin instead of just working: {@code ItemSiloBlockEntity}
 * is <em>not</em> a subclass of Create's {@code ItemVaultBlockEntity} — it extends
 * {@code SmartBlockEntity} and reimplements the multiblock machinery (its own
 * {@code controller}/{@code radius}/{@code length}, {@code getControllerBE()},
 * {@code initCapability()}, {@code notifyMultiUpdated()}). But it implements the same
 * Create interface ({@code IMultiBlockEntityContainer.Inventory}) and uses the same
 * {@code ConnectivityHandler}, and its axis is Y (vertical). So it plugs into exactly
 * the same UUID mechanism — it just needs the state fields and hooks, which this class
 * provides by delegating to {@link ContainerIdSupport}, identically to
 * {@code ItemVaultBlockEntityMixin}.</p>
 *
 * <p><b>Optional dependency.</b> This mixin lives in its own config,
 * {@code create_package_innovation.compat.mixins.json}, which is declared with
 * {@code "required": false} and {@code defaultRequire: 0} so that a player without
 * Create: Connected installed (or a future Create: Connected that renames these methods)
 * gets a warning instead of a startup crash. Without the mod, repackagers attached to a
 * silo simply behave like vanilla — no pooling, nothing breaks.</p>
 *
 * <p>The {@code notifyMultiUpdated}/{@code write}/{@code read} targets all exist on
 * {@code ItemSiloBlockEntity} with the same descriptors as on the vanilla vault
 * (verified against the 1.20.1 source of Create: Connected).</p>
 */
@Mixin(value = ItemSiloBlockEntity.class, remap = false)
public class ItemSiloBlockEntityMixin implements VaultIdAccessor {

    /** Stable per-container UUID. Lazily minted (see ContainerIdSupport), NBT-persisted. */
    @Unique
    private UUID cpi$vaultId;

    /** See ItemVaultBlockEntityMixin#cpi$observedIds. */
    @Unique
    private final Set<UUID> cpi$observedIds = new HashSet<>();

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
    // extraData trio
    // ---------------------------------------------------------------------------

    public Object getExtraData() {
        return new VaultExtraData(cpi$vaultId);
    }

    public void setExtraData(Object data) {
        ContainerIdSupport.onSetExtraData((BlockEntity) (Object) this, this, cpi$observedIds, data);
    }

    public Object modifyExtraData(Object data) {
        return data;
    }

    // ---------------------------------------------------------------------------
    // Hooks: merge resolution / lazy minting + NBT persistence
    // ---------------------------------------------------------------------------

    @Inject(method = "notifyMultiUpdated()V", at = @At("HEAD"))
    private void createPackageInnovation$onNotifyMultiUpdated(CallbackInfo ci) {
        ContainerIdSupport.onNotifyMultiUpdated((BlockEntity) (Object) this, this, cpi$observedIds);
    }

    @Inject(method = "write(Lnet/minecraft/nbt/CompoundTag;Z)V", at = @At("RETURN"))
    private void createPackageInnovation$writeVaultId(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        ContainerIdSupport.onWrite(this, tag);
    }

    @Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Z)V", at = @At("RETURN"))
    private void createPackageInnovation$readVaultId(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        ContainerIdSupport.onRead(this, tag);
    }
}
