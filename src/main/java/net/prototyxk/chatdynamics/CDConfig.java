package net.prototyxk.chatdynamics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class CDConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(CDConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("chatdynamics");

    public static Map<String, String> questions = new HashMap<>();
    public static List<String> wordsMc  = new ArrayList<>();
    public static List<String> wordsHover = new ArrayList<>();

    public static boolean rewardItemValidated = false;

    public static int delaiEntreEvents = 12000;
    public static int delaiTimeout     = 1200;

    // On stocke uniquement la ResourceLocation — l'Item est résolu TARD
    // (après que tous les mods soient enregistrés) via getItemRecompense()
    public static ResourceLocation rewardItemLocation = ResourceLocation.tryParse("minecraft:diamond");
    public static int quantiteRecompense = 1;

    public static Map<UUID, Integer> leaderboard = Collections.synchronizedMap(new HashMap<>());
    public static Map<UUID, String>  nomsCache   = Collections.synchronizedMap(new HashMap<>());

    /**
     * Résout la ResourceLocation en Item au moment de l'appel.
     * Doit être appelé APRÈS que tous les mods soient chargés (ex: ServerStartedEvent),
     * jamais depuis modloading-worker.
     */
    public static Item getItemRecompense() {
        if (rewardItemValidated) {  // ✅ NOUVEAU
            Item found = ForgeRegistries.ITEMS.getValue(rewardItemLocation);
            if (found != null && found != Items.AIR) {
                return found;
            }
        }
        LOGGER.warn("[ChatDynamics] Item '{}' introuvable (validated={}). Fallback diamond.",
                rewardItemLocation, rewardItemValidated);
        return Items.DIAMOND;
    }

    public static void loadAllConfigs() {
        try {
            if (!Files.exists(CONFIG_DIR)) Files.createDirectories(CONFIG_DIR);

            questions  = loadJson("questions.json",  new TypeToken<Map<String, String>>(){}.getType(), new HashMap<>());
            wordsMc    = loadJson("words_mc.json",   new TypeToken<List<String>>(){}.getType(), Arrays.asList("MINECRAFT", "CREEPER"));
            wordsHover = loadJson("words_hover.json",new TypeToken<List<String>>(){}.getType(), Arrays.asList("SECRET", "CACHE"));

            loadSettings();

            Map<UUID, Integer> rawLb = loadJson("leaderboard.json", new TypeToken<Map<UUID, Integer>>(){}.getType(), new HashMap<>());
            leaderboard.clear();
            leaderboard.putAll(rawLb);

            Map<UUID, String> rawNames = loadJson("names.json", new TypeToken<Map<UUID, String>>(){}.getType(), new HashMap<>());
            nomsCache.clear();
            nomsCache.putAll(rawNames);

            // On log la location brute — PAS l'item résolu, car les registres
            // des autres mods ne sont pas encore disponibles ici
            LOGGER.info("[ChatDynamics] Config chargée ({} questions, rewardItem: {}, validated: {}, x{})",
                    questions.size(), rewardItemLocation, rewardItemValidated, quantiteRecompense);

        } catch (Exception e) {
            LOGGER.error("[ChatDynamics] Erreur chargement configs", e);
        }
    }

    private static void loadSettings() {
        Path path = CONFIG_DIR.resolve("config.json");
        if (!Files.exists(path)) {
            saveConfig();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json == null) return;

            if (json.has("delaiEntreEvents")) delaiEntreEvents   = json.get("delaiEntreEvents").getAsInt();
            if (json.has("delaiTimeout"))     delaiTimeout       = json.get("delaiTimeout").getAsInt();
            if (json.has("rewardAmount"))     quantiteRecompense = json.get("rewardAmount").getAsInt();
            if (json.has("rewardItem")) {
                ResourceLocation loc = ResourceLocation.tryParse(json.get("rewardItem").getAsString());
                if (loc != null) rewardItemLocation = loc;
            }
            if (json.has("rewardItemValidated")) {
                rewardItemValidated = json.get("rewardItemValidated").getAsBoolean();
            } else {
                rewardItemValidated = false;  // Anciennes configs
            }
        } catch (Exception e) {
            LOGGER.error("[ChatDynamics] Erreur lecture config.json", e);
        }
    }

    /**
     * Sauvegarde SYNCHRONE — ne jamais passer en async.
     * Appelée depuis /cd setreward, doit être sur disque immédiatement
     * au cas où le serveur s'arrêterait juste après.
     */

    public static void validateRewardItem() {
        Item found = ForgeRegistries.ITEMS.getValue(rewardItemLocation);
        if (found != null && found != Items.AIR) {
            rewardItemValidated = true;
            LOGGER.info("[ChatDynamics] Item recompense validé : {}", rewardItemLocation);
        } else {
            LOGGER.warn("[ChatDynamics] Item recompense NON validé : {}. Fallback diamond.", rewardItemLocation);
            // Optionnel : reset vers diamond si invalide
            // rewardItemLocation = ResourceLocation.tryParse("minecraft:diamond");
        }
    }

    public static void saveConfig() {
        JsonObject json = new JsonObject();
        json.addProperty("delaiEntreEvents", delaiEntreEvents);
        json.addProperty("delaiTimeout",     delaiTimeout);
        json.addProperty("rewardItem",       rewardItemLocation.toString());
        json.addProperty("rewardAmount",     quantiteRecompense);
        json.addProperty("rewardItemValidated", rewardItemValidated);  // ← AJOUTE ÇA


        Path path = CONFIG_DIR.resolve("config.json");
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
            LOGGER.info("[ChatDynamics] config.json sauvegardé (item: {}, x{})", rewardItemLocation, quantiteRecompense);
        } catch (IOException e) {
            LOGGER.error("[ChatDynamics] Erreur sauvegarde config.json", e);
        }
    }

    public static void saveLeaderboardConfig() {
        Map<UUID, Integer> copyLb    = new HashMap<>(leaderboard);
        Map<UUID, String>  copyNames = new HashMap<>(nomsCache);
        CompletableFuture.runAsync(() -> {
            saveJsonSync(CONFIG_DIR.resolve("leaderboard.json"), copyLb);
            saveJsonSync(CONFIG_DIR.resolve("names.json"), copyNames);
        });
    }

    private static <T> T loadJson(String fileName, Type type, T defaultValue) {
        Path path = CONFIG_DIR.resolve(fileName);
        if (!Files.exists(path)) {
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(defaultValue, w);
            } catch (IOException e) {
                LOGGER.error("[ChatDynamics] Erreur création {}", fileName, e);
            }
            return defaultValue;
        }
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T result = GSON.fromJson(r, type);
            return result != null ? result : defaultValue;
        } catch (Exception e) {
            LOGGER.error("[ChatDynamics] Erreur lecture {}", fileName, e);
            return defaultValue;
        }
    }

    private static void saveJsonSync(Path path, Object data) {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
        } catch (IOException e) {
            LOGGER.error("[ChatDynamics] Erreur sauvegarde {}", path, e);
        }
    }
}