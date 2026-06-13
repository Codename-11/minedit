package com.angel.aibuilder.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MineditControlsScreen extends Screen {
    public MineditControlsScreen() {
        super(Component.literal("Minedit"));
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int left = center - 155;
        int right = center + 5;
        int y = Math.max(36, this.height / 2 - 82);
        addButton(left, y, 150, "Status", "minedit status");
        addButton(right, y, 150, "Models", "minedit model list");
        addButton(left, y + 24, 150, "Streaming On", "minedit streaming on");
        addButton(right, y + 24, 150, "Streaming Off", "minedit streaming off");
        addButton(left, y + 48, 150, "Tool On", "minedit selection tool on");
        addButton(right, y + 48, 150, "Tool Off", "minedit selection tool off");
        addButton(left, y + 72, 150, "Clear Selection", "minedit selection clear");
        addButton(right, y + 72, 150, "Stop", "minedit stop");
        this.addRenderableWidget(Button.builder(Component.literal("Close"), button -> this.onClose())
                .bounds(center - 75, y + 110, 150, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.centeredText(this.font, this.title, this.width / 2, Math.max(12, this.height / 2 - 116), 0xFFFFFF);
        graphics.centeredText(this.font, Component.literal("/minedit controls"), this.width / 2, Math.max(25, this.height / 2 - 101), 0xA0A0A0);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void addButton(int x, int y, int width, String label, String command) {
        this.addRenderableWidget(Button.builder(Component.literal(label), button -> runCommand(command))
                .bounds(x, y, width, 20)
                .build());
    }

    private void runCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.connection != null) {
            minecraft.player.connection.sendCommand(command);
        }
    }
}
