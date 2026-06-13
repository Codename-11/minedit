package com.angel.aibuilder.client;

import com.angel.aibuilder.AiBuilderMod;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = AiBuilderMod.MODID, dist = Dist.CLIENT)
public class AiBuilderClient {
    public AiBuilderClient() {
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(new SelectionParticleRenderer());
    }

    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mineditgui")
                .executes(ctx -> MineditScreenOpener.open()));
        event.getDispatcher().register(Commands.literal("minedit")
                .then(Commands.literal("gui")
                        .executes(ctx -> MineditScreenOpener.open())));
    }
}
