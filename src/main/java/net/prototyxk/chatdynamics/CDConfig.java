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
import java.util.concurrent.CompletableFuture; // Import indispensable

public class CDConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(CDConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("chatdynamics");

    public static Map<String, String> questions = new HashMap<>();
    public static List<String> wordsMc = new ArrayList<>();
    public static List<String> wordsHover = new ArrayList<>();

    public static int delaiEntreEvents = 12000;
    public static int delaiTimeout = 1200;

    public static Item itemRecompense = Items.DIAMOND;
    public static int quantiteRecompense = 1;

    // On utilise des Maps synchronisées pour éviter les crashs si 2 joueurs répondent en même temps
    public static Map<UUID, Integer> leaderboard = Collections.synchronizedMap(new HashMap<>());
    public static Map<UUID, String> nomsCache = Collections.synchronizedMap(new HashMap<>());

    public static void loadAllConfigs() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }

            questions = loadJson("questions.json", new TypeToken<Map<String, String>>(){}.getType(), new HashMap<>());
            wordsMc = loadJson("words_mc.json", new TypeToken<List<String>>(){}.getType(),
                    Arrays.asList("MINECRAFT", "DIAMANT", "CREEPER", "NETHER"));
            wordsHover = loadJson("words_hover.json", new TypeToken<List<String>>(){}.getType(),
                    Arrays.asList("SECRET", "SURPRISE", "CACHÉ"));

            loadSettings();

            // Chargement des données dynamiques
            Map<UUID, Integer> rawLeaderboard = loadJson("leaderboard.json", new TypeToken<Map<UUID, Integer>>(){}.getType(), new HashMap<>());
            leaderboard.clear();
            leaderboard.putAll(rawLeaderboard);

            Map<UUID, String> rawNames = loadJson("names.json", new TypeToken<Map<UUID, String>>(){}.getType(), new HashMap<>());
            nomsCache.clear();
            nomsCache.putAll(rawNames);

            LOGGER.info("[ChatDynamics] Configuration chargée avec succès.");
        } catch (Exception e) {
            LOGGER.error("[ChatDynamics] Erreur lors du chargement des configs : ", e);
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

            if (json.has("delaiEntreEvents")) delaiEntreEvents = json.get("delaiEntreEvents").getAsInt();
            if (json.has("delaiTimeout")) delaiTimeout = json.get("delaiTimeout").getAsInt();
            if (json.has("rewardAmount")) quantiteRecompense = json.get("rewardAmount").getAsInt();

            if (json.has("rewardItem")) {
                String itemName = json.get("rewardItem").getAsString();
                Item found = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemName));
                if (found != null && found != Items.AIR) {
                    itemRecompense = found;
                }
            }
        } catch (Exception e) {
            LOGGER.error("[ChatDynamics] Erreur lecture config.json", e);
        }
    }

    public static void saveConfig() {
        JsonObject json = new JsonObject();
        json.addProperty("delaiEntreEvents", delaiEntreEvents);
        json.addProperty("delaiTimeout", delaiTimeout);

        ResourceLocation loc = ForgeRegistries.ITEMS.getKey(itemRecompense);
        json.addProperty("rewardItem", loc != null ? loc.toString() : "minecraft:diamond");
        json.addProperty("rewardAmount", quantiteRecompense);

        saveJson(CONFIG_DIR.resolve("config.json"), json);
    }

    public static void saveLeaderboardConfig() {
        // On crée une copie locale pour la sauvegarde asynchrone
        // afin d'éviter les erreurs si le leaderboard change pendant l'écriture.
        Map<UUID, Integer> copyLeaderboard = new HashMap<>(leaderboard);
        Map<UUID, String> copyNames = new HashMap<>(nomsCache);

        saveJson(CONFIG_DIR.resolve("leaderboard.json"), copyLeaderboard);
        saveJson(CONFIG_DIR.resolve("names.json"), copyNames);
    }

    private static <T> T loadJson(String fileName, Type type, T defaultValue) {
        Path path = CONFIG_DIR.resolve(fileName);
        if (!Files.exists(path)) {
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(defaultValue, writer);
            } catch (IOException e) {
                LOGGER.error("[ChatDynamics] Erreur création fichier défaut " + fileName, e);
            }
            return defaultValue;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T result = GSON.fromJson(reader, type);
            return result != null ? result : defaultValue;
        } catch (Exception e) {
            LOGGER.error("[ChatDynamics] Erreur lecture " + fileName, e);
            return defaultValue;
        }
    }

    private static void saveJson(Path path, Object data) {
        CompletableFuture.runAsync(() -> {
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            } catch (IOException e) {
                LOGGER.error("[ChatDynamics] Erreur sauvegarde critique " + path, e);
            }
        });
    }
}