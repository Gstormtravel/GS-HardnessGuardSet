package br.com.survivalzao.obsidianguard;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Escuta acoes de mineracao para reduzir em 95% a velocidade de quebra de
 * obsidiana natural ou de geradores com lava/redstone sem afetar KGenerators.
 */
public final class ObsidianHardnessListener implements Listener {

    private final ObsidianGuardPlugin plugin;
    private final Map<UUID, Long> lastWarningMs = new ConcurrentHashMap<>();
    private final Set<UUID> activelyDamaging = ConcurrentHashMap.newKeySet();

    public ObsidianHardnessListener(ObsidianGuardPlugin plugin) {
        this.plugin = plugin;
    }

    private void evaluateBlockForPlayer(Player player, Block block, boolean fromDamageEvent, boolean showWarning) {
        UUID uuid = player.getUniqueId();
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            activelyDamaging.remove(uuid);
            plugin.removePenalty(player);
            return;
        }
        if (plugin.isHardenedObsidian(block)) {
            if (fromDamageEvent) {
                activelyDamaging.add(uuid);
            }
            plugin.applyPenalty(player);
            if (!fromDamageEvent) {
                // Caso o clique nao evolua para mineracao continua (BlockDamageEvent), limpa apos 2 ticks
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && !activelyDamaging.contains(uuid)) {
                        plugin.removePenalty(player);
                    }
                }, 2L);
            }
            if (showWarning) {
                long now = System.currentTimeMillis();
                long prev = lastWarningMs.getOrDefault(uuid, 0L);
                if (now - prev >= plugin.getActionbarCooldownMs()) {
                    lastWarningMs.put(uuid, now);
                    plugin.sendActionBar(player);
                }
            }
        } else {
            activelyDamaging.remove(uuid);
            plugin.removePenalty(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_BLOCK) {
            evaluateBlockForPlayer(event.getPlayer(), event.getClickedBlock(), false, true);
        } else if (action == Action.LEFT_CLICK_AIR) {
            activelyDamaging.remove(event.getPlayer().getUniqueId());
            plugin.removePenalty(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockDamage(BlockDamageEvent event) {
        evaluateBlockForPlayer(event.getPlayer(), event.getBlock(), true, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDamageAbort(BlockDamageAbortEvent event) {
        activelyDamaging.remove(event.getPlayer().getUniqueId());
        plugin.removePenalty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        activelyDamaging.remove(event.getPlayer().getUniqueId());
        plugin.removePenalty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHotbarChange(PlayerItemHeldEvent event) {
        activelyDamaging.remove(event.getPlayer().getUniqueId());
        plugin.removePenalty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() == GameMode.CREATIVE || event.getNewGameMode() == GameMode.SPECTATOR) {
            activelyDamaging.remove(event.getPlayer().getUniqueId());
            plugin.removePenalty(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        activelyDamaging.remove(event.getPlayer().getUniqueId());
        plugin.removePenalty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        activelyDamaging.remove(uuid);
        lastWarningMs.remove(uuid);
        plugin.removePenalty(event.getPlayer());
    }
}
