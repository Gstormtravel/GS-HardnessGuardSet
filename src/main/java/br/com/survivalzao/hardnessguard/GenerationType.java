package br.com.survivalzao.hardnessguard;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Tipos de origem/geracao suportados para filtragem de regras de dureza por bloco.
 */
public enum GenerationType {
    NATURAL_GEN("NATURAL_GEN"),
    KGEN_GEN("KGEN_GEN"),
    LAVA_GENERATOR_GEN("LAVA_GENERATOR_GEN"),
    PLAYER_PLACED("PLAYER_PLACED"),
    ALL("ALL");

    private final String configKey;

    GenerationType(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigKey() {
        return configKey;
    }

    /**
     * Converte nomes e aliases comuns (ex: LAVA-GENERATOR_GEN, NATURAL, PLAYER_GEN, *).
     */
    public static GenerationType fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (norm) {
            case "NATURAL_GEN", "NATURAL", "WORLD_GEN", "VANILLA_NATURAL" -> NATURAL_GEN;
            case "KGEN_GEN", "KGENERATORS_GEN", "KGENERATOR_GEN", "KGEN" -> KGEN_GEN;
            case "LAVA_GENERATOR_GEN", "LAVA_GEN", "LAVAGENERATOR_GEN", "REDSTONE_GEN", "FLUID_GEN" -> LAVA_GENERATOR_GEN;
            case "PLAYER_PLACED", "PLAYER_GEN", "PLAYER" -> PLAYER_PLACED;
            case "ALL", "*" -> ALL;
            default -> null;
        };
    }

    public static EnumSet<GenerationType> parseList(List<String> values) {
        EnumSet<GenerationType> set = EnumSet.noneOf(GenerationType.class);
        if (values == null) {
            return set;
        }
        for (String v : values) {
            if (v == null || v.isBlank()) {
                continue;
            }
            for (String token : v.split(",")) {
                GenerationType parsed = fromString(token);
                if (parsed != null) {
                    set.add(parsed);
                }
            }
        }
        return set;
    }
}
