package br.com.survivalzao.hardnessguard;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin GS-HardnessGuardSet: controla modificadores de velocidade de quebra
 * (Attribute.BLOCK_BREAK_SPEED) para qualquer bloco configurado em config.yml
 * de acordo com a sua origem/geracao (NATURAL_GEN, KGEN_GEN, LAVA_GENERATOR_GEN,
 * PLAYER_PLACED ou ALL).
 */
public final class HardnessGuardPlugin extends JavaPlugin {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private static final String CONFIG_HEADER = """
            # ==============================================================================
            # GS-HardnessGuardSet - Configuracao de Dureza e Velocidade de Quebra
            # ==============================================================================
            # Qualquer bloco do Minecraft NAO listado na secao 'blocks:' abaixo continuara
            # funcionando exatamente da forma padrao (vanilla), sem nenhum impacto no TPS.
            #
            # TIPOS DE GERACAO SUPORTADOS EM 'generations' (use um ou varios na lista):
            #   - NATURAL_GEN         : Blocos naturais do mundo, cavernas, estruturas, portais, etc.
            #   - KGEN_GEN            : Blocos gerados dentro de geradores do plugin KGenerators.
            #   - LAVA_GENERATOR_GEN  : Blocos gerados por lava + agua/redstone (alias: LAVA-GENERATOR_GEN).
            #   - PLAYER_PLACED       : Blocos colocados manualmente por jogadores.
            #   - ALL                 : Aplica em TODAS as origens acima sem distincao.
            #
            # VALORES DE 'speed-modifier' (Modificador no atributo BLOCK_BREAK_SPEED):
            #   - Negativos (-0.99 a -0.01): DEIXA MAIS DIFICIL (mais lento para quebrar).
            #       Ex.: -0.95 = 95% mais lento (5% da velocidade / 20x mais demorado)
            #       Ex.: -0.50 = 50% mais lento (50% da velocidade / 2x mais demorado)
            #   - Positivos (+0.01 a +10.0): DEIXA MAIS FACIL (mais rapido para quebrar).
            #       Ex.: +0.50 = 50% mais rapido
            #       Ex.: +1.00 = 2x mais rapido (metade do tempo)
            #
            # Tambem e possivel configurar/alterar regras pelo chat do servidor com:
            #   /hardnessguard setrule <BLOCO> <SPEED-MODIFIER> <GENERATIONS> [true/false] [actionbar true/false] [MENSAGEM]
            # ==============================================================================
            """;

    private NamespacedKey modifierKey;
    private BlockOriginTracker originTracker;
    private final Map<Material, BlockHardnessRule> rules = new EnumMap<>(Material.class);
    private final EnumSet<GameMode> exemptGamemodes = EnumSet.of(GameMode.CREATIVE, GameMode.SPECTATOR);

    private boolean useBypassPermission;
    private long actionbarCooldownMs;
    private boolean trackPistons;
    private boolean debug;
    private boolean kgeneratorsEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.modifierKey = new NamespacedKey(this, "block_hardness_mod");
        this.originTracker = new BlockOriginTracker(this);
        this.kgeneratorsEnabled = Bukkit.getPluginManager().isPluginEnabled("KGenerators");

        loadSettings();

        Bukkit.getPluginManager().registerEvents(originTracker, this);
        Bukkit.getPluginManager().registerEvents(new HardnessListener(this), this);

