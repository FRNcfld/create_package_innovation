package com.frnc.create_package_innovation.mixin.compat;

import com.frnc.create_package_innovation.identity.RepackagerLike;
import com.yision.fluidlogistics.content.logistics.fluidPackager.repackager.FluidRepackagerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Opts <b>Create: FluidLogistics</b>' fluid repackager into everything the shared pool keys off
 * {@link RepackagerLike}: its output is tagged as repackager output (so a plain packager cannot
 * take it, §3.21) and its polling obeys its own redstone signal (§3.9⑦).
 *
 * <h3>Why this needs its own mixin</h3>
 *
 * <p>{@code FluidRepackagerBlockEntity} extends Create's {@code PackagerBlockEntity}
 * <b>directly</b> — it is <em>not</em> a subclass of {@code RepackagerBlockEntity} (verified with
 * {@code javap} on {@code createfluidlogistic-1.3.0-mc1.20.1}). Before this mixin existed it was
 * therefore classified as a plain packager: it kept pulling packages out of the pool while
 * unpowered, and its own output could be taken by any packager.</p>
 *
 * <p>A pure marker: {@code implements RepackagerLike} and nothing else. Mixin merges the interface
 * into the target class, and there is nothing to implement.</p>
 *
 * <p><b>Optional dependency.</b> Lives in {@code create_package_innovation.compat.mixins.json}
 * ({@code required = false}, {@code defaultRequire = 0}), so a pack without FluidLogistics — or
 * one with a version that renames this class — only gets a warning and never a crash, and core
 * behaviour is unaffected.</p>
 */
@Mixin(value = FluidRepackagerBlockEntity.class, remap = false)
public class FluidRepackagerBlockEntityMixin implements RepackagerLike {
}
