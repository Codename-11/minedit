package com.angel.aibuilder.build;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

public class BuildQueue {
    private static final Queue<QueuedBuild> BUILDS = new ArrayDeque<>();

    public static void enqueue(QueuedBuild build) {
        BUILDS.add(build);
    }

    public static int cancelBuilds(UUID playerId) {
        int before = BUILDS.size();
        BUILDS.removeIf(build -> build.isFor(playerId));
        return before - BUILDS.size();
    }

    public static boolean hasBuildFor(UUID playerId) {
        return BUILDS.stream().anyMatch(build -> build.isFor(playerId));
    }

    public static int size() {
        return BUILDS.size();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        BuildUndoManager.tickRestores();
        QueuedBuild build = BUILDS.peek();
        if (build != null && build.tick()) {
            BUILDS.poll();
        }
    }
}
