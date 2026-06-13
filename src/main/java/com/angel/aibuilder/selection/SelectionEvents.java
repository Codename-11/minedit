package com.angel.aibuilder.selection;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public class SelectionEvents {
    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getItemStack().is(Items.STICK) || !SelectionManager.selectionToolEnabled(event.getEntity().getUUID())) {
            return;
        }

        event.setCanceled(true);
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }

        if (event.getLevel().isClientSide()) {
            SelectionManager.selectFirstClient(event.getEntity().getUUID(), event.getPos());
        } else {
            SelectionManager.SelectionClick click = SelectionManager.selectFirstServer(event.getEntity().getUUID(), event.getPos());
            sendSelectionMessage(event, "pos1", click);
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getItemStack().is(Items.STICK) || !SelectionManager.selectionToolEnabled(event.getEntity().getUUID())) {
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getLevel().isClientSide()) {
            SelectionManager.selectSecondClient(event.getEntity().getUUID(), event.getPos());
        } else {
            SelectionManager.SelectionClick click = SelectionManager.selectSecondServer(event.getEntity().getUUID(), event.getPos());
            sendSelectionMessage(event, "pos2", click);
        }
    }

    private static void sendSelectionMessage(PlayerInteractEvent event, String corner, SelectionManager.SelectionClick click) {
        if (click.selection() == null) {
            event.getEntity().sendSystemMessage(Component.literal("Minedit: " + corner + " selected.").withStyle(ChatFormatting.YELLOW));
            return;
        }

        BuildSelection selection = click.selection();
        event.getEntity().sendSystemMessage(Component.literal(
                "Minedit: " + corner + " selected; footprint is " + selection.width() + " x " + selection.depth()
                        + " at Y " + selection.baseY() + "."
        ).withStyle(ChatFormatting.GREEN));
    }
}
