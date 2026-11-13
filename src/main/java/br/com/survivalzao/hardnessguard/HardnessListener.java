package br.com.survivalzao.hardnessguard;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
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
 * Escuta eventos de mineracao e aplica/remove o AttributeModifier em
 * BLOCK_BREAK_SPEED dinamicamente conforme a regra do bloco e sua origem.
 */
public final class HardnessListener implements Listener {

    private final HardnessGuardPlugin plugin;
    private final Map<UUID, Long> lastWarningMs = new ConcurrentHashMap<>();
    private final Set<UUID> activelyDamaging = ConcurrentHashMap.newKeySet();

    public HardnessListener(HardnessGuardPlugin plugin) {
        this.plugin = plugin;
    }

    private void evaluateBlockForPlayer(Player player, Block block, boolean fromDamageEvent, boolean showWarning) {
        UUID uuid = player.getUniqueId();
        if (block == null || plugin.isExemptPlayer(player)) {
            activelyDamaging.remove(uuid);
            plugin.removeModifier(player);
            return;
        }

        BlockHardnessRule rule = plugin.getMatchingRule(block);
        if (rule == null) {
            activelyDamaging.remove(uuid);
            plugin.removeModifier(player);
            return;
        }

        if (fromDamageEvent) {
            activelyDamaging.add(uuid);
        }

        plugin.applyModifier(player, rule.getSpeedModifier());

        if (!fromDamageEvent) {
            // Caso um clique unico nao evolua para BlockDamageEvent continuo, limpa em 2 ticks
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && !activelyDamaging.contains(uuid)) {
                    plugin.removeModifier(player);
                }
            }, 2L);
        }

        if (showWarning && rule.isActionbarWarning() && !rule.getActionbarMessage().isEmpty()) {
            long now = System.currentTimeMillis();
            long prev = lastWarningMs.getOrDefault(uuid, 0L);
            if (now - prev >= plugin.getActionbarCooldownMs()) {
                lastWarningMs.put(uuid, now);
                plugin.sendActionBar(player, rule.getActionbarMessage());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_BLOCK) {
            evaluateBlockForPlayer(event.getPlayer(), event.getClickedBlock(), false, true);
        } else if (action == Action.LEFT_CLICK_AIR) {
            activelyDamaging.remove(event.getPlayer().getUniqueId());
            plugin.removeModifier(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockDamage(BlockDamageEvent event) {
        evaluateBlockForPlayer(event.getPlayer(), event.getBlock(), true, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDamageAbort(BlockDamageAbortEvent event) {
        activelyDamaging.remove(event.getPlayer().getUniqueId());
        plugin.removeModifier(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        activelyDamaging.remove(event.getPlayer().getUniqueId());
        plugin.removeModifier(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHotbarChange(PlayerItemHeldEvent event) {
        activelyDamaging.remove(event.getPlayer().getUniqueId());
        plugin.removeModifier(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (plugin.isExemptGamemode(event.getNewGameMode())) {
            activelyDamaging.remove(event.getPlayer().getUniqueId());
            plugin.removeModifier(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        activelyDamaging.remove(event.getPlayer().getUniqueId());
        plugin.removeModifier(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        activelyDamaging.remove(uuid);
        lastWarningMs.remove(uuid);
        plugin.removeModifier(event.getPlayer());
    }
}
