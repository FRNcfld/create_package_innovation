package com.frnc.create_package_innovation.mixin.compat;

import com.frnc.create_package_innovation.identity.FluidTargetAccessor;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.TankManipulationBehaviour;
import com.yision.fluidlogistics.content.logistics.fluidPackager.FluidPackagerBlockEntity;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Tells the pool keying which container a <b>Create: FluidLogistics fluid packager</b>
 * really serves.
 *
 * <p>The machine extends Create's {@code PackagerBlockEntity}, but its
 * {@code targetInventory} is only its item face: its filter even rejects portable fluid
 * interfaces. The storage it drains and fills is the tank that its separate
 * {@code fluidTarget} ({@link TankManipulationBehaviour}) faces, so without this the pool
 * would be keyed on some item container — or, more often, on nothing at all, since a fluid
 * tank exposes no item capability for {@code targetInventory} to resolve. That is why fluid
 * packagers never pooled before this.</p>
 *
 * <p>Only the one method of {@link FluidTargetAccessor} is implemented; everything else is
 * the ordinary packager path. The field is read with {@code @Shadow} rather than a cast to
 * keep the reference local and the missing-field failure mode obvious in the log.</p>
 *
 * <p><b>Optional dependency.</b> This lives in {@code create_package_innovation.compat.mixins.json}
 * ({@code required: false}, {@code defaultRequire: 0}), so a pack without FluidLogistics
 * simply skips the whole config. The field name is pinned by
 * {@code fluidlogistics_version} in {@code gradle.properties}, which is what makes the
 * {@code @Shadow} safe.</p>
 */
@Mixin(value = FluidPackagerBlockEntity.class, remap = false)
public class FluidPackagerBlockEntityMixin implements FluidTargetAccessor {

    /** The machine's fluid-side target behaviour. Set once in {@code addBehaviours}. */
    @Shadow
    public TankManipulationBehaviour fluidTarget;

    @Override
    public BlockPos createPackageInnovation$fluidTargetPos() {
        TankManipulationBehaviour behaviour = fluidTarget;
        if (behaviour == null) return null;
        BlockFace target = behaviour.getTarget();
        return target == null ? null : target.getConnectedPos();
    }
}