        PluginCommand cmd = getCommand("hardnessguard");
        if (cmd != null) {
            HardnessGuardCommand executor = new HardnessGuardCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getLogger().info("GS-HardnessGuardSet v" + getDescription().getVersion()
                + " habilitado! Regras ativas: " + getActiveRuleCount()
                + " | KGenerators=" + kgeneratorsEnabled);
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeModifier(player);
        }
        if (originTracker != null) {
            originTracker.clearCache();
        }
    }

    public void loadSettings() {
        reloadConfig();

        for (Player p : Bukkit.getOnlinePlayers()) {
            removeModifier(p);
        }

        exemptGamemodes.clear();
        List<String> gmList = getConfig().getStringList("settings.exempt-gamemodes");
        if (gmList.isEmpty()) {
            exemptGamemodes.add(GameMode.CREATIVE);
            exemptGamemodes.add(GameMode.SPECTATOR);
        } else {
            for (String s : gmList) {
                try {
                    exemptGamemodes.add(GameMode.valueOf(s.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        this.useBypassPermission = getConfig().getBoolean("settings.use-bypass-permission", false);
        this.actionbarCooldownMs = Math.max(0L, getConfig().getLong("settings.actionbar-cooldown-ms", 5000L));
        this.trackPistons = getConfig().getBoolean("settings.track-pistons", true);
        this.debug = getConfig().getBoolean("settings.debug", false);

        rules.clear();
        ConfigurationSection blocksSec = getConfig().getConfigurationSection("blocks");
        if (blocksSec != null) {
            for (String key : blocksSec.getKeys(false)) {
                Material mat = Material.matchMaterial(key.trim().toUpperCase(Locale.ROOT));
                if (mat == null || !mat.isBlock() || mat.isAir()) {
                    getLogger().warning("Material invalido/nao-bloco ignorado em blocks." + key);
                    continue;
                }
                ConfigurationSection sec = blocksSec.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                boolean enabled = sec.getBoolean("enabled", true);
                double mod = sec.getDouble("speed-modifier", 0.0D);
                EnumSet<GenerationType> gens = GenerationType.parseList(sec.getStringList("generations"));
                if (gens.isEmpty()) {
                    String single = sec.getString("generations", "");
                    if (!single.isBlank()) {
                        gens = GenerationType.parseList(List.of(single));
                    }
                }
                boolean abWarn = sec.getBoolean("actionbar-warning", mod < 0.0D);
                String abMsg = sec.getString("actionbar-message", "");
                rules.put(mat, new BlockHardnessRule(mat, enabled, mod, gens, abWarn, abMsg));
            }
        }
    }

    public void saveBlockRule(BlockHardnessRule rule) {
        String path = "blocks." + rule.getMaterial().name();
        getConfig().set(path + ".enabled", rule.isEnabled());
        getConfig().set(path + ".speed-modifier", rule.getSpeedModifier());
        getConfig().set(path + ".generations", rule.getGenerationNames());
        getConfig().set(path + ".actionbar-warning", rule.isActionbarWarning());
        getConfig().set(path + ".actionbar-message", rule.getActionbarMessage());
        writeCommentedConfig();
        loadSettings();
    }

    public boolean removeBlockRule(Material mat) {
        String path = "blocks." + mat.name();
        if (!getConfig().contains(path)) {
            return false;
        }
        getConfig().set(path, null);
        writeCommentedConfig();
        loadSettings();
        return true;
    }

    private void writeCommentedConfig() {
        File cfgFile = new File(getDataFolder(), "config.yml");
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        String body = getConfig().saveToString().replaceFirst("(?s)^(#[^\\r\\n]*\\r?\\n|\\r?\\n)+", "");
        try {
            Files.writeString(cfgFile.toPath(), CONFIG_HEADER + "\n" + body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning("Falha ao salvar config.yml comentado, usando saveConfig padrao: " + e.getMessage());
            saveConfig();
        }
    }

    public BlockOriginTracker getOriginTracker() {
        return originTracker;
    }

    public boolean hasRuleFor(Material mat) {
        return mat != null && rules.containsKey(mat);
    }

    public BlockHardnessRule getRule(Material mat) {
        return mat == null ? null : rules.get(mat);
    }

    public Map<Material, BlockHardnessRule> getAllRules() {
        return Collections.unmodifiableMap(rules);
    }

    public int getActiveRuleCount() {
        int count = 0;
        for (BlockHardnessRule r : rules.values()) {
            if (r.isEnabled()) {
                count++;
            }
        }
        return count;
    }

    public BlockHardnessRule getMatchingRule(Block block) {
        if (block == null) {
            return null;
        }
        BlockHardnessRule rule = rules.get(block.getType());
        if (rule == null || !rule.isEnabled()) {
            return null;
        }
        GenerationType origin = originTracker.resolveOrigin(block);
        return rule.matchesOrigin(origin) ? rule : null;
    }

    public boolean isExemptPlayer(Player player) {
        if (player == null) {
            return true;
        }
        if (exemptGamemodes.contains(player.getGameMode())) {
            return true;
        }
        return useBypassPermission && player.hasPermission("hardnessguard.bypass");
    }

    public boolean isExemptGamemode(GameMode mode) {
        return mode != null && exemptGamemodes.contains(mode);
    }

    public long getActionbarCooldownMs() {
        return actionbarCooldownMs;
    }

    public boolean isTrackPistons() {
        return trackPistons;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isKGeneratorsEnabled() {
        return kgeneratorsEnabled;
    }

    /**
     * Verifica em tempo real se a localizacao pertence a um gerador do KGenerators.
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
            return KGenHook.isKGeneratorBlock(block);
        } catch (Throwable ex) {
            return false;
        }
    }

    public Double getActiveModifierAmount(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr == null) {
            return null;
        }
        AttributeModifier mod = attr.getModifier(modifierKey);
        return mod == null ? null : mod.getAmount();
    }

    public void applyModifier(Player player, double amount) {
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr == null) {
            return;
        }
        AttributeModifier existing = attr.getModifier(modifierKey);
        if (existing != null) {
            if (Double.compare(existing.getAmount(), amount) == 0) {
                return;
            }
            attr.removeModifier(modifierKey);
        }
        if (Double.compare(amount, 0.0D) == 0) {
            return;
        }
        attr.addTransientModifier(new AttributeModifier(
                modifierKey, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    public void removeModifier(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr == null) {
            return;
        }
        if (attr.getModifier(modifierKey) != null) {
            attr.removeModifier(modifierKey);
        }
    }

    public Component color(String raw) {
        return LEGACY.deserialize(raw == null ? "" : raw);
    }

    public void sendActionBar(Player player, String rawMessage) {
        if (rawMessage != null && !rawMessage.isEmpty()) {
            player.sendActionBar(color(rawMessage));
        }
    }
}
