package com.frnc.create_package_innovation.identity;

import com.frnc.create_package_innovation.CreatePackageInnovation;
import com.frnc.create_package_innovation.partial.PartialOrderTracker;
import com.frnc.create_package_innovation.pool.OrphanSweep;
import com.frnc.create_package_innovation.pool.SharedPackagePool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Disk half of {@link ContainerHintRegistry}: remembers, across sessions, where each pool key
 * was last seen, so {@link OrphanSweep} can still decide whether that container still exists
 * after a restart.
 *
 * <h3>Why this exists at all</h3>
 *
 * <p>The hints used to be memory-only, which left one hole in the sweep: restart the game and
 * every hint is gone, so a pool whose container was removed by some path we do not hook (or
 * whose drain was missed right before a crash) can never be resolved and its packages sit in
 * SavedData forever — silently lost rather than dropped back to the player. Persisting the
 * hints closes that, because the sweep re-runs on every chunk load and now has the position
 * to probe again.</p>
 *
 * <h3>Separate file, deliberately</h3>
 *
 * <p>This is its own SavedData with its own id; {@code SharedPackagePool} and
 * {@code PartialOrderTracker} are <b>not</b> touched. That keeps the promise made when the
 * position key was introduced: an existing world's pool entries keep their keys and need no
 * format migration. The prefix matches its two sibling files on purpose.</p>
 *
 * <h3>Write frequency</h3>
 *
 * <p>{@link ContainerHintRegistry#remember} runs on every machine tick, so this class only
 * marks itself dirty when a hint actually appears or changes ({@link #put} compares against
 * the previous value). After the first tick at a position, re-recording the same hint is a
 * map comparison and nothing else.</p>
 */
public class ContainerHintStore extends SavedData {

    private static final String DATA_ID = "gdr_container_hints";

    private final Map<UUID, ContainerHintRegistry.Hint> hints = new HashMap<>();

    public ContainerHintStore() {}

    /** Load from NBT. Entries that are malformed are skipped, never fatal. */
    public static ContainerHintStore load(CompoundTag root) {
        ContainerHintStore store = new ContainerHintStore();
        ListTag list = root.getList("Hints", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("VaultId") || !entry.contains("Dim") || !entry.contains("Pos")) continue;
            ResourceKey<Level> dimension = dimensionOf(entry.getString("Dim"));
            if (dimension == null) continue;
            store.hints.put(entry.getUUID("VaultId"), new ContainerHintRegistry.Hint(
                    dimension, BlockPos.of(entry.getLong("Pos")), entry.getBoolean("Multiblock")));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, ContainerHintRegistry.Hint> e : hints.entrySet()) {
            ContainerHintRegistry.Hint hint = e.getValue();
            CompoundTag entry = new CompoundTag();
            entry.putUUID("VaultId", e.getKey());
            entry.putString("Dim", hint.dimension().location().toString());
            // Packed block position (BlockPos.asLong/of). Version-proof and needs no NBT
            // read/write helper whose signature has moved between MC versions.
            entry.putLong("Pos", hint.pos().asLong());
            entry.putBoolean("Multiblock", hint.multiblock());
            list.add(entry);
        }
        root.put("Hints", list);
        return root;
    }

    public static ContainerHintStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ContainerHintStore::load, ContainerHintStore::new, DATA_ID);
    }

    /** Live internal map. Callers must copy it before mutating (see the registry). */
    public Map<UUID, ContainerHintRegistry.Hint> all() {
        return hints;
    }

    /** @return true if this changed something, i.e. the file now needs writing. */
    public boolean put(UUID key, ContainerHintRegistry.Hint hint) {
        ContainerHintRegistry.Hint previous = hints.put(key, hint);
        if (hint.equals(previous)) return false;
        setDirty();
        return true;
    }

    /** @return true if an entry was actually removed. */
    public boolean remove(UUID key) {
        if (hints.remove(key) == null) return false;
        setDirty();
        return true;
    }

    private static ResourceKey<Level> dimensionOf(String id) {
        try {
            return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(id));
        } catch (RuntimeException e) {
            // A hand-edited or foreign save entry. Not worth failing the whole load over.
            CreatePackageInnovation.LOGGER.warn(
                    "[CPI-POOL] ignoring a container hint with an unparsable dimension '{}'", id);
            return null;
        }
    }
}
