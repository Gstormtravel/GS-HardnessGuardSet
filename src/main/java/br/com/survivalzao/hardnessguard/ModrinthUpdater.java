package br.com.survivalzao.hardnessguard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Verificador e atualizador assincrono integrado com a API v2 do Modrinth.
 * Faz download validado por hash SHA-512/SHA-1 para a pasta nativa plugins/update/.
 */
public final class ModrinthUpdater implements Listener {

    public static final String DEFAULT_PROJECT_ID = "Lqgp0tpP";
    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/%s/version";
    private static final String MODRINTH_PAGE = "https://modrinth.com/project/%s";

    private final HardnessGuardPlugin plugin;
    private final HttpClient httpClient;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    private volatile String latestVersion;
    private volatile String latestFileName;
    private volatile String downloadUrl;
    private volatile String expectedSha512;
    private volatile String expectedSha1;
    private volatile boolean updateAvailable;
    private volatile String downloadedVersion;

    public ModrinthUpdater(HardnessGuardPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @EventHandler
    public void onAdminJoin(PlayerJoinEvent event) {
        if (!plugin.isUpdateCheckerEnabled() || !plugin.isUpdateNotifyOnJoin() || !updateAvailable) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("hardnessguard.admin")) {
            return;
        }
        String found = latestVersion;
        if (found == null) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (found.equals(downloadedVersion)) {
                player.sendMessage(plugin.color("&6[HardnessGuardSet] &aNova versao &f" + found
                        + " &aja baixada em &fplugins/update/&a! Reinicie o servidor para aplicar."));
            } else {
                player.sendMessage(plugin.color("&6[HardnessGuardSet] &eNova versao disponivel no Modrinth: &a"
                        + found + " &7(atual: &f" + plugin.getDescription().getVersion() + "&7)"));
                player.sendMessage(plugin.color("&7  Use &f/hardnessguard update &7ou acesse: &b"
                        + String.format(Locale.ROOT, MODRINTH_PAGE, plugin.getModrinthProjectId())));
            }
        }, 40L);
    }

    public void checkAsync(CommandSender sender, boolean triggerDownloadIfEnabled, boolean forceDownload) {
        if (!checking.compareAndSet(false, true)) {
            if (sender != null) {
                sender.sendMessage(plugin.color("&e[HardnessGuardSet] Ja existe uma verificacao no Modrinth em andamento."));
            }
            return;
        }

        String projectId = plugin.getModrinthProjectId();
        String currentVersion = plugin.getDescription().getVersion();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ReleaseAsset asset = fetchLatestVersion(projectId);
                if (asset == null) {
                    notifySender(sender, "&e[HardnessGuardSet] Nenhuma versao publicada encontrada no Modrinth (&f" + projectId + "&e).");
                    return;
                }

                this.latestVersion = asset.versionNumber();
                this.latestFileName = asset.fileName();
                this.downloadUrl = asset.url();
                this.expectedSha512 = asset.sha512();
                this.expectedSha1 = asset.sha1();
                this.updateAvailable = isNewerVersion(currentVersion, asset.versionNumber());

                if (!updateAvailable) {
                    notifySender(sender, "&a[HardnessGuardSet] Voce ja esta na ultima versao (&f" + currentVersion + "&a)!");
                    return;
                }

                String pageUrl = String.format(Locale.ROOT, MODRINTH_PAGE, projectId);
                if (sender != null) {
                    notifySender(sender, "&6[HardnessGuardSet] &eNova versao disponivel no Modrinth: &a"
                            + asset.versionNumber() + " &7(atual: &f" + currentVersion + "&7)");
                    notifySender(sender, "&7  Link: &b" + pageUrl);
                } else {
                    plugin.getLogger().info("Nova versao disponivel no Modrinth: " + asset.versionNumber()
                            + " (atual: " + currentVersion + ") -> " + pageUrl);
                }

                if (forceDownload || (triggerDownloadIfEnabled && plugin.isUpdateAutoDownload())) {
                    downloadAsset(sender, asset);
                } else if (sender == null) {
                    plugin.getLogger().info("Execute '/hardnessguard update' (ou ative settings.update-checker.auto-download: true) para baixar em plugins/update/.");
                } else {
                    notifySender(sender, "&7  Use &f/hardnessguard update &7para baixar em &fplugins/update/&7.");
                }
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                if (sender != null) {
                    notifySender(sender, "&c[HardnessGuardSet] Erro ao consultar Modrinth: &f" + msg);
                } else if (plugin.isDebug()) {
                    plugin.getLogger().warning("Falha ao consultar atualizacoes no Modrinth: " + msg);
                }
            } finally {
                checking.set(false);
            }
        });
    }

    private ReleaseAsset fetchLatestVersion(String projectId) throws IOException, InterruptedException {
        String encodedId = URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        String loadersQuery = "?loaders=" + URLEncoder.encode("[\"paper\",\"purpur\"]", StandardCharsets.UTF_8);
        JsonArray arr = requestVersionArray(String.format(Locale.ROOT, MODRINTH_API, encodedId) + loadersQuery);
        if (arr == null || arr.isEmpty()) {
            arr = requestVersionArray(String.format(Locale.ROOT, MODRINTH_API, encodedId));
        }
        if (arr == null || arr.isEmpty()) {
            return null;
        }

        JsonObject fallbackObj = null;
        for (JsonElement elem : arr) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject obj = elem.getAsJsonObject();
            String status = obj.has("status") && !obj.get("status").isJsonNull() ? obj.get("status").getAsString() : "listed";
            if ("archived".equalsIgnoreCase(status) || "rejected".equalsIgnoreCase(status)) {
                continue;
            }
            if (fallbackObj == null) {
                fallbackObj = obj;
            }
            String vType = obj.has("version_type") && !obj.get("version_type").isJsonNull()
                    ? obj.get("version_type").getAsString()
                    : "release";
            if ("release".equalsIgnoreCase(vType)) {
                ReleaseAsset candidate = extractJarAsset(obj);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return fallbackObj != null ? extractJarAsset(fallbackObj) : null;
    }

    private JsonArray requestVersionArray(String endpoint) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Gstormtravel/GS-HardnessGuardSet/" + plugin.getDescription().getVersion())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        JsonElement parsed = JsonParser.parseString(resp.body());
        return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
    }

    private static ReleaseAsset extractJarAsset(JsonObject versionObj) {
        if (!versionObj.has("files") || !versionObj.get("files").isJsonArray()) {
            return null;
        }
        String versionNumber = versionObj.has("version_number") ? versionObj.get("version_number").getAsString() : "";
        if (versionNumber.isBlank()) {
            return null;
        }

        JsonObject chosen = null;
        for (JsonElement fElem : versionObj.getAsJsonArray("files")) {
            if (!fElem.isJsonObject()) {
                continue;
            }
            JsonObject fObj = fElem.getAsJsonObject();
            String name = fObj.has("filename") ? fObj.get("filename").getAsString() : "";
            if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            boolean primary = fObj.has("primary") && fObj.get("primary").getAsBoolean();
            if (primary) {
                chosen = fObj;
                break;
            }
            if (chosen == null) {
                chosen = fObj;
            }
        }
        if (chosen == null || !chosen.has("url")) {
            return null;
        }

        String url = chosen.get("url").getAsString();
        String fileName = chosen.has("filename") ? chosen.get("filename").getAsString() : "GS-HardnessGuardSet.jar";
        String sha512 = null;
        String sha1 = null;
        if (chosen.has("hashes") && chosen.get("hashes").isJsonObject()) {
            JsonObject hashes = chosen.getAsJsonObject("hashes");
            if (hashes.has("sha512")) {
                sha512 = hashes.get("sha512").getAsString();
            }
            if (hashes.has("sha1")) {
                sha1 = hashes.get("sha1").getAsString();
            }
        }
        return new ReleaseAsset(versionNumber, fileName, url, sha512, sha1);
    }

    private void downloadAsset(CommandSender sender, ReleaseAsset asset) {
        if (!downloading.compareAndSet(false, true)) {
            notifySender(sender, "&e[HardnessGuardSet] Download de atualizacao ja em andamento.");
            return;
        }

        try {
            if (asset.versionNumber().equals(downloadedVersion)) {
                notifySender(sender, "&a[HardnessGuardSet] Versao &f" + asset.versionNumber()
                        + " &aja esta pronta em &fplugins/update/&a. Reinicie o servidor para aplicar.");
                return;
            }

            File updateFolder = Bukkit.getUpdateFolderFile();
            if (!updateFolder.exists() && !updateFolder.mkdirs()) {
                throw new IOException("Nao foi possivel criar pasta plugins/update/");
            }

            String targetName = resolveRunningJarName();
            Path tmpFile = updateFolder.toPath().resolve(targetName + ".tmp");
            Path finalFile = updateFolder.toPath().resolve(targetName);

            notifySender(sender, "&e[HardnessGuardSet] Baixando versao &a" + asset.versionNumber() + " &edo Modrinth...");

            HttpRequest req = HttpRequest.newBuilder(URI.create(asset.url()))
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", "Gstormtravel/GS-HardnessGuardSet/" + plugin.getDescription().getVersion())
                    .GET()
                    .build();
            HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(tmpFile));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                Files.deleteIfExists(tmpFile);
                throw new IOException("Falha no download HTTP " + resp.statusCode());
            }

            verifyHash(tmpFile, asset.sha512(), asset.sha1());
            Files.move(tmpFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
            this.downloadedVersion = asset.versionNumber();

            String okMsg = "Atualizacao v" + asset.versionNumber() + " baixada com sucesso em "
                    + finalFile + ". Reinicie o servidor para aplicar.";
            if (sender != null) {
                notifySender(sender, "&a[HardnessGuardSet] &f" + okMsg);
            }
            plugin.getLogger().info(okMsg);
        } catch (Exception ex) {
            String err = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            if (sender != null) {
                notifySender(sender, "&c[HardnessGuardSet] Falha ao baixar atualizacao: &f" + err);
            } else {
                plugin.getLogger().warning("Falha ao baixar atualizacao do Modrinth: " + err);
            }
        } finally {
            downloading.set(false);
        }
    }

    private String resolveRunningJarName() {
        try {
            CodeSource source = ModrinthUpdater.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                File jar = new File(source.getLocation().toURI());
                if (jar.isFile() && jar.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    return jar.getName();
                }
            }
        } catch (URISyntaxException ignored) {
        }
        return "GS-HardnessGuardSet.jar";
    }

    private static void verifyHash(Path file, String expectedSha512, String expectedSha1)
            throws IOException, NoSuchAlgorithmException {
        if (expectedSha512 != null && !expectedSha512.isBlank()) {
            String actual = computeHash(file, "SHA-512");
            if (!actual.equalsIgnoreCase(expectedSha512.trim())) {
                Files.deleteIfExists(file);
                throw new IOException("Checksum SHA-512 invalido do arquivo baixado.");
            }
            return;
        }
        if (expectedSha1 != null && !expectedSha1.isBlank()) {
            String actual = computeHash(file, "SHA-1");
            if (!actual.equalsIgnoreCase(expectedSha1.trim())) {
                Files.deleteIfExists(file);
                throw new IOException("Checksum SHA-1 invalido do arquivo baixado.");
            }
        }
    }

    private static String computeHash(Path file, String algorithm) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static boolean isNewerVersion(String currentRaw, String candidateRaw) {
        int[] current = parseSemVer(currentRaw);
        int[] candidate = parseSemVer(candidateRaw);
        int len = Math.max(current.length, candidate.length);
        for (int i = 0; i < len; i++) {
            int a = i < current.length ? current[i] : 0;
            int b = i < candidate.length ? candidate[i] : 0;
            if (b != a) {
                return b > a;
            }
        }
        return false;
    }

    private static int[] parseSemVer(String raw) {
        if (raw == null || raw.isBlank()) {
            return new int[]{0};
        }
        String clean = raw.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) {
            clean = clean.substring(1);
        }
        int dashIdx = clean.indexOf('-');
        if (dashIdx >= 0) {
            clean = clean.substring(0, dashIdx);
        }
        String[] parts = clean.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9]", "");
            nums[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }
        return nums;
    }

    private void notifySender(CommandSender sender, String legacyText) {
        if (sender == null || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(plugin.color(legacyText)));
    }

    private record ReleaseAsset(String versionNumber, String fileName, String url, String sha512, String sha1) {}
}
