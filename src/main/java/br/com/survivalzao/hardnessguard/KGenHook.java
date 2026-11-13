package br.com.survivalzao.hardnessguard;

import me.kryniowesegryderiusz.kgenerators.api.KGeneratorsAPI;
import org.bukkit.block.Block;

/**
 * Ponte isolada para KGeneratorsAPI, carregada somente quando o plugin KGenerators estiver ativo.
 */
final class KGenHook {

    private KGenHook() {
    }

    static boolean isKGeneratorBlock(Block block) {
        return KGeneratorsAPI.getLoadedGeneratorLocation(block.getLocation()) != null;
    }
}
