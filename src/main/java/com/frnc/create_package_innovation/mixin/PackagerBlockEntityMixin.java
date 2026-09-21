package com.frnc.create_package_innovation.mixin;

import com.frnc.create_package_innovation.CreatePackageInnovation;
import com.frnc.create_package_innovation.identity.VaultIdentity;
import com.frnc.create_package_innovation.pool.SharedPackagePool;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The shared-pool wiring for <b>every</b> packager — plain packagers, repackagers, and other
 * mods' packagers that extend {@code PackagerBlockEntity} (e.g. Create: FluidLogistics' fluid
 * packager/repackager). Three pieces, all on the parent class:
 *
 * <ul>
 *   <li><b>Hand-over</b> ({@link #createPackageInnovation$feedFromPool}, part A): anything
 *       sitting in a packager's private queue is moved into the pool. This is the
 *       <em>producer-agnostic</em> rule: vanilla packagers are hooked directly in
 *       {@code attemptToSend}, and repackagers in {@code attemptToRepackage}, but a
 *       third-party packager can enqueue from its own classes (FluidLogistics does exactly
 *       that, from its {@code ResourcePackagerEngine} and its repackager subclass) — there is
 *       no single seam to redirect for those. Draining the queue instead means whatever they
 *       produced ends up shared without us having to know who produced it.</li>
 *   <li><b>Poll</b> (part B): at {@code tick} HEAD an idle packager takes one package back out
 *       of the pool into its private queue; vanilla's tick then ships it as usual (strategy A).
 *       Previously restricted to repackagers by an {@code instanceof} guard — that guard is
 *       gone, which is the point: before this, a large order was handed to one packager and
 *       that single machine emitted one package per second while its neighbours watched.</li>
 *   <li><b>Deposit</b> ({@link #createPackageInnovation$depositToPool}): the parent's
 *       {@code attemptToSend} is where a plain packager enqueues what it assembled; redirect
 *       that insert straight into the pool so the batch never becomes private in the first
 *       place (the hand-over above would catch it one tick later anyway).</li>
 * </ul>
 *
 * <p><b>Why this does not double-handle repackagers.</b> {@code RepackagerBlockEntity}
 * <em>overrides</em> {@code attemptToSend} and never calls {@code super}, so the parent-level
 * redirect simply does not run for it — repackagers keep depositing through their own
 * {@code attemptToRepackage} redirect in {@code RepackagerBlockEntityMixin}. The poll/hand-over
 * half applies to them as well, which is intended.</p>
 *
 * <p>Targets {@code tick()} and {@code attemptToSend} on the <b>parent</b> because both are only
 * defined there (the repackager inherits {@code tick} and overrides {@code attemptToSend}).
 * {@code remap = false} is correct for a Create class: its own method names are never
 * obfuscated.</p>
 *
 * <h3>Strategy A safety</h3>
 *
 * <p>We only add one package to the private queue when it is empty AND {@code heldBox} is empty
 * AND {@code animationTicks == 0}. Vanilla {@code tick()} then dequeues it from the head next,
 * so the private queue stays at 0~1 elements and vanilla sees no difference; the
 * {@code heldBox} passive-clear protocol is untouched. A stalled packager
 * ({@code heldBox} non-empty) is blocked by the {@code !heldBox.isEmpty()} guard and receives
 * nothing until its downstream clears. The hand-over deliberately does <em>not</em> require
 * redstone: relocating an already-produced backlog to the pool lets the container's powered
 * machines ship it, which is exactly the desired sharing.</p>
 */
@Mixin(value = PackagerBlockEntity.class, remap = false)
public class PackagerBlockEntityMixin {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void createPackageInnovation$feedFromPool(CallbackInfo ci) {
        PackagerBlockEntity self = (PackagerBlockEntity) (Object) this;

        Level level = self.getLevel();
        if (level == null || level.isClientSide) return;

        boolean hasQueue = !self.queuedExitingPackages.isEmpty();
        // "idle" = could accept one package from the pool right now.
        //
        // ⚠️ Deliberately NOT gated on redstonePowered. vanilla tick() has no redstone check at
        // all — it drains queuedExitingPackages unconditionally, and redstone only gates whether
        // lazyTick/attemptToSend *fills* that queue. Gating our feed on redstone therefore made
        // us STRICTER than vanilla: a package already handed to the pool by a machine whose
        // redstone was cut afterwards could never be pulled back, the order silently stalled, and
        // the items looked swallowed. Matching vanilla (ship whatever is queued, powered or not)
        // is required for the pool to be safe. Genuinely stalled machines stay protected by the
        // heldBox guard below.
        boolean idle = self.animationTicks == 0
                && self.heldBox.isEmpty() && !hasQueue;

        // Nothing to hand over and not able to take anything → skip the (non-trivial) key lookup.
        if (!hasQueue && !idle) return;

        UUID vaultKey = VaultIdentity.vaultIdOf(self);
        if (vaultKey == null) return;
        MinecraftServer server = level.getServer();
        if (server == null) return;
        SharedPackagePool pool = SharedPackagePool.get(server);

        // ---- (A) hand over this machine's backlog to the pool ---------------------------
        if (hasQueue) {
            // At-least-once ordering: deposit FIRST, clear the private queue second. The batch
            // holds the same BigItemStack references, so a failure between the two steps can only
            // duplicate a package (recoverable, and only within this tick since both structures
            // are in-memory) — never lose one. Clearing first would open a window where the batch
            // exists in neither the queue nor the pool.
            List<BigItemStack> batch = new ArrayList<>(self.queuedExitingPackages);
            pool.deposit(vaultKey, batch);
            self.queuedExitingPackages.clear();
            self.setChanged();
            if (CreatePackageInnovation.DEBUG_LOGGING) {
                CreatePackageInnovation.LOGGER.info(
                        "[CPI-POOL] handed over {} queue entr(ies) from packager at {} (vault pending: {})",
                        batch.size(), self.getBlockPos().toShortString(), pool.pending(vaultKey));
            }
        }

        // ---- (B) take one package back if idle ------------------------------------------
        if (!idle) return;
        BigItemStack pkg = pool.poll(vaultKey);
        if (pkg != null) {
            self.queuedExitingPackages.add(pkg);
            if (CreatePackageInnovation.DEBUG_LOGGING) {
                CreatePackageInnovation.LOGGER.info(
                        "[CPI-POOL] fed 1 package to packager at {} (vault pending: {})",
                        self.getBlockPos().toShortString(), pool.pending(vaultKey));
            }
        }
    }

    /**
     * Deposit a freshly assembled package into the shared pool instead of this packager's
     * private queue.
     *
     * <p>The target is the one and only {@code queuedExitingPackages.add(...)} inside the
     * parent's {@code attemptToSend} (bytecode offsets 726→739; the method contains no other
     * {@code List.add}). No {@code ordinal} is given on purpose: should a future Create add a
     * second insert there, Mixin fails loudly on the ambiguity instead of silently redirecting
     * the wrong call.</p>
     *
     * <p>Falls back to vanilla {@code add} whenever no container key can be resolved (no target
     * inventory, unsupported multiblock, client side, …), so unrelated setups behave exactly
     * like vanilla. This is an optimisation over the hand-over in {@code tick}: without it the
     * batch would sit private for one tick first.</p>
     */
    @Redirect(
            method = "attemptToSend",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z")
    )
    private boolean createPackageInnovation$depositToPool(List<Object> queue, Object pkg) {
        if (!(pkg instanceof BigItemStack bis)) return queue.add(pkg);

        PackagerBlockEntity self = (PackagerBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide) return queue.add(pkg);

        UUID vaultKey = VaultIdentity.vaultIdOf(self);
        MinecraftServer server = level.getServer();
        if (vaultKey == null || server == null) return queue.add(pkg);

        SharedPackagePool.get(server).deposit(vaultKey, List.of(bis));

        if (CreatePackageInnovation.DEBUG_LOGGING) {
            CreatePackageInnovation.LOGGER.info(
                    "[CPI-POOL] deposited 1 package from packager at {} (vault pending: {})",
                    self.getBlockPos().toShortString(),
                    SharedPackagePool.get(server).pending(vaultKey));
        }
        return true;
    }
}
