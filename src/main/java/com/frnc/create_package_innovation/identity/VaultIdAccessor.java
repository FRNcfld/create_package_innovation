package com.frnc.create_package_innovation.identity;

import com.frnc.create_package_innovation.partial.PartialOrderTracker;
import com.frnc.create_package_innovation.pool.SharedPackagePool;
import java.util.UUID;

/**
 * Duck-typing interface for reading/writing the stable UUID that our adapter mixins inject
 * into the {@code BlockEntity} of a <b>multiblock</b> container.
 *
 * <p><b>Only multiblocks need this.</b> Single-block containers (vanilla chests, barrels,
 * other mods' storage blocks, …) are keyed by a deterministic position UUID instead — no
 * mixin into their class, so they work out of the box. A multiblock needs an on-BE UUID
 * because its identity has to survive split / reform / reshape, which a position key
 * cannot (see {@link VaultIdentity}). So "support a new container" means:
 * <ul>
 *   <li><b>single-block container, any mod</b> — nothing to do, it already works;</li>
 *   <li><b>multiblock container</b> — add a ~60-line adapter mixin that (a) declares a
 *       {@code @Unique UUID} field, (b) implements this interface, and (c) delegates to
 *       {@link ContainerIdSupport}.</li>
 * </ul>
 * Everything downstream ({@code SharedPackagePool}, {@code PartialOrderTracker},
 * {@link VaultGeometry}, {@code ConnectivityHandlerMixin}) works on any BE implementing
 * this interface — no {@code instanceof} against a concrete container class anywhere.
 * Currently implemented by:
 * <ul>
 *   <li>Create's {@code ItemVaultBlockEntity} — via {@code ItemVaultBlockEntityMixin}</li>
 *   <li>Create: Connected's {@code ItemSiloBlockEntity} (the vertical vault) — via
 *       {@code mixin.compat.ItemSiloBlockEntityMixin}</li>
 * </ul>
 *
 * <p><b>Why not {@code @Accessor} on a {@code @Mixin interface}?</b> Mixin's APT
 * cannot resolve {@code @Accessor} targets that are themselves injected by another
 * mixin in the same compilation (the field doesn't exist in the original class at
 * APT time). We instead use the standard "duck interface" pattern: this is a plain
 * interface (no {@code @Mixin}), and each adapter mixin declares
 * {@code implements VaultIdAccessor}. Mixin then makes the merged target class
 * implement this interface, and any code can {@code cast} a container BE to it and
 * call the bridge methods.</p>
 *
 * <p><b>Lives in a plain, non-mixin package</b> — {@code …create_package_innovation.identity},
 * never {@code …create_package_innovation.mixin} — because the mixin subsystem forbids direct
 * references to classes inside any registered {@code @Mixin} package — see the runtime error
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... and cannot be
 * referenced directly} (Mixin rejects any direct class load from a registered mixin
 * package).</p>
 *
 * <p>The method signatures here MUST exactly match the public methods declared in
 * the adapter mixins so the JVM's interface dispatch finds them on the merged target.</p>
 */
public interface VaultIdAccessor {

    UUID createPackageInnovation$getVaultId();

    void createPackageInnovation$setVaultId(UUID id);
}
