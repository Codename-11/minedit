package com.angel.aibuilder.client;

import net.minecraft.client.Minecraft;

public final class MineditScreenOpener {
    private MineditScreenOpener() {
    }

    public static int open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new MineditControlsScreen()));
        return 1;
    }
}
