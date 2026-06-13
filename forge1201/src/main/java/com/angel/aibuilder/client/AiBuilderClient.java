package com.angel.aibuilder.client;

import com.angel.aibuilder.AiBuilderMod;
import net.minecraft.commands.Commands;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = AiBuilderMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AiBuilderClient {
    private static final SelectionParticleRenderer RENDERER = new SelectionParticleRenderer();

    private AiBuilderClient() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent event) {
        RENDERER.onClientTick(event);
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mineditgui")
                .executes(ctx -> MineditScreenOpener.open()));
        event.getDispatcher().register(Commands.literal("minedit")
                .then(Commands.literal("gui")
                        .executes(ctx -> MineditScreenOpener.open())));
    }
}
