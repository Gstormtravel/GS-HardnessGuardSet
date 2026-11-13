package br.com.survivalzao.hardnessguard;

import java.util.EnumSet;
import java.util.List;
import org.bukkit.Material;

/**
 * Regra imutavel de dureza/velocidade para um Material especifico.
 */
public final class BlockHardnessRule {

    private final Material material;
    private final boolean enabled;
    private final double speedModifier;
    private final EnumSet<GenerationType> generations;
    private final boolean actionbarWarning;
    private final String actionbarMessage;

    public BlockHardnessRule(
            Material material,
            boolean enabled,
            double speedModifier,
            EnumSet<GenerationType> generations,
            boolean actionbarWarning,
            String actionbarMessage
    ) {
        this.material = material;
        this.enabled = enabled;
        this.speedModifier = clampModifier(speedModifier);
        this.generations = generations == null || generations.isEmpty()
                ? EnumSet.noneOf(GenerationType.class)
                : EnumSet.copyOf(generations);
        this.actionbarWarning = actionbarWarning;
        this.actionbarMessage = actionbarMessage == null ? "" : actionbarMessage;
    }

    public static double clampModifier(double raw) {
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            return 0.0D;
        }
        return Math.max(-0.99D, Math.min(10.0D, raw));
    }

    public Material getMaterial() {
        return material;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getSpeedModifier() {
        return speedModifier;
    }

    public EnumSet<GenerationType> getGenerations() {
        return EnumSet.copyOf(generations);
    }

    public List<String> getGenerationNames() {
        return generations.stream().map(GenerationType::getConfigKey).toList();
    }

    public boolean matchesOrigin(GenerationType origin) {
        if (!enabled || origin == null || generations.isEmpty()) {
            return false;
        }
        return generations.contains(GenerationType.ALL) || generations.contains(origin);
    }

    public boolean isActionbarWarning() {
        return actionbarWarning;
    }

    public String getActionbarMessage() {
        return actionbarMessage;
    }
}
