package net.prototyxk.chatdynamics;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = ChatDynamics.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // On crée une option pour régler le délai (en secondes)
    private static final ForgeConfigSpec.IntValue DELAI_SECONDS = BUILDER
            .comment("Délai en secondes entre deux calculs (ex: 60 pour 1 minute)")
            .defineInRange("delaiSeconds", 60, 10, 3600);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int delaiTicks;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            delaiTicks = DELAI_SECONDS.get() * 20;
        }
    }
}