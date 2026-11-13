<div align="center">

<img src="assets/banner.jpg" alt="GS-HardnessGuardSet Banner" width="100%" />

<br/>

<img src="assets/icon.jpg" alt="GS-HardnessGuardSet Icon" width="128" />

# GS-HardnessGuardSet

**Controle granular de dureza e velocidade de quebra de qualquer bloco do Minecraft por tipo de geração.**

[![Versão](https://img.shields.io/badge/Versão-2.0.1-7C3AED?style=for-the-badge)](https://github.com/Gstormtravel/GS-HardnessGuardSet/releases/tag/v2.0.1)
[![Modrinth](https://img.shields.io/badge/Modrinth-gs--hardnessguardset-1BD96A?style=for-the-badge&logo=modrinth)](https://modrinth.com/project/gs-hardnessguardset)
[![Paper / Purpur](https://img.shields.io/badge/Paper%20%2F%20Purpur-1.21%2B%20%7C%2026.3-10B981?style=for-the-badge)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21%2B-EA580C?style=for-the-badge)](https://adoptium.net/)
[![Reddit](https://img.shields.io/badge/Reddit-r%2FGSHardnessGuardSet-FF4500?style=for-the-badge)](https://www.reddit.com/r/GSHardnessGuardSet/)

</div>

---

# Wiki — **GS-HardnessGuardSet**

Plugin leve para **Paper / Purpur `1.21+` (incluindo `26.3`)** que permite configurar a resistência (velocidade de quebra) de **qualquer bloco do Minecraft** via `config.yml` ou diretamente pelo chat do jogo, filtrando por **origem/tipo de geração do bloco** sem impactar o TPS do servidor.

---

## 1. Requisitos e Compatibilidade

* **Servidor:** Paper, Purpur ou forks compatíveis (`api-version: 1.21`, Java `21+`).
* **Dependências opcionais (`softdepend`):** `KGenerators` *(necessário apenas se utilizar ou excluir a origem `KGEN_GEN`)*.
* **Comportamento para blocos não listados:** Qualquer material que **não** estiver configurado na seção `blocks:` do `config.yml` segue 100% o comportamento padrão vanilla, sem checagens extras.

---

## 2. Instalação

1. Pare o servidor.
2. Coloque `GS-HardnessGuardSet-2.0.0.jar` na pasta `plugins/` e inicie o servidor para gerar `plugins/GS-HardnessGuardSet/config.yml`.
3. Edite o `config.yml` ou configure diretamente no chat com `/hardnessguard setrule ...` e aplique quando desejar com `/hardnessguard reload`.

---

## 3. Como Funciona a Dureza (`speed-modifier`)

O `GS-HardnessGuardSet` aplica um modificador transitório no atributo nativo `Attribute.BLOCK_BREAK_SPEED` (`MULTIPLY_SCALAR_1`) enquanto o jogador está minerando um bloco que corresponda à regra configurada — removendo o modificador automaticamente ao soltar o clique, quebrar o bloco, trocar de slot, mudar de gamemode ou desconectar.

<div align="center">
  <img src="assets/formula_speed.png" alt="Velocidade Final = 1.0 + speed-modifier" width="680" />
</div>

| `speed-modifier` | Velocidade Resultante | Tempo para Quebrar (vs. Vanilla) | Uso Típico |
| :--- | :--- | :--- | :--- |
| `-0.95` | `5%` | **20x mais demorado** (+1900% tempo) | Obsidiana natural/redstone (anti-farm gratuita) |
| `-0.75` | `25%` | **4x mais demorado** | Minérios raros / blocos de proteção |
| `-0.50` | `50%` | **2x mais demorado** | Progressão de dificuldade intermediária |
| `0.00` | `100%` | Igual ao vanilla | Desativado / referência |
| `+0.50` | `150%` | **1,5x mais rápido** (~33% menos tempo) | Aceleração leve de blocos |
| `+1.00` | `200%` | **2x mais rápido** (metade do tempo) | Facilitar escavação / recompensar geradores |
| `+3.00` | `400%` | **4x mais rápido** (25% do tempo) | Quebra super-rápida em geradores |

> **Faixa aceita:** `-0.99` (mais lento seguro) até `+10.0` (mais rápido seguro). Ferramentas, `Eficiência` e `Pressa` (`Haste`) continuam funcionando proporcionalmente sobre a velocidade modificada.

---

## 4. Tipos de Geração Suportados (`generations`)

Em cada bloco dentro de `blocks:`, você escolhe uma lista de origens onde o modificador será ativado:

| Origem | Alias Aceito | O que Identifica |
| :--- | :--- | :--- |
| `NATURAL_GEN` | `NATURAL` | Blocos gerados naturalmente no mundo (terreno, cavernas, estruturas, portais, End, etc.) que não pertençam às demais categorias rastreadas. |
| `KGEN_GEN` | `KGENERATORS_GEN`, `KGEN` | Blocos pertencentes a geradores configurados no plugin **KGenerators**. |
| `LAVA_GENERATOR_GEN` | `LAVA-GENERATOR_GEN`, `LAVA_GEN` | Blocos gerados por contato de lava com água/redstone/gelo azul (ex.: geradores automáticos de pedra, cobblestone, basalto ou obsidiana com redstone). |
| `PLAYER_PLACED` | `PLAYER_GEN`, `PLAYER` | Blocos colocados manualmente por um jogador. |
| `ALL` | `*` | Aplica em **todas** as origens acima sem distinção. |

### Notas Importantes sobre Rastreamento e Persistência
* Os blocos criados por `LAVA_GENERATOR_GEN` e `PLAYER_PLACED` (quando o material possui regra ativa no `config.yml`) são gravados de forma compacta no `PersistentDataContainer` do próprio chunk — **sobrevivendo a reinícios do servidor**.
* Quando `settings.track-pistons: true`, blocos empurrados ou puxados por pistões preservam sua etiqueta de origem no novo local.
* Blocos já existentes no mapa **antes** da instalação do `GS-HardnessGuardSet` (exceto geradores `KGenerators`, que são reconhecidos retroativamente pela API) são tratados como `NATURAL_GEN`.

---

## 5. Referência Completa do `config.yml`

```yaml
settings:
  exempt-gamemodes: [CREATIVE, SPECTATOR]
  use-bypass-permission: false
  actionbar-cooldown-ms: 5000
  track-pistons: true
  debug: false
  update-checker:
    enabled: true
    auto-download: false
    notify-on-join: true
    project-id: "Lqgp0tpP"

blocks:
  NOME_DO_MATERIAL_MINECRAFT:
    enabled: true
    speed-modifier: -0.95
    generations:
      - NATURAL_GEN
      - LAVA-GENERATOR_GEN
    actionbar-warning: true
    actionbar-message: "&cMensagem opcional na ActionBar usando &cores"
```

### Campos em `settings:`
* `exempt-gamemodes`: Lista de `GameMode` onde nenhum modificador será aplicado.
* `use-bypass-permission`: Quando `true`, jogadores com `hardnessguard.bypass` ignoram todos os modificadores.
* `actionbar-cooldown-ms`: Intervalo mínimo (em milissegundos) entre envios da ActionBar para o mesmo jogador.
* `track-pistons`: Mantém o rastreamento de `LAVA_GENERATOR_GEN` e `PLAYER_PLACED` quando blocos são movidos por pistão.
* `debug`: Ativa logs detalhados no console para verificação de eventos.
* `update-checker.enabled`: Consulta a API v2 do Modrinth no boot e a cada 12 horas por novas versões.
* `update-checker.auto-download`: Quando `true`, baixa automaticamente novos `.jar` validados por SHA-512 para `plugins/update/` (aplicados no próximo restart).
* `update-checker.notify-on-join`: Notifica jogadores com `hardnessguard.admin` ao entrar quando houver atualização.
* `update-checker.project-id`: ID/slug do projeto no Modrinth (padrão: `Lqgp0tpP`).

### Campos em `blocks.<MATERIAL>:`
* `<MATERIAL>`: Nome oficial do `Material` no Paper/Bukkit (ex.: `OBSIDIAN`, `CRYING_OBSIDIAN`, `DEEPSLATE`, `ANCIENT_DEBRIS`, `COBBLESTONE`, `BASALT`, `NETHERRACK`).
* `enabled`: `true` ou `false` — permite desativar rapidamente uma regra sem apagá-la do arquivo.
* `speed-modifier`: Número decimal entre `-0.99` e `+10.0`.
* `generations`: Lista com uma ou mais origens (`NATURAL_GEN`, `KGEN_GEN`, `LAVA_GENERATOR_GEN` / `LAVA-GENERATOR_GEN`, `PLAYER_PLACED`, `ALL`).
* `actionbar-warning`: `true` ou `false` — exibe aviso discreto acima da hotbar ao começar a minerar um bloco afetado.
* `actionbar-message`: Texto formatado com códigos `&` (`&c`, `&e`, `&7`, etc.).

---

## 6. Exemplos Práticos Prontos

### Exemplo 1: Obsidiana Hardcore fora do KGenerators *(Padrão)*
```yaml
  OBSIDIAN:
    enabled: true
    speed-modifier: -0.95
    generations:
      - NATURAL_GEN
      - LAVA-GENERATOR_GEN
      - PLAYER_PLACED
    actionbar-warning: true
    actionbar-message: "&c⛏ Obsidiana protegida: &e95% mais resistente &7(use Gerador KGenerators)"
```

### Exemplo 2: Gerador Vanilla de Pedra mais lento e KGenerators 2x mais rápido
```yaml
  COBBLESTONE:
    enabled: true
    speed-modifier: -0.40
    generations:
      - LAVA-GENERATOR_GEN
    actionbar-warning: false

  OBSIDIAN:
    enabled: true
    speed-modifier: 1.00
    generations:
      - KGEN_GEN
    actionbar-warning: true
    actionbar-message: "&a⚡ Bônus KGenerators: &fQuebra 2x mais rápida!"
```

### Exemplo 3: Escavação Facilitada em Deepslate Natural
```yaml
  DEEPSLATE:
    enabled: true
    speed-modifier: 0.75
    generations:
      - NATURAL_GEN
    actionbar-warning: false
```

---

## 7. Comandos e Permissões

| Comando | Aliases | Descrição | Permissão |
| :--- | :--- | :--- | :--- |
| `/hardnessguard status` | `/hguard`, `/gsguard`, `/obsidianguard` | Mostra status do plugin, integração com `KGenerators` e total de regras ativas. | `hardnessguard.admin` |
| `/hardnessguard check` | — | Inspeciona o bloco para o qual você está olhando (Material, origem detectada, regra e modifier ativo). | `hardnessguard.admin` |
| `/hardnessguard list` | — | Lista todos os blocos atualmente configurados e seus modificadores. | `hardnessguard.admin` |
| `/hardnessguard setrule <BLOCO> <SPEED-MODIFIER> <GENERATIONS> [true/false] [actionbar true/false] <ACTIONBAR_MESSAGE>` | `setblock` | Cria ou altera diretamente uma regra no `config.yml` pelo chat, incluindo estado ativo, aviso na ActionBar e mensagem customizada (`<GENERATIONS>` separadas por vírgula). | `hardnessguard.admin` |
| `/hardnessguard removerule <BLOCO>` | `removeblock` | Remove a regra de um bloco do `config.yml` restaurando o comportamento padrão vanilla. | `hardnessguard.admin` |
| `/hardnessguard reload` | — | Recarrega o `config.yml` e atualiza as regras em memória sem reiniciar o servidor. | `hardnessguard.admin` |
| `/hardnessguard update [check\|download]` | — | Verifica atualizações no Modrinth (`Lqgp0tpP`) e baixa o `.jar` validado por SHA-512 para `plugins/update/`. | `hardnessguard.admin` |

### Exemplos de uso via chat
* `/hardnessguard setrule OBSIDIAN -0.95 NATURAL_GEN,LAVA-GENERATOR_GEN,PLAYER_PLACED true true &c⛏ Obsidiana protegida!`
* `/hardnessguard setrule DEEPSLATE 0.75 NATURAL_GEN true false`
* `/hardnessguard setrule ANCIENT_DEBRIS -0.60 ALL true true &e⚠ Debris endurecido nesta região!`
* `/hardnessguard removerule DEEPSLATE`

### Permissões
* **`hardnessguard.admin`** *(padrão: `op`)*: Acesso completo aos comandos administrativos.
* **`hardnessguard.bypass`** *(padrão: `false`)*: Ignora modificadores quando `settings.use-bypass-permission: true`.

---

## 8. Auto-Publicação no Modrinth via GitHub Actions (PAT)

Este repositório inclui o workflow [`.github/workflows/modrinth-publish.yml`](.github/workflows/modrinth-publish.yml). Sempre que uma nova **Release** for publicada no GitHub com o `.jar` compilado em anexo, o GitHub Actions envia automaticamente a nova versão para o Modrinth usando o secret `MODRINTH_TOKEN` (**Personal Access Token / PAT** criado em `https://modrinth.com/settings/pats` e cadastrado em *Repository Settings → Secrets and variables → Actions*).
