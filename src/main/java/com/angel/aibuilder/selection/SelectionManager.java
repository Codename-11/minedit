package com.angel.aibuilder.selection;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SelectionManager {
    private static final Map<UUID, BlockPos> FIRST = new HashMap<>();
    private static final Map<UUID, BlockPos> SECOND = new HashMap<>();
    private static final Map<UUID, BuildSelection> SELECTIONS = new HashMap<>();
    private static final Map<UUID, BlockPos> CLIENT_FIRST = new HashMap<>();
    private static final Map<UUID, BlockPos> CLIENT_SECOND = new HashMap<>();
    private static final Map<UUID, BuildSelection> CLIENT_SELECTIONS = new HashMap<>();
    private static final Map<UUID, Boolean> TOOL_ENABLED = new HashMap<>();

    private SelectionManager() {
    }

    public static SelectionClick selectFirstServer(UUID playerId, BlockPos pos) {
        return selectCorner(FIRST, SECOND, SELECTIONS, playerId, pos, true);
    }

    public static SelectionClick selectSecondServer(UUID playerId, BlockPos pos) {
        return selectCorner(FIRST, SECOND, SELECTIONS, playerId, pos, false);
    }

    public static SelectionClick selectFirstClient(UUID playerId, BlockPos pos) {
        return selectCorner(CLIENT_FIRST, CLIENT_SECOND, CLIENT_SELECTIONS, playerId, pos, true);
    }

    public static SelectionClick selectSecondClient(UUID playerId, BlockPos pos) {
        return selectCorner(CLIENT_FIRST, CLIENT_SECOND, CLIENT_SELECTIONS, playerId, pos, false);
    }

    private static SelectionClick selectCorner(Map<UUID, BlockPos> firstMap, Map<UUID, BlockPos> secondMap, Map<UUID, BuildSelection> selectionMap, UUID playerId, BlockPos pos, boolean firstCorner) {
        if (firstCorner) {
            firstMap.put(playerId, pos.immutable());
        } else {
            secondMap.put(playerId, pos.immutable());
        }

        BlockPos first = firstMap.get(playerId);
        BlockPos second = secondMap.get(playerId);
        if (first == null || second == null) {
            selectionMap.remove(playerId);
            return new SelectionClick(firstCorner, null);
        }

        BuildSelection selection = new BuildSelection(first, second);
        selectionMap.put(playerId, selection);
        return new SelectionClick(firstCorner, selection);
    }

    public static Optional<BuildSelection> selection(UUID playerId) {
        return Optional.ofNullable(SELECTIONS.get(playerId));
    }

    public static Optional<BuildSelection> clientSelection(UUID playerId) {
        return Optional.ofNullable(CLIENT_SELECTIONS.get(playerId));
    }

    public static void clearServer(UUID playerId) {
        FIRST.remove(playerId);
        SECOND.remove(playerId);
        SELECTIONS.remove(playerId);
    }

    public static void clearClient(UUID playerId) {
        CLIENT_FIRST.remove(playerId);
        CLIENT_SECOND.remove(playerId);
        CLIENT_SELECTIONS.remove(playerId);
    }

    public static boolean selectionToolEnabled(UUID playerId) {
        return TOOL_ENABLED.getOrDefault(playerId, true);
    }

    public static boolean setSelectionToolEnabled(UUID playerId, boolean enabled) {
        TOOL_ENABLED.put(playerId, enabled);
        return enabled;
    }

    public static boolean toggleSelectionTool(UUID playerId) {
        return setSelectionToolEnabled(playerId, !selectionToolEnabled(playerId));
    }

    public record SelectionClick(boolean firstCorner, BuildSelection selection) {
    }
}
