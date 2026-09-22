package com.frnc.create_package_innovation.identity;

/**
 * Marker for machines that count as a <b>理包机</b> (repackager) for everything the shared pool
 * cares about. It has two effects, both keyed off this one interface:
 *
 * <ol>
 *   <li><b>Pool routing</b> (TECHNICAL.md §3.21): what this machine deposits is tagged as
 *       repackager output, and only machines that implement this interface may poll those
 *       entries. A plain packager therefore can no longer take the ordered packages a repackager
 *       produced — which also means a package always leaves through an output face of the kind of
 *       machine that produced it.</li>
 *   <li><b>Redstone gate</b> (TECHNICAL.md §3.9⑦): while its own {@code redstonePowered} is
 *       false, the machine takes nothing out of the pool. Nothing is lost by being gated — the
 *       packages stay in SavedData and ship as soon as the machine is powered again. The
 *       <b>hand-over</b> half of the hook is never gated, for any machine.</li>
 * </ol>
 *
 * <h3>Why a marker instead of a class check</h3>
 *
 * <p>Repackager <em>variants</em> are not all subclasses of Create's {@code RepackagerBlockEntity}:
 * Create: FluidLogistics' fluid repackager ({@code FluidRepackagerBlockEntity}, in the mod's
 * {@code repackager} sub-package) extends {@code PackagerBlockEntity} <em>directly</em> — verified
 * with {@code javap} against {@code createfluidlogistic-1.3.0-mc1.20.1} — so an {@code instanceof
 * RepackagerBlockEntity} test silently misses it (it was un-gated and mis-classified before this
 * interface existed).</p>
 *
 * <p>At the same time the main mixin ({@code PackagerBlockEntityMixin}, main config) must not
 * reference any optional mod's class: AGENTS.md §3③ — an optional mod's package reference in a
 * non-compat class makes players without that mod crash on class load. One plain interface in this
 * package solves both:</p>
 *
 * <ul>
 *   <li>Create's repackager opts in through {@code RepackagerBlockEntityMixin} (main config);</li>
 *   <li>the fluid repackager opts in through {@code mixin.compat.FluidRepackagerBlockEntityMixin}
 *       (compat config, {@code required = false});</li>
 *   <li>the main mixin only ever tests <em>this</em> type, so it names no subclass of
 *       {@code PackagerBlockEntity} at all.</li>
 * </ul>
 *
 * <p>⚠️ <b>Do not opt a plain packager in.</b> On a shared container a plain packager is usually a
 * pure sender that is not wired to redstone at all, and opting it in would both stop the pool from
 * being drained while it is unpowered and (worse) let it ship the repackagers' ordered packages out
 * of its own output face — which is exactly the problem §3.21 exists to prevent.</p>
 *
 * <p>This is a pure marker — no methods, nothing to implement. An adapter mixin adds it with
 * {@code implements RepackagerLike} and Mixin makes the merged target class implement it. It lives
 * in a plain package (never {@code …mixin}) because the mixin subsystem forbids referencing classes
 * registered in a mixin package directly — same reason as {@link VaultIdAccessor}.</p>
 */
public interface RepackagerLike {
}
