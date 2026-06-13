package com.angel.aibuilder.client;

import com.angel.aibuilder.AiBuilderMod;
import net.minecraftforge.api.distmarker.Dist;
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
}
