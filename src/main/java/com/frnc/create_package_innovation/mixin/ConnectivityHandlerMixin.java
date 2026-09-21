package com.frnc.create_package_innovation.mixin;

import com.frnc.create_package_innovation.identity.ContainerHintRegistry;
import com.frnc.create_package_innovation.identity.VaultGeometry;
import com.frnc.create_package_innovation.identity.VaultIdAccessor;
import com.frnc.create_package_innovation.partial.PartialOrderTracker;
import com.frnc.create_package_innovation.pool.SharedPackagePool;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Detects pooled-container teardown (break / wrench) and drains the corresponding shared
 * package pool so packages are dropped instead of being orphaned in SavedData.
 *
 * <p>Targets the PUBLIC {@code splitMulti(T)} — the universal entry point called by each
 * container block's {@code onRemove} (break) and {@code onWrenched} (wrench top face),
 * e.g. Create's {@code ItemVaultBlock} and Create: Connected's silo block. The broken
 * part's UUID is read directly off the BE (mixin-injected field), no
 * {@code getControllerBE()} hop — that's the §3.9③ footgun during teardown.</p>
 *
 * <p><b>Container-agnostic:</b> the handler does not test for a concrete container class.
 * Any BE implementing {@link VaultIdAccessor} (i.e. any container we have an adapter
 * mixin for) is handled; every other multiblock Create routes through {@code splitMulti}
 * (fluid tanks, …) simply fails the {@code instanceof} and is ignored.</p>
 *
 * <h3>0.5.1 change vs 0.5.0</h3>
 *
 * <ul>
 *   <li>Pool key is now the container's stable UUID, not a geometry-derived
 *       {@code BoundingBox}. Reshape can no longer split/duplicate the pool.</li>
 *   <li><b>Partial-break vs full-teardown detection:</b> vanilla calls splitMulti
 *       for BOTH partial breaks (remaining parts re-form with the same UUID via
 *       extraData, in-container fragments stay alive, the order keeps crafting) and
 *       full teardown (container disappears). Draining on a partial break deletes
 *       the pool while the order is still active — the next fragment scan
 *       re-takes over the order and re-crafts a duplicate batch (the 0.5.1
 *       regression we're fixing here). So we only drain when no sibling container BE
 *       with the same UUID survives anywhere nearby.</li>
 * </ul>
 *
 * <p>Why splitMulti and not splitMultiAndInvalidate: the latter is the deeper
 * chokepoint (also fires for add-block reshape) but it is private static with a
 * package-private SearchCache parameter, and Mixin requires the handler to declare
 * the FULL target parameter list — which would force us to reference the
 * package-private SearchCache type. splitMulti is public and takes only the
 * BlockEntity, so it's cleanly targetable. Reshape is handled separately by the UUID
 * mechanism in the per-container adapter mixins ({@code ItemVaultBlockEntityMixin},
 * {@code mixin.compat.ItemSiloBlockEntityMixin}).</p>
 */
@Mixin(value = ConnectivityHandler.class, remap = false)
public class ConnectivityHandlerMixin {

    // 注意：编译时注解处理器会对下面这行报
    //   Cannot find target method "splitMulti(Lnet/minecraft/world/level/block/entity/BlockEntity;)V"
    // 这是 AP 匹配不了「交类型边界」形参（T extends BlockEntity & IMultiBlockEntityContainer）的误报，
    // 不是描述符写错 —— javap -s 显示该方法的擦除描述符正是
    //   (Lnet/minecraft/world/level/block/entity/BlockEntity;)V
    // 与这里逐字一致，且类里只有这一个 splitMulti（无歧义），所以运行时 Mixin 能正常注入。
    // （TECHNICAL.md §3.9② 说的"AP 的 Cannot find target method 不可当良性"针对的是注
    // private splitMultiAndInvalidate —— 它的参数带 package-private 的 SearchCache，是真注不进去。）
    // ⚠️ 注入点必须是 TAIL，不能用 HEAD —— 这是上游遗留的 bug，本模组修正：
    //   ConnectivityHandler.splitMulti(T) 的实现只是转调
    //       splitMultiAndInvalidate(be, null, false);
    //   （javap 已确认），而"幸存的方块从旧 controller 继承 UUID"这一步
    //   （partControllerBE.getExtraData() → part.setExtraData(...)）就发生在它里面。
    // 所以在 HEAD 时幸存方的 cpi$vaultId 仍是 null，"找同 UUID 的兄弟"必然落空 →
    // 误判成"全拆" → 爆池。上游看起来"能工作"只是因为拆到没有 UUID 的部件时会提前
    // return（那是巧合，不是判定）。改到 TAIL 后 extraData 传递已完成，幸存方已持有同一
    // UUID，判定才真正可靠；同时也保证重新成形后的多方块沿用同一 UUID（池 key 不变，
    // 不会变成孤儿）。
    @Inject(
            method = "splitMulti(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            at = @At("TAIL")
    )
    private static void createPackageInnovation$onVaultSplit(BlockEntity be, CallbackInfo ci) {
        if (be == null) return;
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return;
        // 支持任意被适配过的容器（原版保险库、Create: Connected 的 Item Silo…）：
        // 判据是"实现了 VaultIdAccessor"，而不是某个具体类。
        if (!(be instanceof VaultIdAccessor broken)) return;

        BlockPos brokenPos = be.getBlockPos();
        MinecraftServer server = level.getServer();
        if (server == null) return;

        // Read the UUID directly off the broken BE. We deliberately do NOT use
        // getControllerBE() here — during teardown the controller field can already
        // be nulled (§3.9③ footgun), causing getControllerBE to return the part
        // itself with stale width/height=1. Reading the mixed-in UUID off the BE
        // directly is geometry-independent and reliable.
        UUID vaultId = broken.createPackageInnovation$getVaultId();
        if (vaultId == null) {
            // This container was never used by any repackager (UUID never minted). Nothing
            // in the pool or tracker belongs to it. (PartialOrderTracker might still
            // have a stale entry from a previous incarnation, but that's an orphan we
            // intentionally leave for the legacy-format cleanup to handle.)
            return;
        }

        // CRITICAL: distinguish "full teardown" from "partial break + reform".
        // vanilla calls splitMulti for BOTH:
        //   - full break (every part destroyed) → container gone → drain
        //   - partial break (one part of a multi-block container) → remaining parts
        //     RE-FORM with the same UUID (via extraData inheritance), in-container
        //     fragments stay in the remaining parts, and the order keeps crafting.
        //     Draining here would delete the pool mid-craft, and the next fragment
        //     scan would re-takeover the order and re-craft a duplicate batch —
        //     the 0.5.1 regression.
        //
        // Scan the neighborhood for any surviving container BE with the same UUID. The
        // broken block has already been removed from the chunk by vanilla onRemove
        // (dropContents → removeBlockEntity → splitMulti), so it won't count. If
        // ANY sibling survives, this is a partial break — leave the pool alone and
        // let the re-formed multiblock inherit it.
        if (VaultGeometry.anySiblingVaultWithUuidExists(level, brokenPos, vaultId)) {
            return;
        }

        // Hardening for the "propagation was skipped" hole (see
        // VaultGeometry.adoptUuidOnSurvivingParts): if splitMultiAndInvalidate bailed out on
        // its getControllerBE()/level guard, it never pushed the UUID onto the surviving
        // parts, so the check above cannot see a multiblock that did survive. Look at the
        // immediate neighbours and adopt the UUID onto any live part of the same block; if
        // there is one, the container is only partially broken — keep the pool.
        if (VaultGeometry.adoptUuidOnSurvivingParts(level, brokenPos, be.getBlockState().getBlock(), vaultId)) {
            return;
        }

        // Structural fallback for containers larger than the radius above assumes. Both checks
        // so far scan a fixed cube sized from the VAULT's geometry (3×3×9); Create's fluid tank
        // takes its height from the fluidTankMaxHeight config (default 32) on a 3×3 base, so a
        // tall tank broken part-way up can have every surviving part outside that cube — the
        // checks above would call it "fully gone" and drop a live container's pool. This walk
        // follows the container part-by-part, so it depends on neither geometry nor config.
        // Purely additive: it runs only when the two checks above said "gone", therefore it can
        // only ever prevent a wrong drain, never cause one. It also adopts the UUID onto
        // surviving parts that have none, which is the only route to reshape-safety for
        // containers whose extraData channel Create already occupies (fluid tanks).
        if (VaultGeometry.anyContiguousPartNearby(level, brokenPos, be.getBlockState().getBlock(), vaultId, true)) {
            return;
        }

        // No sibling survives → the multiblock is fully gone. Drain the pool and
        // the tracker so packages and leftover raw materials drop as items.
        SharedPackagePool.get(server).drainAndDrop(vaultId, level, brokenPos);
        PartialOrderTracker.get(server).drainAndDrop(vaultId, level, brokenPos);
        // The key is resolved now: forget its hint (memory + disk) instead of leaving a dead
        // entry for a later chunk load to discover and clear.
        ContainerHintRegistry.forget(server, vaultId);
    }
}
