package br.com.survivalzao.hardnessguard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Rastreia em memoria + PersistentDataContainer dos chunks as posicoes de blocos
 * originados por geradores de lava/redstone (LAVA_GENERATOR_GEN) ou colocados por
 * jogadores (PLAYER_PLACED), sem impacto de I/O bloqueante.
 */
public final class BlockOriginTracker implements Listener {

    private static final BlockFace[] NEIGHBORS = {
        BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final HardnessGuardPlugin plugin;
    private final NamespacedKey lavaGenKey;
    private final NamespacedKey playerPlacedKey;
    private final Map<ChunkCoord, ChunkOriginData> cache = new ConcurrentHashMap<>();

    public BlockOriginTracker(HardnessGuardPlugin plugin) {
        this.plugin = plugin;
        this.lavaGenKey = new NamespacedKey(plugin, "lava_gen_blocks");
        this.playerPlacedKey = new NamespacedKey(plugin, "player_placed_blocks");
    }

    private record ChunkCoord(UUID worldUid, int cx, int cz) {}

    private static final class ChunkOriginData {
        final Set<Long> lavaGen = new HashSet<>();
        final Set<Long> playerPlaced = new HashSet<>();
    }

    private static long packLocal(int x, int y, int z) {
        long rx = x & 0xFL;
        long rz = z & 0xFL;
        long py = ((long) y + 2048L) & 0xFFFFL;
        return (py << 8) | (rx << 4) | rz;
    }

    private static ChunkCoord chunkCoord(Block block) {
        return new ChunkCoord(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);
    }

    private ChunkOriginData getData(Chunk chunk) {
        ChunkCoord key = new ChunkCoord(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        return cache.computeIfAbsent(key, ignored -> loadFromChunk(chunk));
    }

    private ChunkOriginData loadFromChunk(Chunk chunk) {
        ChunkOriginData data = new ChunkOriginData();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        long[] lavaArr = pdc.get(lavaGenKey, PersistentDataType.LONG_ARRAY);
        if (lavaArr != null) {
            for (long packed : lavaArr) {
                data.lavaGen.add(packed);
            }
        }
        long[] playerArr = pdc.get(playerPlacedKey, PersistentDataType.LONG_ARRAY);
        if (playerArr != null) {
            for (long packed : playerArr) {
                data.playerPlaced.add(packed);
            }
        }
        return data;
    }

    private void saveToChunk(Chunk chunk, ChunkOriginData data) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (data.lavaGen.isEmpty()) {
            pdc.remove(lavaGenKey);
        } else {
            pdc.set(lavaGenKey, PersistentDataType.LONG_ARRAY, toPrimitiveArray(data.lavaGen));
        }
        if (data.playerPlaced.isEmpty()) {
            pdc.remove(playerPlacedKey);
        } else {
            pdc.set(playerPlacedKey, PersistentDataType.LONG_ARRAY, toPrimitiveArray(data.playerPlaced));
        }
    }

    private static long[] toPrimitiveArray(Set<Long> set) {
        long[] arr = new long[set.size()];
        int i = 0;
        for (Long v : set) {
            arr[i++] = v;
        }
        return arr;
    }

    /**
     * Determina a origem real do bloco com precedencia:
     * KGEN_GEN -> LAVA_GENERATOR_GEN -> PLAYER_PLACED -> NATURAL_GEN.
     */
    public GenerationType resolveOrigin(Block block) {
        if (block == null) {
            return GenerationType.NATURAL_GEN;
        }
        if (plugin.isKGeneratorBlock(block)) {
            return GenerationType.KGEN_GEN;
        }
        Chunk chunk = block.getChunk();
        ChunkOriginData data = getData(chunk);
        long packed = packLocal(block.getX(), block.getY(), block.getZ());
        if (data.lavaGen.contains(packed)) {
            return GenerationType.LAVA_GENERATOR_GEN;
        }
        if (data.playerPlaced.contains(packed)) {
            return GenerationType.PLAYER_PLACED;
        }
        return GenerationType.NATURAL_GEN;
    }

    public void markOrigin(Block block, GenerationType origin) {
        if (block == null || origin == null) {
            return;
        }
        Chunk chunk = block.getChunk();
        ChunkOriginData data = getData(chunk);
        long packed = packLocal(block.getX(), block.getY(), block.getZ());
        boolean changed = false;
        if (origin == GenerationType.LAVA_GENERATOR_GEN) {
            changed |= data.playerPlaced.remove(packed);
            changed |= data.lavaGen.add(packed);
        } else if (origin == GenerationType.PLAYER_PLACED) {
            changed |= data.lavaGen.remove(packed);
            changed |= data.playerPlaced.add(packed);
        }
        if (changed) {
            saveToChunk(chunk, data);
        }
    }

    public void clearOrigin(Block block) {
        if (block == null) {
            return;
        }
        Chunk chunk = block.getChunk();
        ChunkOriginData data = getData(chunk);
        long packed = packLocal(block.getX(), block.getY(), block.getZ());
        boolean changed = data.lavaGen.remove(packed) | data.playerPlaced.remove(packed);
        if (changed) {
            saveToChunk(chunk, data);
        }
    }

    private static boolean isFluidOrGeneratorForm(Block oldBlock, Material newType) {
        Material oldType = oldBlock.getType();
        if (oldType == Material.LAVA || oldType == Material.WATER || oldType == Material.REDSTONE_WIRE) {
            return true;
        }
        if (newType == Material.COBBLESTONE || newType == Material.STONE
                || newType == Material.OBSIDIAN || newType == Material.BASALT) {
            for (BlockFace face : NEIGHBORS) {
                Material adj = oldBlock.getRelative(face).getType();
                if (adj == Material.LAVA || adj == Material.WATER || adj == Material.BLUE_ICE) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Block block = event.getBlock();
        BlockState newState = event.getNewState();
        Material newType = newState.getType();
        if (!plugin.hasRuleFor(newType)) {
            return;
        }
        if (plugin.isKGeneratorBlock(block)) {
            return;
        }
        if (isFluidOrGeneratorForm(block, newType)) {
            markOrigin(block, GenerationType.LAVA_GENERATOR_GEN);
            if (plugin.isDebug()) {
                plugin.getLogger().info("[Debug] Marcado LAVA_GENERATOR_GEN em "
                        + block.getWorld().getName() + ":" + block.getX() + "," + block.getY() + "," + block.getZ()
                        + " (" + newType + ")");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!plugin.hasRuleFor(block.getType())) {
            clearOrigin(block);
            return;
        }
        if (plugin.isKGeneratorBlock(block)) {
            clearOrigin(block);
            return;
        }
        markOrigin(block, GenerationType.PLAYER_PLACED);
        if (plugin.isDebug()) {
            plugin.getLogger().info("[Debug] Marcado PLAYER_PLACED em "
                    + block.getWorld().getName() + ":" + block.getX() + "," + block.getY() + "," + block.getZ()
                    + " (" + block.getType() + ")");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        clearOrigin(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        clearOrigin(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) {
            clearOrigin(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) {
            clearOrigin(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!plugin.isTrackPistons()) {
            return;
        }
        shiftMovedBlocks(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!plugin.isTrackPistons()) {
            return;
        }
        shiftMovedBlocks(event.getBlocks(), event.getDirection());
    }

    private record MovedTag(Block destination, GenerationType origin) {}

    private void shiftMovedBlocks(List<Block> blocks, BlockFace direction) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        List<MovedTag> pending = new ArrayList<>();
        for (Block oldBlock : blocks) {
            GenerationType origin = resolveTrackedNonNatural(oldBlock);
            if (origin != null) {
                pending.add(new MovedTag(oldBlock.getRelative(direction), origin));
                clearOrigin(oldBlock);
            }
        }
        for (MovedTag moved : pending) {
            markOrigin(moved.destination(), moved.origin());
        }
    }

    private GenerationType resolveTrackedNonNatural(Block block) {
        ChunkOriginData data = getData(block.getChunk());
        long packed = packLocal(block.getX(), block.getY(), block.getZ());
        if (data.lavaGen.contains(packed)) {
            return GenerationType.LAVA_GENERATOR_GEN;
        }
        if (data.playerPlaced.contains(packed)) {
            return GenerationType.PLAYER_PLACED;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk c = event.getChunk();
        cache.remove(new ChunkCoord(c.getWorld().getUID(), c.getX(), c.getZ()));
    }

    public void clearCache() {
        cache.clear();
    }
}
