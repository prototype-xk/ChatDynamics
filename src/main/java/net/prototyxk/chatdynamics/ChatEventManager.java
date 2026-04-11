package net.prototyxk.chatdynamics;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.ChatFormatting;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.text.Normalizer;

public class ChatEventManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatEventManager.class);
    private static final String HOLO_TAG = "cd_hologram";
    private static final Component PREFIX = Component.literal("§6[ChatDynamics] ");
    private static final MutableComponent MSG_HOVER_BASE =
            Component.literal("§ePasse ta souris §6§n[ICI]§e pour le mot secret !");

    private final Random random = ThreadLocalRandom.current();
    private MinecraftServer server = null;

    private boolean eventActif = false;
    private String reponseAttendue = "";
    private int tickLancement = 0;
    private int timerAttente = 0;
    private int timerExpiration = 0;
    private int tickCounter = 0;
    private static final int TICKS_PAR_SECONDE = 20;

    private Pioche<String> piocheCultureG;
    private Pioche<String> piocheMotsMC;
    private Pioche<String> piocheMotsHover;

    private Map<UUID, Integer> leaderboard = new HashMap<>();
    private Map<UUID, String> nomsCache = new HashMap<>();
    private List<Component> leaderboardCache = new ArrayList<>();

    private BlockPos holoPos = new BlockPos(0, 100, 0);
    private final List<UUID> holoStandUUIDs = new ArrayList<>();

    public ChatEventManager() {
        CDConfig.loadAllConfigs();
        this.piocheCultureG = new Pioche<>(new ArrayList<>(CDConfig.questions.keySet()));
        this.piocheMotsMC = new Pioche<>(CDConfig.wordsMc);
        this.piocheMotsHover = new Pioche<>(CDConfig.wordsHover);
        this.leaderboard = new HashMap<>(CDConfig.leaderboard);
        this.nomsCache = new HashMap<>(CDConfig.nomsCache);
        updateLeaderboardCache();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        this.server = event.getServer();
        holoStandUUIDs.clear();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;
        if (++tickCounter < TICKS_PAR_SECONDE) return;
        tickCounter = 0;

        if (!eventActif) {
            if (++timerAttente >= (CDConfig.delaiEntreEvents / TICKS_PAR_SECONDE)) {
                lancerEvenementAleatoire();
            }
        } else {
            if (++timerExpiration >= (CDConfig.delaiTimeout / TICKS_PAR_SECONDE)) {
                broadcast(Component.literal("§cTemps écoulé ! §fLa réponse était : §e" + reponseAttendue));
                resetEvent(true);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerChat(ServerChatEvent event) {
        if (!eventActif) return;
        String messageSaisi = normaliser(event.getRawText());
        String solution = normaliser(reponseAttendue);

        if (messageSaisi.equals(solution)) {
            ServerPlayer p = event.getPlayer();
            leaderboard.put(p.getUUID(), leaderboard.getOrDefault(p.getUUID(), 0) + 1);
            nomsCache.put(p.getUUID(), p.getName().getString());
            CDConfig.leaderboard = new HashMap<>(this.leaderboard);
            CDConfig.nomsCache = new HashMap<>(this.nomsCache);
            CDConfig.saveLeaderboardConfig();
            updateLeaderboardCache();
            updateHologram();

            float sec = (server.getTickCount() - tickLancement) / 20.0f;
            broadcast(Component.literal("§a" + p.getName().getString() + " §fa trouvé §e" + reponseAttendue + " §fen §e" + String.format("%.2f", sec) + "s !"));
            p.addItem(new ItemStack(CDConfig.itemRecompense, CDConfig.quantiteRecompense));
            resetEvent(false);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cd")
                .then(Commands.literal("top").executes(c -> {
                    leaderboardCache.forEach(line -> c.getSource().sendSuccess(() -> line, false));
                    return 1;
                }))
                .then(Commands.literal("score").executes(c -> {
                    if (!(c.getSource().getEntity() instanceof ServerPlayer p)) {
                        c.getSource().sendFailure(Component.literal("§cCommande réservée aux joueurs."));
                        return 0;
                    }
                    int score = leaderboard.getOrDefault(p.getUUID(), 0);
                    c.getSource().sendSuccess(() -> Component.literal("§6[ChatDynamics] §eTon score : §b" + score + " §epoint(s)."), false);
                    return 1;
                }))
                .then(Commands.literal("setreward")
                        .requires(s -> s.hasPermission(2))
                        .executes(c -> {
                            if (!(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
                            ItemStack stack = p.getMainHandItem();
                            if (stack.isEmpty()) {
                                c.getSource().sendFailure(Component.literal("§cTu dois tenir un item en main !"));
                                return 0;
                            }
                            CDConfig.itemRecompense = stack.getItem();
                            CDConfig.quantiteRecompense = stack.getCount();
                            CDConfig.saveConfig(); // Sauvegarde permanente dans le JSON
                            c.getSource().sendSuccess(() -> Component.literal("§aRécompense mise à jour : §e" + stack.getCount() + "x " + stack.getItem().getDescriptionId()), true);
                            return 1;
                        })
                )
                .then(Commands.literal("status").executes(c -> {
                    if (!eventActif) {
                        int resteSecondes = (CDConfig.delaiEntreEvents / TICKS_PAR_SECONDE) - timerAttente;
                        c.getSource().sendSuccess(() -> Component.literal("§6[ChatDynamics] §eAucun événement actif. Prochain dans §b" + resteSecondes + "s§e."), false);
                    } else {
                        int resteSecondes = (CDConfig.delaiTimeout / TICKS_PAR_SECONDE) - timerExpiration;
                        c.getSource().sendSuccess(() -> Component.literal("§6[ChatDynamics] §aÉvénement en cours ! §eTemps restant : §b" + resteSecondes + "s§e."), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("setholo").requires(s -> s.hasPermission(2)).executes(c -> {
                    if (!(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
                    this.holoPos = p.blockPosition();
                    updateHologram();
                    c.getSource().sendSuccess(() -> Component.literal("§a[ChatDynamics] Hologramme défini à ta position."), true);
                    return 1;
                }))
                .then(Commands.literal("delholo").requires(s -> s.hasPermission(2)).executes(c -> {
                    discardHologram();
                    this.holoPos = new BlockPos(0, 100, 0);
                    c.getSource().sendSuccess(() -> Component.literal("§c[ChatDynamics] Hologramme supprimé."), true);
                    return 1;
                }))
                .then(Commands.literal("start").requires(s -> s.hasPermission(2))
                        .executes(c -> { lancer(EventType.random(random)); return 1; })
                        .then(Commands.literal("calcul").executes(c -> { lancer(EventType.CALCUL); return 1; }))
                        .then(Commands.literal("calcul_expert").executes(c -> { lancer(EventType.CALCUL_EXPERT); return 1; }))
                        .then(Commands.literal("division").executes(c -> { lancer(EventType.DIVISION); return 1; }))
                        .then(Commands.literal("culture_g").executes(c -> { lancer(EventType.CULTURE_G); return 1; }))
                        .then(Commands.literal("mot").executes(c -> { lancer(EventType.MOT_DESORDRE); return 1; }))
                        .then(Commands.literal("hover").executes(c -> { lancer(EventType.HOVER); return 1; }))
                )
                .then(Commands.literal("stop").requires(s -> s.hasPermission(2)).executes(c -> {
                    if (!eventActif) {
                        c.getSource().sendFailure(Component.literal("§cAucun événement en cours."));
                        return 0;
                    }
                    broadcast(Component.literal("§cL'événement a été annulé par un modérateur."));
                    resetEvent(false);
                    return 1;
                }))
                .then(Commands.literal("reset").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("joueur", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> {
                                    nomsCache.values().forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .executes(c -> {
                                    String nom = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "joueur");
                                    UUID found = nomsCache.entrySet().stream()
                                            .filter(e -> e.getValue().equalsIgnoreCase(nom))
                                            .map(Map.Entry::getKey)
                                            .findFirst().orElse(null);

                                    if (found == null) {
                                        c.getSource().sendFailure(Component.literal("§cJoueur introuvable."));
                                        return 0;
                                    }

                                    leaderboard.remove(found);

                                    CDConfig.leaderboard = new HashMap<>(this.leaderboard);
                                    CDConfig.nomsCache = new HashMap<>(this.nomsCache);
                                    CDConfig.saveLeaderboardConfig();

                                    updateLeaderboardCache();
                                    updateHologram();

                                    c.getSource().sendSuccess(() -> Component.literal("§aScore de §e" + nom + " §aremis à zéro."), true);
                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(c -> {
                    reloadConfig();
                    c.getSource().sendSuccess(() -> Component.literal("§aConfig rechargée !"), true);
                    return 1;
                }))
        );
    }

    private void lancer(EventType type) {
        if (eventActif) return;
        eventActif = true;
        tickLancement = (server != null) ? server.getTickCount() : 0;
        timerAttente = 0;
        timerExpiration = 0;

        switch (type) {
            case CALCUL -> lancerCalcul();
            case CULTURE_G -> lancerCultureG();
            case MOT_DESORDRE -> lancerMotDesordre();
            case HOVER -> lancerHoverEvent();
            case DIVISION -> lancerDivision();
            case CALCUL_EXPERT -> lancerCalculExpert();
        }
    }

    private void lancerCalcul() {
        int a = random.nextInt(50) + 1, b = random.nextInt(50) + 1;
        reponseAttendue = String.valueOf(a + b);
        broadcast(Component.literal("§eCalcul : §f" + a + " + " + b + " = ?"));
    }

    private void lancerCultureG() {
        String question = piocheCultureG.tirer();
        if (question == null) {
            CDConfig.loadAllConfigs();
            if (!CDConfig.questions.isEmpty()) {
                piocheCultureG.reset(new ArrayList<>(CDConfig.questions.keySet()));
                question = piocheCultureG.tirer();
            }
        }
        if (question == null) {
            resetEvent(false);
            return;
        }
        this.reponseAttendue = CDConfig.questions.get(question);
        broadcast(Component.literal("§eCulture G : §f" + question));
    }

    private void lancerMotDesordre() {
        String mot = piocheMotsMC.tirer();
        if (mot == null) { resetEvent(false); return; }
        reponseAttendue = mot;
        List<String> chars = Arrays.asList(mot.split(""));
        Collections.shuffle(chars);
        broadcast(Component.literal("§eMot mélangé : §b" + String.join("", chars)));
    }

    private void lancerHoverEvent() {
        String mot = piocheMotsHover.tirer();
        if (mot == null) return;
        MutableComponent hoverContent = Component.literal("Mot à trouver : ")
                .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(mot).withStyle(net.minecraft.ChatFormatting.BOLD, net.minecraft.ChatFormatting.GOLD));

        MutableComponent msg = MSG_HOVER_BASE.copy().withStyle(style ->
                style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverContent))
        );

        broadcast(msg);
    }

    private void lancerDivision() {
        int q = random.nextInt(50) + 1, b = random.nextInt(9) + 2;
        reponseAttendue = String.valueOf(q);
        broadcast(Component.literal("§eDivision : §f" + (q * b) + " / " + b + " = ?"));
    }

    private void lancerCalculExpert() {
        int a = random.nextInt(20) + 1, b = random.nextInt(10) + 1, c = random.nextInt(5) + 1;
        reponseAttendue = String.valueOf((a + b) * c);
        broadcast(Component.literal("§eCalcul Expert : §f(" + a + " + " + b + ") * " + c + " = ?"));
    }

    private void updateHologram() {
        if (server == null || holoPos.getY() == 100) return;
        ServerLevel level = server.overworld();
        List<Component> lignes = buildLeaderboardLignes();

        boolean reconstruire = holoStandUUIDs.isEmpty() ||
                holoStandUUIDs.size() != lignes.size() ||
                holoStandUUIDs.stream().anyMatch(uuid -> findStand(level, uuid) == null);

        if (reconstruire) {
            discardHologram();

            double x = holoPos.getX() + 0.5, z = holoPos.getZ() + 0.5;
            for (int i = 0; i < lignes.size(); i++) {
                double y = holoPos.getY() + 2.8 - (i * 0.28);
                ArmorStand stand = spawnStand(level, x, y, z, lignes.get(i));
                if (stand != null) holoStandUUIDs.add(stand.getUUID());
            }
        } else {
            for (int i = 0; i < lignes.size(); i++) {
                ArmorStand stand = findStand(level, holoStandUUIDs.get(i));
                if (stand != null) {
                    stand.setCustomName(lignes.get(i));
                }
            }
        }
    }

    private List<Component> buildLeaderboardLignes() {
        List<Component> lignes = new ArrayList<>();
        lignes.add(Component.literal("§6§l== TOP 10 CHATDYNAMICS =="));
        List<Map.Entry<UUID, Integer>> top = leaderboard.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .limit(10).toList();
        for (int i = 0; i < top.size(); i++) {
            Map.Entry<UUID, Integer> entry = top.get(i);
            lignes.add(Component.literal("§7#" + (i+1) + " §f" + nomsCache.getOrDefault(entry.getKey(), "Inconnu") + " §7- §b" + entry.getValue()));
        }
        if (top.isEmpty()) lignes.add(Component.literal("§7Aucun score !"));
        return lignes;
    }

    private ArmorStand spawnStand(ServerLevel level, double x, double y, double z, Component texte) {
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        if (stand == null) return null;
        stand.setPos(x, y, z);
        stand.addTag(HOLO_TAG);
        stand.setCustomName(texte);
        stand.setCustomNameVisible(true);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        CompoundTag tag = new CompoundTag();
        stand.save(tag);
        tag.putBoolean("Small", true);
        tag.putBoolean("Marker", true);
        stand.load(tag);
        level.addFreshEntity(stand);
        return stand;
    }

    private ArmorStand findStand(ServerLevel level, UUID uuid) {
        AABB zone = new AABB(holoPos).inflate(5);
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, zone)) {
            if (stand.getUUID().equals(uuid) && stand.isAlive()) return stand;
        }
        return null;
    }

    private void discardHologram() {
        if (server == null) return;
        ServerLevel level = server.overworld();
        level.getEntitiesOfClass(ArmorStand.class, new AABB(holoPos).inflate(10)).forEach(s -> {
            if (s.getTags().contains(HOLO_TAG)) s.discard();
        });
        holoStandUUIDs.clear();
    }

    private void reloadConfig() {
        CDConfig.loadAllConfigs();
        this.piocheCultureG.reset(new ArrayList<>(CDConfig.questions.keySet()));
        this.piocheMotsMC.reset(CDConfig.wordsMc);
        this.piocheMotsHover.reset(CDConfig.wordsHover);
        this.leaderboard = new HashMap<>(CDConfig.leaderboard);
        this.nomsCache = new HashMap<>(CDConfig.nomsCache);
        updateLeaderboardCache();
        updateHologram();
    }

    private void updateLeaderboardCache() {
        leaderboardCache.clear();
        leaderboardCache.add(Component.literal("§6--- TOP 10 CHATDYNAMICS ---"));
        leaderboard.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> leaderboardCache.add(Component.literal("§e" + nomsCache.getOrDefault(e.getKey(), "???") + " §7: §b" + e.getValue())));
    }

    private void broadcast(Component message) {
        if (server == null) return;

        Component finalMsg = Component.empty().append(PREFIX).append(message);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(finalMsg);
        }
    }

    private static String normaliser(String s) {
        if (s == null) return "";
        String upper = s.strip().toUpperCase();
        String decomposed = Normalizer.normalize(upper, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{InCombiningDiacriticalMarks}", "");
    }

    private void resetEvent(boolean keepTimer) {
        eventActif = false;
        reponseAttendue = "";
        timerExpiration = 0;
        if (!keepTimer) timerAttente = 0;
    }

    private void lancerEvenementAleatoire() {
        lancer(EventType.random(random));
    }
}