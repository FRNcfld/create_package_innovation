package com.frnc.create_package_innovation.mixin;

import com.frnc.create_package_innovation.identity.ContainerIdSupport;
import com.frnc.create_package_innovation.identity.VaultExtraData;
import com.frnc.create_package_innovation.identity.VaultIdAccessor;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
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
 * Adapter mixin for Create's vanilla vault: gives {@code ItemVaultBlockEntity} a stable
 * UUID and carries it through Create's {@code IMultiBlockEntityContainer} extraData trio
 * so it survives split / reform / reshape. The pool key is the UUID, not a
 * geometry-derived BoundingBox, so reshape never orphans or duplicates pool entries.
 *
 * <p>All the actual logic lives in {@link ContainerIdSupport} — this class is only the
 * per-container glue (state fields + bridge methods + the four hooks). Other containers
 * are supported by writing an identical adapter for their BlockEntity; see
 * {@code mixin.compat.ItemSiloBlockEntityMixin} for the Create: Connected vertical silo.
 * The interface and the propagation contract are documented on
 * {@link com.frnc.create_package_innovation.identity.VaultIdAccessor} and
 * {@link ContainerIdSupport}.</p>
 *
 * <h3>Mixin note on the extraData overrides</h3>
 *
 * {@code getExtraData}/{@code setExtraData}/{@code modifyExtraData} are <em>default</em>
 * methods on {@code IMultiBlockEntityContainer}, and {@code ItemVaultBlockEntity} does NOT
 * override them. Declaring them here causes the merged target class to gain concrete
 * overrides of those interface methods — a standard mixin pattern. The method names MUST
 * exactly match the interface method names (no {@code createPackageInnovation$} prefix) so
 * the JVM treats them as interface implementations.
 */
@Mixin(value = ItemVaultBlockEntity.class, remap = false)
public class ItemVaultBlockEntityMixin implements VaultIdAccessor {

    /** Stable per-container UUID. Lazily minted (see ContainerIdSupport), NBT-persisted. */
    @Unique
    private UUID cpi$vaultId;

    /**
     * UUIDs observed via {@code setExtraData} on this BE since the last
     * {@code notifyMultiUpdated}. Always single-element in normal reshape; multi-element
     * only in a two-container merge. Transient — not persisted.
     */
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
