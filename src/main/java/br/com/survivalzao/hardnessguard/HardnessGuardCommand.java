package br.com.survivalzao.hardnessguard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * Executor e tab-completer do comando /hardnessguard.
 */
public final class HardnessGuardCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of(
            "status", "check", "list", "setrule", "setblock", "removerule", "removeblock", "reload");
    private static final List<String> GEN_SUGGESTIONS = List.of(
            "NATURAL_GEN",
            "KGEN_GEN",
            "LAVA_GENERATOR_GEN",
            "LAVA-GENERATOR_GEN",
            "PLAYER_PLACED",
            "ALL",
            "NATURAL_GEN,LAVA-GENERATOR_GEN",
            "NATURAL_GEN,LAVA-GENERATOR_GEN,PLAYER_PLACED"
    );
    private static final List<String> MOD_SUGGESTIONS = List.of("-0.95", "-0.75", "-0.50", "0.0", "0.50", "1.00", "2.00");
    private static final List<String> BOOL_SUGGESTIONS = List.of("true", "false");

    private final HardnessGuardPlugin plugin;

    public HardnessGuardCommand(HardnessGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        return switch (sub) {
            case "reload" -> handleReload(sender);
            case "check" -> handleCheck(sender);
            case "list" -> handleList(sender);
            case "setrule", "setblock" -> handleSetRule(sender, label, args, sub);
            case "removerule", "removeblock" -> handleRemoveRule(sender, label, args, sub);
            default -> handleStatus(sender);
        };
    }

    private boolean handleStatus(CommandSender sender) {
        sender.sendMessage(plugin.color(
                "&6[HardnessGuardSet] &aAtivo &7| KGenerators: "
                        + (plugin.isKGeneratorsEnabled() ? "&aCONECTADO" : "&cAUSENTE")
                        + " &7| Regras ativas: &e" + plugin.getActiveRuleCount()
                        + "&7/&f" + plugin.getAllRules().size()
                        + " &7| Pistoes: " + (plugin.isTrackPistons() ? "&aON" : "&cOFF")));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        plugin.loadSettings();
        sender.sendMessage(plugin.color(
                "&a[HardnessGuardSet] Configuracao recarregada! Regras ativas: &e"
                        + plugin.getActiveRuleCount() + "&a."));
        return true;
    }

    private boolean handleCheck(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Comando disponivel apenas in-game.");
            return true;
        }
        Block target = player.getTargetBlockExact(6, FluidCollisionMode.NEVER);
        if (target == null) {
            sender.sendMessage(plugin.color("&e[HardnessGuardSet] Nenhum bloco no alvo (ate 6 blocos)."));
            return true;
        }
        GenerationType origin = plugin.getOriginTracker().resolveOrigin(target);
        BlockHardnessRule rule = plugin.getRule(target.getType());
        boolean applies = rule != null && rule.matchesOrigin(origin);
        Double activeMod = plugin.getActiveModifierAmount(player);

        String ruleDesc;
        if (rule == null) {
            ruleDesc = "&7Vanilla (sem regra)";
        } else if (!rule.isEnabled()) {
            ruleDesc = "&8Desativada";
        } else if (applies) {
            ruleDesc = String.format(Locale.US, "&cATIVA (%.2f -> %s)", rule.getSpeedModifier(), formatSpeedPercent(rule.getSpeedModifier()));
        } else {
            ruleDesc = "&aINATIVA para " + origin.getConfigKey();
        }

        sender.sendMessage(plugin.color(
                "&6[HardnessGuardSet] &7Bloco: &f" + target.getType().name()
                        + " &7| Origem: &b" + origin.getConfigKey()
                        + " &7| Regra: " + ruleDesc
                        + " &7| Modifier ativo: " + (activeMod != null ? "&cSIM (" + activeMod + ")" : "&aNAO")));
        return true;
    }

    private boolean handleList(CommandSender sender) {
        MapHolder list = new MapHolder(plugin.getAllRules());
        if (list.rules.isEmpty()) {
            sender.sendMessage(plugin.color("&e[HardnessGuardSet] Nenhum bloco configurado em config.yml (tudo vanilla)."));
            return true;
        }
        sender.sendMessage(plugin.color("&6[HardnessGuardSet] Regras configuradas (&f" + list.rules.size() + "&6):"));
        for (BlockHardnessRule r : list.rules.values()) {
            String state = r.isEnabled() ? "&aON" : "&cOFF";
            String gens = String.join(",", r.getGenerationNames());
            sender.sendMessage(plugin.color(String.format(Locale.US,
                    " &7- &f%s &8[%s&8] &7speed=&e%.2f &8(%s) &7gens=&b[%s]",
                    r.getMaterial().name(), state, r.getSpeedModifier(), formatSpeedPercent(r.getSpeedModifier()), gens)));
        }
        return true;
    }

    private boolean handleSetRule(CommandSender sender, String label, String[] args, String sub) {
        if (args.length < 4) {
            sender.sendMessage(plugin.color("&cUso: &f/" + label + " " + sub
                    + " <NOME_BLOCO> <SPEED-MODIFIER> <GENERATIONS> [true/false] [actionbar true/false] [ACTIONBAR_MESSAGE...]"));
            sender.sendMessage(plugin.color("&7Ex.: &f/" + label
                    + " setrule OBSIDIAN -0.95 NATURAL_GEN,LAVA-GENERATOR_GEN true true &cObsidiana protegida!"));
            return true;
        }

        String rawBlock = args[1].trim().toUpperCase(Locale.ROOT);
        if (rawBlock.startsWith("MINECRAFT:")) {
            rawBlock = rawBlock.substring("MINECRAFT:".length());
        }
        Material mat = Material.matchMaterial(rawBlock);
        if (mat == null || !mat.isBlock() || mat.isAir()) {
            sender.sendMessage(plugin.color("&c[HardnessGuardSet] Bloco invalido ou nao quebravel: &f" + args[1]));
            return true;
        }

        double speed;
        try {
            speed = Double.parseDouble(args[2].replace(',', '.'));
        } catch (NumberFormatException ex) {
            sender.sendMessage(plugin.color("&c[HardnessGuardSet] speed-modifier invalido: &f" + args[2] + " &7(ex: -0.95 ou 0.75)"));
            return true;
        }
        double clampedSpeed = BlockHardnessRule.clampModifier(speed);

        EnumSet<GenerationType> gens = GenerationType.parseList(List.of(args[3]));
        if (gens.isEmpty()) {
            sender.sendMessage(plugin.color("&c[HardnessGuardSet] Nenhuma origem valida em: &f" + args[3]));
            sender.sendMessage(plugin.color("&7Opcoes: &bNATURAL_GEN, KGEN_GEN, LAVA_GENERATOR_GEN (ou LAVA-GENERATOR_GEN), PLAYER_PLACED, ALL"));
            return true;
        }

        boolean enabled = true;
        if (args.length >= 5) {
            Boolean parsedEnabled = parseBooleanStrict(args[4]);
            if (parsedEnabled == null) {
                sender.sendMessage(plugin.color("&c[HardnessGuardSet] Valor invalido para enabled: &f" + args[4] + " &7(use true ou false)"));
                return true;
            }
            enabled = parsedEnabled;
        }

        boolean actionbar = clampedSpeed < 0.0D;
        if (args.length >= 6) {
            Boolean parsedAb = parseBooleanStrict(args[5]);
            if (parsedAb == null) {
                sender.sendMessage(plugin.color("&c[HardnessGuardSet] Valor invalido para actionbar: &f" + args[5] + " &7(use true ou false)"));
                return true;
            }
            actionbar = parsedAb;
        }

        String msg;
        if (args.length >= 7) {
            StringBuilder sb = new StringBuilder();
            for (int i = 6; i < args.length; i++) {
                if (i > 6) {
                    sb.append(' ');
                }
                sb.append(args[i]);
            }
            msg = sb.toString().trim();
            if (msg.length() >= 2 && msg.startsWith("\"") && msg.endsWith("\"")) {
                msg = msg.substring(1, msg.length() - 1);
            }
        } else {
            BlockHardnessRule existing = plugin.getRule(mat);
            msg = existing != null && !existing.getActionbarMessage().isEmpty()
                    ? existing.getActionbarMessage()
                    : defaultActionbarMessage(mat, clampedSpeed);
        }

        BlockHardnessRule rule = new BlockHardnessRule(mat, enabled, clampedSpeed, gens, actionbar, msg);
        plugin.saveBlockRule(rule);

        sender.sendMessage(plugin.color(String.format(Locale.US,
                "&a[HardnessGuardSet] Regra salva no config.yml! &f%s &8| &7Ativo: %s &8| &7Speed: &e%.2f &8(%s) &8| &7Origens: &b%s &8| &7ActionBar: %s",
                mat.name(),
                enabled ? "&atrue" : "&cfalse",
                clampedSpeed,
                formatSpeedPercent(clampedSpeed),
                String.join(",", rule.getGenerationNames()),
                actionbar ? "&atrue" : "&cfalse")));
        if (actionbar && !msg.isEmpty()) {
            sender.sendMessage(plugin.color("&7  ActionBar Msg: &r" + msg));
        }
        return true;
    }

    private boolean handleRemoveRule(CommandSender sender, String label, String[] args, String sub) {
        if (args.length < 2) {
            sender.sendMessage(plugin.color("&cUso: &f/" + label + " " + sub + " <NOME_BLOCO>"));
            return true;
        }
        String rawBlock = args[1].trim().toUpperCase(Locale.ROOT);
        if (rawBlock.startsWith("MINECRAFT:")) {
            rawBlock = rawBlock.substring("MINECRAFT:".length());
        }
        Material mat = Material.matchMaterial(rawBlock);
        if (mat == null) {
            sender.sendMessage(plugin.color("&c[HardnessGuardSet] Material desconhecido: &f" + args[1]));
            return true;
        }
        if (plugin.removeBlockRule(mat)) {
            sender.sendMessage(plugin.color("&a[HardnessGuardSet] Bloco &f" + mat.name() + " &aremovido do config.yml (voltou ao padrao vanilla)."));
        } else {
            sender.sendMessage(plugin.color("&e[HardnessGuardSet] O bloco &f" + mat.name() + " &enao tinha regra configurada."));
        }
        return true;
    }

    private static String defaultActionbarMessage(Material mat, double speed) {
        long pct = Math.round(Math.abs(speed) * 100.0D);
        if (speed < 0.0D) {
            return "&c⛏ " + mat.name() + ": &e" + pct + "% mais resistente";
        }
        return "&a⚡ " + mat.name() + ": &f" + pct + "% mais rapido para quebrar";
    }

    private static String formatSpeedPercent(double mod) {
        long resultPct = Math.round((1.0D + mod) * 100.0D);
        return resultPct + "% vel.";
    }

    private static Boolean parseBooleanStrict(String raw) {
        if ("true".equalsIgnoreCase(raw) || "sim".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw) || "nao".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private record MapHolder(java.util.Map<Material, BlockHardnessRule> rules) {}

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterPrefix(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("removeblock") || sub.equals("removerule")) && args.length == 2) {
            List<String> configured = plugin.getAllRules().keySet().stream().map(Material::name).toList();
            return filterPrefix(configured, args[1]);
        }
        if (sub.equals("setrule") || sub.equals("setblock")) {
            if (args.length == 2) {
                String prefix = args[1].toUpperCase(Locale.ROOT);
                List<String> matches = new ArrayList<>();
                for (Material m : Material.values()) {
                    if (m.isBlock() && !m.isAir() && !m.name().startsWith("LEGACY_") && m.name().startsWith(prefix)) {
                        matches.add(m.name());
                        if (matches.size() >= 40) {
                            break;
                        }
                    }
                }
                return matches;
            }
            if (args.length == 3) {
                return filterPrefix(MOD_SUGGESTIONS, args[2]);
            }
            if (args.length == 4) {
                return filterPrefix(GEN_SUGGESTIONS, args[3]);
            }
            if (args.length == 5 || args.length == 6) {
                return filterPrefix(BOOL_SUGGESTIONS, args[args.length - 1]);
            }
            if (args.length == 7 && args[6].isEmpty()) {
                return List.of("&c⛏", "&a⚡");
            }
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> options, String rawPrefix) {
        String lower = rawPrefix == null ? "" : rawPrefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
