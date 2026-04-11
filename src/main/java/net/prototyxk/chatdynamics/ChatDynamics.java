package net.prototyxk.chatdynamics;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(ChatDynamics.MODID)
public class ChatDynamics {
    public static final String MODID = "chatdynamics";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static ChatEventManager EVENT_MANAGER;

    public ChatDynamics() {
        CDConfig.loadAllConfigs();

        EVENT_MANAGER = new ChatEventManager();

        MinecraftForge.EVENT_BUS.register(EVENT_MANAGER);

        LOGGER.info("ChatDynamics est prêt à l'action !");
    }
}