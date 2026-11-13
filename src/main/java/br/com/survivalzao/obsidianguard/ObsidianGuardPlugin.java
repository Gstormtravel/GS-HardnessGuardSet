package br.com.survivalzao.obsidianguard;

import me.kryniowesegryderiusz.kgenerators.api.KGeneratorsAPI;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin leve que aplica penalidade de 95% na velocidade de quebra de blocos de
 * {@link Material#OBSIDIAN} que nao pertencam a um gerador gerenciado pelo KGenerators
 * (ex: obsidiana natural do mundo ou criada por lava + agua/redstone).
 */
public final class ObsidianGuardPlugin extends JavaPlugin {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private NamespacedKey modifierKey;
    private double speedPenalty;
    private boolean actionbarWarning;
    private String actionbarMessage;
    private long actionbarCooldownMs;
    private boolean kgeneratorsEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.modifierKey = new NamespacedKey(this, "natural_obsidian_slow");
        loadSettings();

        this.kgeneratorsEnabled = Bukkit.getPluginManager().isPluginEnabled("KGenerators");
        Bukkit.getPluginManager().registerEvents(new ObsidianHardnessListener(this), this);

        getLogger().info("SurvivalzaoObsidianGuard habilitado! speed-penalty=" + speedPenalty
                + " | KGenerators=" + kgeneratorsEnabled);
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removePenalty(player);
        }
    }

    public void loadSettings() {
        reloadConfig();
        double configured = getConfig().getDouble("speed-penalty", -0.95D);
        // Garante limite seguro (-0.99 a 0.0)
        this.speedPenalty = Math.max(-0.99D, Math.min(0.0D, configured));
        this.actionbarWarning = getConfig().getBoolean("actionbar-warning", true);
        this.actionbarMessage = getConfig().getString("actionbar-message",
                "&cObsidiana Natural/Redstone: &e95% mais resistente &7(use Gerador KGenerators)");
        this.actionbarCooldownMs = Math.max(0L, getConfig().getLong("actionbar-cooldown-ms", 5000L));
    }

    public NamespacedKey getModifierKey() {
        return modifierKey;
    }

    public double getSpeedPenalty() {
        return speedPenalty;
    }

    public boolean isActionbarWarning() {
        return actionbarWarning;
    }

    public String getActionbarMessage() {
        return actionbarMessage;
    }

    public long getActionbarCooldownMs() {
        return actionbarCooldownMs;
    }

    /**
     * Verifica se o bloco e gerenciado por um gerador do KGenerators.
     */
    public boolean isKGeneratorBlock(Block block) {
        if (block == null) {
            return false;
        }
        if (!kgeneratorsEnabled && !Bukkit.getPluginManager().isPluginEnabled("KGenerators")) {
            return false;
        }
        kgeneratorsEnabled = true;
        try {
            return KGeneratorsAPI.getLoadedGeneratorLocation(block.getLocation()) != null;
        } catch (Throwable ex) {
            return false;
        }
    }

    public boolean isHardenedObsidian(Block block) {
        return block != null && block.getType() == Material.OBSIDIAN && !isKGeneratorBlock(block);
    }

    public boolean hasPenalty(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr == null) {
            return false;
        }
        return attr.getModifier(modifierKey) != null;
    }

    public void applyPenalty(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr == null) {
            return;
        }
        AttributeModifier existing = attr.getModifier(modifierKey);
        if (existing != null) {
            if (Double.compare(existing.getAmount(), speedPenalty) == 0) {
                return;
            }
            attr.removeModifier(modifierKey);
        }
        attr.addTransientModifier(new AttributeModifier(
                modifierKey, speedPenalty, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    public void removePenalty(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr == null) {
            return;
        }
        if (attr.getModifier(modifierKey) != null) {
            attr.removeModifier(modifierKey);
        }
    }

    public void sendActionBar(Player player) {
        if (actionbarWarning && actionbarMessage != null && !actionbarMessage.isEmpty()) {
            player.sendActionBar(LEGACY.deserialize(actionbarMessage));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "reload" -> {
                loadSettings();
                sender.sendMessage(LEGACY.deserialize(
                        "&a[ObsidianGuard] Configuracao recarregada! Penalidade: &e"
                                + Math.round(Math.abs(speedPenalty) * 100) + "%"));
                return true;
            }
            case "check" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Comando disponivel apenas in-game.");
                    return true;
                }
                Block target = player.getTargetBlockExact(6, FluidCollisionMode.NEVER);
                if (target == null) {
                    sender.sendMessage(LEGACY.deserialize("&e[ObsidianGuard] Nenhum bloco no alvo (ate 6 blocos)."));
                    return true;
                }
                boolean kgen = isKGeneratorBlock(target);
                boolean hardened = isHardenedObsidian(target);
                sender.sendMessage(LEGACY.deserialize(
                        "&6[ObsidianGuard] &7Bloco: &f" + target.getType().name()
                                + " &7| KGenerator: " + (kgen ? "&aSIM (100%)" : "&cNAO")
                                + " &7| Reducao 95%: " + (hardened ? "&cATIVA (5% vel.)" : "&aINATIVA")
                                + " &7| Modifier ativo: " + (hasPenalty(player) ? "&cSIM" : "&aNAO")));
                return true;
            }
            default -> {
                sender.sendMessage(LEGACY.deserialize(
                        "&6[ObsidianGuard] &aAtivo &7| KGenerators: "
                                + (kgeneratorsEnabled ? "&aCONECTADO" : "&cAUSENTE")
                                + " &7| Penalidade: &e" + speedPenalty));
                return true;
            }
        }
    }
}
