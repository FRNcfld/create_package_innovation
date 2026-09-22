package com.frnc.create_package_innovation.mixin;

import com.frnc.create_package_innovation.CreatePackageInnovation;
import com.frnc.create_package_innovation.identity.RepackagerLike;
import com.frnc.create_package_innovation.identity.VaultIdentity;
import com.frnc.create_package_innovation.pool.SharedPackagePool;
import com.frnc.create_package_innovation.pool.SharedPackagePool.Origin;
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
 *   <li><b>Poll</b> (part B): at {@code tick} HEAD an idle packager takes one package back out of
 *       the pool into its private queue; vanilla's tick then ships it as usual (strategy A). Two
 *       conditions narrow what it may take: the entry must have been produced by <em>its own kind</em>
 *       of machine (a 打包机 never carries off a 理包机's ordered packages — §3.21), and a machine
 *       that implements {@link RepackagerLike} must also be powered (see the redstone note in the
 *       handler). A plain packager is not gated on redstone, and the hand-over is never gated for
 *       anyone. Previously the whole hook was restricted to repackagers by an {@code instanceof}
 *       guard — that guard is gone, which is the point: before this, a large order was handed to
 *       one packager and that single machine emitted one package per second while its neighbours
 *       watched.</li>
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
 * nothing until its downstream clears. Polling additionally requires {@code redstonePowered} for
 * machines that opt in via {@link RepackagerLike}, and is always limited to entries of the poller's
 * own kind (a plain packager is gated on neither); the hand-over deliberately requires nothing of
 * the sort: relocating an already-produced backlog to the pool lets the container's other machines
 * ship it, which is exactly the desired sharing, whereas gating it would stall packages in a way
 * vanilla never does.</p>
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
        boolean idle = self.animationTicks == 0
                && self.heldBox.isEmpty() && !hasQueue;

        // ⚠️ Two rules keyed off {@link RepackagerLike}: routing (§3.21) and the redstone gate
        // (§3.9⑦).
        //
        //  - ROUTING (both halves): everything this machine deposits is tagged with its kind, and
        //    poll() only hands back entries of the same kind. That is what stops a 打包机 attached
        //    to the container from carrying off the ordered packages a 理包机 produced (and sending
        //    them out of its own output face). Machines of the same kind still share one queue, so
        //    N repackagers are still ≈ N packages/second.
        //  - The HAND-OVER below (part A) is never redstone-gated, for ANY machine. vanilla tick()
        //    has no redstone check at all: it drains queuedExitingPackages unconditionally, and
        //    redstone only gates whether lazyTick/attemptToSend *fills* that queue. Gating the
        //    hand-over would make us STRICTER than vanilla — an already-produced package could not
        //    even be moved into the pool, so the order would stall silently and the items would
        //    look swallowed. Relocating a produced backlog to the pool is exactly what lets the
        //    container's other machines ship it.
        //  - The POLL (part B) is redstone-gated for machines that opt in via
        //    {@link RepackagerLike}: Create's repackager (through RepackagerBlockEntityMixin) and
        //    Create: FluidLogistics' fluid repackager (through its compat mixin — it extends
        //    PackagerBlockEntity directly, so a class check on RepackagerBlockEntity would miss
        //    it). A repackager is the machine a player actually wires up and expects to stop when
        //    the signal is cut, so an unpowered one takes nothing. A plain packager is NOT opted in
        //    and keeps polling regardless of its own redstone state — in practice a plain packager
        //    on a shared container is a pure sender and is often not wired to redstone at all, and
        //    gating it made packagers stop shipping the pool entirely.
        //    Nothing is lost by the gate either way: the packages stay in SavedData and ship as
        //    soon as that machine is powered again.
        //    Consequence worth knowing: on a container that has BOTH kinds, cutting the
        //    repackagers' redstone does not stop shipping, because the plain packagers keep
        //    polling their own kind's entries. That is intended — put the redstone control on
        //    every machine of the container if you want a full stop.
        //  - `redstonePowered` is the same public field vanilla's own production gate reads. It
        //    is set on the rising edge (activate()) and re-read from the block state in
        //    lazyTick(), which SmartBlockEntity runs every 10 ticks (lazyTickRate = 10), so this
        //    gate can trail the actual signal by at most one lazy tick.
        boolean repackagerLike = self instanceof RepackagerLike;
        boolean canPoll = idle && (!repackagerLike || self.redstonePowered);
        Origin origin = repackagerLike ? Origin.REPACKAGER : Origin.PACKAGER;

        // Nothing to hand over and not able to take anything → skip the (non-trivial) key lookup.
        if (!hasQueue && !canPoll) return;

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
            //
            // Origin = this machine's kind. Its private queue is filled by itself (or by the
            // engine that drives it), so the machine doing the hand-over is the producer.
            List<BigItemStack> batch = new ArrayList<>(self.queuedExitingPackages);
            pool.deposit(vaultKey, batch, origin);
            self.queuedExitingPackages.clear();
            self.setChanged();
            if (CreatePackageInnovation.DEBUG_LOGGING) {
                CreatePackageInnovation.LOGGER.info(
                        "[CPI-POOL] handed over {} queue entr(ies) as {} from packager at {} (vault pending: {})",
                        batch.size(), origin, self.getBlockPos().toShortString(), pool.pending(vaultKey));
            }
        }

        // ---- (B) take one package back if idle (repackager: also powered) -----------------
        if (!canPoll) return;
        BigItemStack pkg = pool.poll(vaultKey, origin);
        if (pkg != null) {
            self.queuedExitingPackages.add(pkg);
            if (CreatePackageInnovation.DEBUG_LOGGING) {
                CreatePackageInnovation.LOGGER.info(
                        "[CPI-POOL] fed 1 {} package to packager at {} (vault pending: {})",
                        origin, self.getBlockPos().toShortString(), pool.pending(vaultKey));
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
     *
     * <p>The deposited entry is tagged with this machine's kind, so routing (§3.21) applies to the
     * direct path as well as to the hand-over. The kind is computed from {@link RepackagerLike}
     * rather than assumed: a repackager variant that did not override {@code attemptToSend} would
     * reach this redirect too, and must not be tagged as a plain packager.</p>
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

        Origin origin = self instanceof RepackagerLike ? Origin.REPACKAGER : Origin.PACKAGER;
        SharedPackagePool.get(server).deposit(vaultKey, List.of(bis), origin);

        if (CreatePackageInnovation.DEBUG_LOGGING) {
            CreatePackageInnovation.LOGGER.info(
                    "[CPI-POOL] deposited 1 {} package from packager at {} (vault pending: {})",
                    origin, self.getBlockPos().toShortString(),
                    SharedPackagePool.get(server).pending(vaultKey));
        }
        return true;
    }
}
