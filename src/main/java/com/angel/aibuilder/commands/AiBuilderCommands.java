package com.angel.aibuilder.commands;

import com.angel.aibuilder.ai.AiProvider;
import com.angel.aibuilder.ai.AiRequestOptions;
import com.angel.aibuilder.ai.ProviderModel;
import com.angel.aibuilder.ai.ProviderStatus;
import com.angel.aibuilder.build.BuildJobService;
import com.angel.aibuilder.build.BuildQueue;
import com.angel.aibuilder.build.BuildUndoManager;
import com.angel.aibuilder.codex.CodexLocalClient;
import com.angel.aibuilder.config.AiBuilderSettings;
import com.angel.aibuilder.cursor.CursorLocalClient;
import com.angel.aibuilder.debug.BuildDebugFiles;
import com.angel.aibuilder.hermes.HermesRunsClient;
import com.angel.aibuilder.openrouter.OpenRouterClient;
import com.angel.aibuilder.selection.BuildSelection;
import com.angel.aibuilder.selection.SelectionManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class AiBuilderCommands {
    private static final String EFFORT_LIST = "none, minimal, low, medium, high, xhigh, max";
    private static final Set<String> EFFORTS = Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    private static final Set<String> ENABLED_VALUES = Set.of("enabled", "enable", "on", "true", "yes");
    private static final Set<String> DISABLED_VALUES = Set.of("disabled", "disable", "off", "false", "no");
    private static final CodexLocalClient CODEX_CLIENT = new CodexLocalClient();
    private static final CursorLocalClient CURSOR_CLIENT = new CursorLocalClient();
    private static final HermesRunsClient HERMES_CLIENT = new HermesRunsClient();
    private static final OpenRouterClient OPENROUTER_CLIENT = new OpenRouterClient();

    @SubscribeEvent
    public void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("apikey")
                .then(Commands.argument("key", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                AiBuilderSettings.setApiKey(StringArgumentType.getString(ctx, "key"));
                                ctx.getSource().sendSuccess(() -> Component.literal("OpenRouter API key saved.").withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save API key: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("provider")
                .then(Commands.argument("provider", StringArgumentType.word())
                        .executes(ctx -> {
                            return setProvider(ctx.getSource(), StringArgumentType.getString(ctx, "provider"));
                        })));

        event.getDispatcher().register(Commands.literal("codexurl")
                .then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                String url = StringArgumentType.getString(ctx, "url");
                                AiBuilderSettings.setCodexUrl(url);
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit Codex URL set to " + url).withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save Codex URL: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("codextoken")
                .then(Commands.argument("token", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                AiBuilderSettings.setCodexToken(StringArgumentType.getString(ctx, "token"));
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit Codex app-server token saved.").withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save Codex token: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("hermesurl")
                .then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                String url = StringArgumentType.getString(ctx, "url");
                                AiBuilderSettings.setHermesUrl(url);
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit Hermes URL set to " + url).withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save Hermes URL: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("hermestoken")
                .then(Commands.argument("token", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                AiBuilderSettings.setHermesToken(StringArgumentType.getString(ctx, "token"));
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit Hermes token saved.").withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save Hermes token: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("codex")
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            MinecraftServer server = source.getServer();
                            String url = AiBuilderSettings.codexUrl();
                            String token = AiBuilderSettings.codexTokenOrEnv();
                            String model = AiBuilderSettings.model();
                            source.sendSuccess(() -> Component.literal("Minedit: checking Codex at " + url + "...").withStyle(ChatFormatting.YELLOW), false);
                            CompletableFuture.supplyAsync(() -> {
                                try {
                                    return CODEX_CLIENT.providerStatus(url, token, model);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }).thenAccept(status -> server.execute(() -> sendProviderStatus(source, status, model)))
                                    .exceptionally(error -> {
                                        server.execute(() -> source.sendFailure(Component.literal("Minedit Codex error: " + rootMessage(error))));
                                        return null;
                                    });
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("effort")
                .then(Commands.argument("effort", StringArgumentType.word())
                        .executes(ctx -> {
                            String effort = StringArgumentType.getString(ctx, "effort").trim().toLowerCase();
                            if (!EFFORTS.contains(effort)) {
                                ctx.getSource().sendFailure(Component.literal("Effort must be one of: " + EFFORT_LIST + "."));
                                return 0;
                            }
                            try {
                                AiBuilderSettings.setEffort(effort);
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit reasoning effort set to " + effort).withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save effort: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("model")
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            listModels(ctx.getSource(), AiBuilderSettings.provider());
                            return 1;
                        })
                        .then(Commands.argument("provider", StringArgumentType.word())
                                .executes(ctx -> {
                                    listModels(ctx.getSource(), StringArgumentType.getString(ctx, "provider"));
                                    return 1;
                                })))
                .then(Commands.argument("model", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            return setModel(ctx.getSource(), StringArgumentType.getString(ctx, "model"));
                        })));

        event.getDispatcher().register(Commands.literal("streaming")
                .then(Commands.argument("enabled", StringArgumentType.word())
                        .executes(ctx -> {
                            String value = StringArgumentType.getString(ctx, "enabled").trim().toLowerCase();
                            Boolean streaming = parseEnabled(value);
                            if (streaming == null) {
                                ctx.getSource().sendFailure(Component.literal("Streaming must be enabled or disabled."));
                                return 0;
                            }
                            try {
                                AiBuilderSettings.setStreaming(streaming);
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit OpenRouter streaming " + (streaming ? "enabled" : "disabled") + ".").withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save streaming setting: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("stop")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    int generationCount = BuildJobService.cancelGenerations(player.getUUID());
                    int queuedCount = BuildQueue.cancelBuilds(player.getUUID());
                    if (generationCount == 0 && queuedCount == 0) {
                        ctx.getSource().sendSuccess(() -> Component.literal("Minedit: nothing active to stop.").withStyle(ChatFormatting.YELLOW), false);
                    } else {
                        ctx.getSource().sendSuccess(() -> Component.literal("Minedit: stop requested. Canceled " + generationCount + " active generation(s) and removed " + queuedCount + " queued placement job(s).").withStyle(ChatFormatting.YELLOW), false);
                    }
                    return 1;
                }));

        event.getDispatcher().register(Commands.literal("status")
                .executes(ctx -> {
                    sendStatus(ctx.getSource());
                    return 1;
                }));

        event.getDispatcher().register(Commands.literal("usage")
                .then(Commands.argument("generation_id", StringArgumentType.word())
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            MinecraftServer server = source.getServer();
                            String apiKey = AiBuilderSettings.apiKey();
                            if (apiKey.isBlank()) {
                                source.sendFailure(Component.literal("Set your OpenRouter key first with /apikey <key>."));
                                return 0;
                            }

                            String generationId = StringArgumentType.getString(ctx, "generation_id");
                            source.sendSuccess(() -> Component.literal("Minedit: checking OpenRouter usage for " + generationId + "...").withStyle(ChatFormatting.YELLOW), false);
                            CompletableFuture.supplyAsync(() -> {
                                try {
                                    return OPENROUTER_CLIENT.fetchUsageReport(apiKey, generationId);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }).thenAccept(report -> server.execute(() -> {
                                ChatFormatting style = report.hasCost() ? ChatFormatting.AQUA : ChatFormatting.YELLOW;
                                source.sendSuccess(() -> Component.literal(report.summary()).withStyle(style), false);
                                if (!report.hasCost()) {
                                    source.sendSuccess(() -> Component.literal("OpenRouter has not exposed final cost for this generation yet. Try /usage again in a bit.").withStyle(ChatFormatting.YELLOW), false);
                                }
                            })).exceptionally(error -> {
                                server.execute(() -> source.sendFailure(Component.literal("OpenRouter usage lookup failed: " + rootMessage(error))));
                                return null;
                            });
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("build")
                .then(Commands.literal("export")
                        .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                                    if (selection == null) {
                                        ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                        return 0;
                                    }

                                    String prompt = StringArgumentType.getString(ctx, "prompt");
                                    try {
                                        Path path = BuildJobService.exportBuildPrompt(selection, prompt);
                                        Path importPath = BuildDebugFiles.importBuildPath();
                                        ctx.getSource().sendSuccess(() -> Component.literal("Minedit export written to " + path + ". Paste the model response or build(api) code into " + importPath + ", then run /build import.").withStyle(ChatFormatting.GREEN), false);
                                    } catch (IOException e) {
                                        ctx.getSource().sendFailure(Component.literal("Could not export Minedit prompt: " + e.getMessage()));
                                        return 0;
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("import")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                            if (selection == null) {
                                ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                return 0;
                            }

                            Path importPath = BuildDebugFiles.importBuildPath();
                            String responseOrCode;
                            try {
                                responseOrCode = BuildDebugFiles.readImportBuild();
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not read Minedit import file " + importPath + ": " + e.getMessage()));
                                return 0;
                            }
                            if (responseOrCode.isBlank()) {
                                ctx.getSource().sendFailure(Component.literal("Minedit import file is empty. Paste the model response or build(api) code into " + importPath + "."));
                                return 0;
                            }

                            ctx.getSource().sendSuccess(() -> Component.literal("Minedit import: reading " + importPath + "...").withStyle(ChatFormatting.YELLOW), false);
                            BuildJobService.importBuild(player, selection, responseOrCode);
                            return 1;
                        })
                        .then(Commands.argument("code", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                                    if (selection == null) {
                                        ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                        return 0;
                                    }

                                    String responseOrCode = StringArgumentType.getString(ctx, "code");
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit import: parsing pasted code...").withStyle(ChatFormatting.YELLOW), false);
                                    BuildJobService.importBuild(player, selection, responseOrCode);
                                    return 1;
                                })))
                .then(Commands.literal("agent")
                        .then(Commands.literal("step-by-step")
                                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            AiRequestOptions options = requestOptions(ctx.getSource(), false);
                                            if (options == null) {
                                                return 0;
                                            }
                                            if (!supportsStepByStepAgent(options.provider())) {
                                                ctx.getSource().sendFailure(Component.literal("Minedit step-by-step agent build only works with Codex local or Cursor. Use /provider codex-local or /provider cursor and start the bridge with `npm --prefix bridge start`."));
                                                return 0;
                                            }

                                            BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                                            if (selection == null) {
                                                ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                                return 0;
                                            }

                                            String prompt = StringArgumentType.getString(ctx, "prompt");
                                            ctx.getSource().sendSuccess(() -> Component.literal("Minedit tool agent: starting step-by-step build with " + options.targetDescription() + " (" + options.effort() + ")...").withStyle(ChatFormatting.YELLOW), false);
                                            BuildJobService.agentStepByStepBuild(player, selection, prompt, options);
                                            return 1;
                                        })))
                        .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    AiRequestOptions options = requestOptions(ctx.getSource(), false);
                                    if (options == null) {
                                        return 0;
                                    }
                                    if (!supportsAgentBuild(options.provider())) {
                                        ctx.getSource().sendFailure(Component.literal("Minedit agent build works with Codex local, Hermes, or Cursor. Use /provider codex, /provider hermes, or /provider cursor."));
                                        return 0;
                                    }

                                    BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                                    if (selection == null) {
                                        ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                        return 0;
                                    }

                                    String prompt = StringArgumentType.getString(ctx, "prompt");
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit agent: starting agent build with " + options.targetDescription() + " (" + options.effort() + ")...").withStyle(ChatFormatting.YELLOW), false);
                                    BuildJobService.agentBuild(player, selection, prompt, options);
                                    return 1;
                                })))
                .then(Commands.literal("stages")
                        .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    AiRequestOptions options = requestOptions(ctx.getSource(), false);
                                    if (options == null) {
                                        return 0;
                                    }

                                    BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                                    if (selection == null) {
                                        ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                        return 0;
                                    }

                                    String prompt = StringArgumentType.getString(ctx, "prompt");
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit stages: asking " + options.targetDescription() + " (" + options.effort() + ") to build in focused stages...").withStyle(ChatFormatting.YELLOW), false);
                                    BuildJobService.stagedBuild(player, selection, prompt, options);
                                    return 1;
                                })))
                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            AiRequestOptions options = requestOptions(ctx.getSource(), false);
                            if (options == null) {
                                return 0;
                            }

                            BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                            if (selection == null) {
                                ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                return 0;
                            }

                            String prompt = StringArgumentType.getString(ctx, "prompt");
                            ctx.getSource().sendSuccess(() -> Component.literal("Minedit: asking " + options.targetDescription() + " (" + options.effort() + ") to build...").withStyle(ChatFormatting.YELLOW), false);
                            BuildJobService.start(player, selection, prompt, options);
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("edit")
                .then(Commands.literal("set")
                        .then(Commands.literal("quickeffort")
                                .then(Commands.argument("effort", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String effort = StringArgumentType.getString(ctx, "effort").trim().toLowerCase();
                                            if (!EFFORTS.contains(effort)) {
                                                ctx.getSource().sendFailure(Component.literal("Quick edit effort must be one of: " + EFFORT_LIST + "."));
                                                return 0;
                                            }
                                            try {
                                                AiBuilderSettings.setQuickEffort(effort);
                                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit quick edit effort set to " + effort).withStyle(ChatFormatting.GREEN), false);
                                            } catch (IOException e) {
                                                ctx.getSource().sendFailure(Component.literal("Could not save quick edit effort: " + e.getMessage()));
                                            }
                                            return 1;
                                        }))))
                .then(Commands.literal("quick")
                        .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    AiRequestOptions options = requestOptions(ctx.getSource(), true);
                                    if (options == null) {
                                        return 0;
                                    }

                                    BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                                    if (selection == null) {
                                        ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                        return 0;
                                    }

                                    String prompt = StringArgumentType.getString(ctx, "prompt");
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit: asking " + options.targetDescription() + " (" + options.effort() + ") for a quick edit...").withStyle(ChatFormatting.YELLOW), false);
                                    BuildJobService.quickEdit(player, selection, prompt, options);
                                    return 1;
                                })))
                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            AiRequestOptions options = requestOptions(ctx.getSource(), false);
                            if (options == null) {
                                return 0;
                            }

                            BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                            if (selection == null) {
                                ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                return 0;
                            }

                            String prompt = StringArgumentType.getString(ctx, "prompt");
                            ctx.getSource().sendSuccess(() -> Component.literal("Minedit: asking " + options.targetDescription() + " (" + options.effort() + ") to edit...").withStyle(ChatFormatting.YELLOW), false);
                            BuildJobService.edit(player, selection, prompt, options);
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("reset")
                .then(Commands.literal("build")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            BuildQueue.cancelBuilds(player.getUUID());
                            if (BuildUndoManager.resetLastBuild(player)) {
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit: restoring previous build area...").withStyle(ChatFormatting.YELLOW), false);
                            } else {
                                ctx.getSource().sendFailure(Component.literal("Minedit: no generated build to reset."));
                            }
                            return 1;
                        }))
                .then(Commands.literal("selection")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            SelectionManager.clearServer(player.getUUID());
                            SelectionManager.clearClient(player.getUUID());
                            ctx.getSource().sendSuccess(() -> Component.literal("Minedit: selection cleared.").withStyle(ChatFormatting.GREEN), false);
                            return 1;
                        })));

        registerMineditRoot(event);
    }

    private static void registerMineditRoot(RegisterCommandsEvent event) {
        CommandNode<CommandSourceStack> apikey = requireNode(event, "apikey");
        CommandNode<CommandSourceStack> provider = requireNode(event, "provider");
        CommandNode<CommandSourceStack> codex = requireNode(event, "codex");
        CommandNode<CommandSourceStack> codexUrl = requireNode(event, "codexurl");
        CommandNode<CommandSourceStack> codexToken = requireNode(event, "codextoken");
        CommandNode<CommandSourceStack> hermesUrl = requireNode(event, "hermesurl");
        CommandNode<CommandSourceStack> hermesToken = requireNode(event, "hermestoken");
        CommandNode<CommandSourceStack> effort = requireNode(event, "effort");
        CommandNode<CommandSourceStack> model = requireNode(event, "model");
        CommandNode<CommandSourceStack> streaming = requireNode(event, "streaming");
        CommandNode<CommandSourceStack> stop = requireNode(event, "stop");
        CommandNode<CommandSourceStack> status = requireNode(event, "status");
        CommandNode<CommandSourceStack> usage = requireNode(event, "usage");
        CommandNode<CommandSourceStack> build = requireNode(event, "build");
        CommandNode<CommandSourceStack> edit = requireNode(event, "edit");
        CommandNode<CommandSourceStack> reset = requireNode(event, "reset");
        CommandNode<CommandSourceStack> codexStatus = requireChildNode(codex, "codex", "status");
        CommandNode<CommandSourceStack> modelList = requireChildNode(model, "model", "list");
        CommandNode<CommandSourceStack> resetSelection = requireChildNode(reset, "reset", "selection");

        event.getDispatcher().register(Commands.literal("minedit")
                .then(Commands.literal("apikey").redirect(apikey))
                .then(Commands.literal("provider")
                        .then(Commands.argument("provider", StringArgumentType.word())
                                .executes(ctx -> setProvider(ctx.getSource(), StringArgumentType.getString(ctx, "provider"))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .executes(ctx -> setProvider(ctx.getSource(), StringArgumentType.getString(ctx, "provider"))))))
                .then(Commands.literal("codex")
                        .then(Commands.literal("status").redirect(codexStatus))
                        .then(Commands.literal("url").redirect(codexUrl))
                        .then(Commands.literal("token").redirect(codexToken)))
                .then(Commands.literal("hermes")
                        .then(Commands.literal("url").redirect(hermesUrl))
                        .then(Commands.literal("token").redirect(hermesToken)))
                .then(Commands.literal("effort").redirect(effort))
                .then(Commands.literal("model")
                        .then(Commands.literal("list").redirect(modelList))
                        .then(Commands.literal("set")
                                .then(Commands.argument("model", StringArgumentType.greedyString())
                                        .executes(ctx -> setModel(ctx.getSource(), StringArgumentType.getString(ctx, "model")))))
                        .then(Commands.argument("model", StringArgumentType.greedyString())
                                .executes(ctx -> setModel(ctx.getSource(), StringArgumentType.getString(ctx, "model")))))
                .then(Commands.literal("streaming").redirect(streaming))
                .then(Commands.literal("stop").redirect(stop))
                .then(Commands.literal("status").redirect(status))
                .then(Commands.literal("usage").redirect(usage))
                .then(Commands.literal("build").redirect(build))
                .then(Commands.literal("edit").redirect(edit))
                .then(Commands.literal("reset").redirect(reset))
                .then(Commands.literal("chat")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> startChat(ctx.getSource(), StringArgumentType.getString(ctx, "message")))))
                .then(Commands.literal("selection")
                        .then(Commands.literal("clear").redirect(resetSelection))
                        .then(Commands.literal("tool")
                                .executes(ctx -> sendSelectionToolStatus(ctx.getSource()))
                                .then(Commands.argument("enabled", StringArgumentType.word())
                                        .executes(ctx -> setSelectionTool(ctx.getSource(), StringArgumentType.getString(ctx, "enabled")))))));
    }

    private static CommandNode<CommandSourceStack> requireNode(RegisterCommandsEvent event, String name) {
        CommandNode<CommandSourceStack> node = event.getDispatcher().getRoot().getChild(name);
        if (node == null) {
            throw new IllegalStateException("Missing command node: " + name);
        }
        return node;
    }

    private static CommandNode<CommandSourceStack> requireChildNode(CommandNode<CommandSourceStack> parent, String parentName, String childName) {
        CommandNode<CommandSourceStack> node = parent.getChild(childName);
        if (node == null) {
            throw new IllegalStateException("Missing command node: " + parentName + " " + childName);
        }
        return node;
    }

    private static int setProvider(CommandSourceStack source, String providerId) {
        AiProvider provider = AiProvider.fromId(providerId).orElse(null);
        if (provider == null) {
            source.sendFailure(Component.literal("Provider must be one of: " + AiProvider.ids() + "."));
            return 0;
        }
        try {
            AiBuilderSettings.setProvider(provider.id());
            if (provider == AiProvider.CODEX_LOCAL) {
                source.sendSuccess(() -> Component.literal("Minedit provider set to Codex. Use /codexurl http://... for the bridge or ws://... for direct app-server, then /codex status.").withStyle(ChatFormatting.GREEN), false);
            } else if (provider == AiProvider.HERMES) {
                source.sendSuccess(() -> Component.literal("Minedit provider set to Hermes. Configure with /hermesurl and /hermestoken if your Hermes endpoint requires a token.").withStyle(ChatFormatting.GREEN), false);
            } else if (provider == AiProvider.CURSOR) {
                source.sendSuccess(() -> Component.literal("Minedit provider set to Cursor local bridge. Start it with `npm --prefix bridge start`, then use /model list cursor and /model auto or another Cursor model id.").withStyle(ChatFormatting.GREEN), false);
            } else {
                source.sendSuccess(() -> Component.literal("Minedit provider set to OpenRouter.").withStyle(ChatFormatting.GREEN), false);
            }
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not save provider: " + e.getMessage()));
        }
        return 1;
    }

    private static int setModel(CommandSourceStack source, String model) {
        try {
            AiBuilderSettings.setModel(model);
            source.sendSuccess(() -> Component.literal("Minedit model set to " + model).withStyle(ChatFormatting.GREEN), false);
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not save model: " + e.getMessage()));
        }
        return 1;
    }

    private static AiRequestOptions requestOptions(CommandSourceStack source, boolean quick) {
        AiProvider provider = AiProvider.fromId(AiBuilderSettings.provider()).orElse(AiProvider.OPENROUTER);
        String model = AiBuilderSettings.model();
        String effort = quick ? AiBuilderSettings.quickEffort() : AiBuilderSettings.effort();
        boolean streaming = AiBuilderSettings.streaming();
        if (provider == AiProvider.CODEX_LOCAL) {
            return new AiRequestOptions(provider, "", AiBuilderSettings.codexUrl(), AiBuilderSettings.codexTokenOrEnv(), "", "", model, effort, streaming);
        }
        if (provider == AiProvider.CURSOR) {
            return new AiRequestOptions(provider, "", AiBuilderSettings.codexUrl(), "", "", "", model, effort, streaming);
        }
        if (provider == AiProvider.HERMES) {
            return new AiRequestOptions(provider, "", "", "", AiBuilderSettings.hermesUrl(), AiBuilderSettings.hermesTokenOrEnv(), model, effort, streaming);
        }

        String apiKey = AiBuilderSettings.apiKey();
        if (apiKey.isEmpty()) {
            source.sendFailure(Component.literal("Set your OpenRouter key first with /apikey <key>, or use /provider codex, /provider hermes, or /provider cursor."));
            return null;
        }
        return new AiRequestOptions(provider, apiKey, "", model, effort, streaming);
    }

    private static boolean supportsStepByStepAgent(AiProvider provider) {
        return provider == AiProvider.CODEX_LOCAL || provider == AiProvider.CURSOR;
    }

    private static boolean supportsAgentBuild(AiProvider provider) {
        return provider == AiProvider.CODEX_LOCAL || provider == AiProvider.HERMES || provider == AiProvider.CURSOR;
    }

    private static int sendSelectionToolStatus(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            boolean enabled = SelectionManager.selectionToolEnabled(player.getUUID());
            source.sendSuccess(() -> Component.literal("Minedit selection tool: " + (enabled ? "enabled" : "disabled") + ".").withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Selection tool mode is per player."));
            return 0;
        }
    }

    private static int setSelectionTool(CommandSourceStack source, String value) {
        Boolean enabled = parseEnabled(value.trim().toLowerCase());
        if (enabled == null) {
            source.sendFailure(Component.literal("Selection tool must be enabled or disabled."));
            return 0;
        }
        try {
            ServerPlayer player = source.getPlayerOrException();
            SelectionManager.setSelectionToolEnabled(player.getUUID(), enabled);
            source.sendSuccess(() -> Component.literal("Minedit selection tool " + (enabled ? "enabled" : "disabled") + ".").withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Selection tool mode is per player."));
            return 0;
        }
    }

    private static int startChat(CommandSourceStack source, String message) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Minedit chat is player-only."));
            return 0;
        }

        AiRequestOptions options = requestOptions(source, false);
        if (options == null) {
            return 0;
        }

        BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
        source.sendSuccess(() -> Component.literal("Minedit chat: asking " + options.targetDescription() + " (" + options.effort() + ")...").withStyle(ChatFormatting.YELLOW), false);
        BuildJobService.chat(player, selection, message, options);
        return 1;
    }

    private static void listModels(CommandSourceStack source, String providerId) {
        AiProvider provider = AiProvider.fromId(providerId).orElse(null);
        if (provider == null) {
            source.sendFailure(Component.literal("Provider must be one of: " + AiProvider.ids() + "."));
            return;
        }
        MinecraftServer server = source.getServer();
        switch (provider) {
            case CURSOR -> {
                String url = AiBuilderSettings.codexUrl();
                source.sendSuccess(() -> Component.literal("Minedit: checking Cursor models through local bridge at " + url + "...").withStyle(ChatFormatting.YELLOW), false);
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return CURSOR_CLIENT.listProviderModels(url);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).thenAccept(models -> server.execute(() -> sendModelList(source, provider, models)))
                        .exceptionally(error -> {
                            server.execute(() -> source.sendFailure(Component.literal("Cursor model list failed: " + rootMessage(error))));
                            return null;
                        });
            }
            case CODEX_LOCAL -> {
                String url = AiBuilderSettings.codexUrl();
                String token = AiBuilderSettings.codexTokenOrEnv();
                source.sendSuccess(() -> Component.literal("Minedit: checking Codex models at " + url + "...").withStyle(ChatFormatting.YELLOW), false);
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return CODEX_CLIENT.listProviderModels(url, token);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).thenAccept(models -> server.execute(() -> sendModelList(source, provider, models)))
                        .exceptionally(error -> {
                            server.execute(() -> source.sendFailure(Component.literal("Codex model list failed: " + rootMessage(error))));
                            return null;
                        });
            }
            case OPENROUTER -> {
                source.sendSuccess(() -> Component.literal("Minedit: checking OpenRouter models...").withStyle(ChatFormatting.YELLOW), false);
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return OPENROUTER_CLIENT.listProviderModels(AiBuilderSettings.apiKey());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).thenAccept(models -> server.execute(() -> sendModelList(source, provider, models)))
                        .exceptionally(error -> {
                            server.execute(() -> source.sendFailure(Component.literal("OpenRouter model list failed: " + rootMessage(error))));
                            return null;
                        });
            }
            case HERMES -> {
                String url = AiBuilderSettings.hermesUrl();
                String token = AiBuilderSettings.hermesTokenOrEnv();
                String model = AiBuilderSettings.model();
                source.sendSuccess(() -> Component.literal("Minedit: checking Hermes status at " + url + "...").withStyle(ChatFormatting.YELLOW), false);
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return HERMES_CLIENT.providerStatus(url, token, model);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).thenAccept(status -> server.execute(() -> {
                    sendProviderStatus(source, status, model);
                    source.sendFailure(Component.literal("Hermes model discovery is not exposed by the configured /v1/runs endpoint. Set the model manually with /model <id>."));
                })).exceptionally(error -> {
                    server.execute(() -> source.sendFailure(Component.literal("Hermes status failed: " + rootMessage(error))));
                    return null;
                });
            }
        }
    }

    private static void sendModelList(CommandSourceStack source, AiProvider provider, List<ProviderModel> models) {
        String label = modelListLabel(provider);
        if (models.isEmpty()) {
            source.sendFailure(Component.literal(label + " returned no models."));
            return;
        }

        source.sendSuccess(() -> Component.literal(label + " models (" + models.size() + " ids):").withStyle(ChatFormatting.GOLD), false);
        StringBuilder chunk = new StringBuilder();
        int limit = provider == AiProvider.OPENROUTER ? Math.min(models.size(), 120) : models.size();
        for (int i = 0; i < limit; i++) {
            String next = models.get(i).id();
            if (!chunk.isEmpty() && chunk.length() + next.length() + 2 > 230) {
                String text = chunk.toString();
                source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
                chunk.setLength(0);
            }
            if (!chunk.isEmpty()) {
                chunk.append(", ");
            }
            chunk.append(next);
        }
        if (!chunk.isEmpty()) {
            String text = chunk.toString();
            source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
        }
        if (models.size() > limit) {
            source.sendSuccess(() -> Component.literal("Showing first " + limit + " of " + models.size() + " OpenRouter ids. Browse the full catalog at https://openrouter.ai/models.").withStyle(ChatFormatting.YELLOW), false);
        }
        String currentModel = AiBuilderSettings.model();
        if (modelAvailable(models, currentModel)) {
            source.sendSuccess(() -> Component.literal("Current model is listed: " + currentModel).withStyle(ChatFormatting.GREEN), false);
        }
    }

    private static void sendStatus(CommandSourceStack source) {
        AiProvider provider = AiProvider.fromId(AiBuilderSettings.provider()).orElse(AiProvider.OPENROUTER);
        String model = AiBuilderSettings.model();
        String effort = AiBuilderSettings.effort();
        String quickEffort = AiBuilderSettings.quickEffort();
        boolean streaming = AiBuilderSettings.streaming();
        String codexUrl = AiBuilderSettings.codexUrl();
        String hermesUrl = AiBuilderSettings.hermesUrl();
        boolean hasOpenRouterKey = !AiBuilderSettings.apiKey().isEmpty();
        boolean hasCodexToken = !AiBuilderSettings.codexToken().isEmpty();
        boolean hasCodexEnvToken = !AiBuilderSettings.codexTokenOrEnv().isEmpty() && !hasCodexToken;
        boolean hasHermesToken = !AiBuilderSettings.hermesToken().isEmpty();
        boolean hasHermesEnvToken = !AiBuilderSettings.hermesTokenOrEnv().isEmpty() && !hasHermesToken;

        source.sendSuccess(() -> Component.literal("Minedit status").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("Provider: " + provider.displayName() + " (" + provider.id() + ")").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Model: " + model).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Reasoning effort: " + effort).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Quick edit effort: " + quickEffort).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Streaming for OpenRouter requests: " + (streaming ? "enabled" : "disabled")).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("OpenRouter key: " + (hasOpenRouterKey ? "saved" : "not set")).withStyle(hasOpenRouterKey ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("Codex URL: " + codexUrl).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Codex token: " + (hasCodexToken ? "saved" : hasCodexEnvToken ? "from MINEDIT_CODEX_APP_SERVER_TOKEN" : "not set")).withStyle(hasCodexToken || hasCodexEnvToken ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("Hermes URL: " + hermesUrl).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Hermes token: " + (hasHermesToken ? "saved" : hasHermesEnvToken ? "from HERMES_GATEWAY_TOKEN" : "not set")).withStyle(hasHermesToken || hasHermesEnvToken ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("AI generations in progress: " + BuildJobService.activeGenerationCount()).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Queued block placement jobs: " + BuildQueue.size()).withStyle(ChatFormatting.GRAY), false);

        if (source.getEntity() instanceof ServerPlayer player) {
            boolean selectionTool = SelectionManager.selectionToolEnabled(player.getUUID());
            source.sendSuccess(() -> Component.literal("Selection tool: " + (selectionTool ? "enabled" : "disabled")).withStyle(selectionTool ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
            SelectionManager.selection(player.getUUID()).ifPresentOrElse(selection -> {
                String text = "Selection: "
                        + selection.width() + " x " + selection.depth()
                        + " footprint at base Y " + selection.baseY()
                        + " from X " + selection.minX() + ".." + selection.maxX()
                        + ", Z " + selection.minZ() + ".." + selection.maxZ();
                source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), false);
            }, () -> source.sendSuccess(() -> Component.literal("Selection: none").withStyle(ChatFormatting.YELLOW), false));

            source.sendSuccess(() -> Component.literal("Your AI generation: " + (BuildJobService.hasActiveGenerationFor(player.getUUID()) ? "yes" : "no")).withStyle(ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.literal("Your queued block placement: " + (BuildQueue.hasBuildFor(player.getUUID()) ? "yes" : "no")).withStyle(ChatFormatting.GRAY), false);
        }
    }

    private static void sendProviderStatus(CommandSourceStack source, ProviderStatus status, String currentModel) {
        String target = status.target().isEmpty() ? status.provider().displayName() : status.target();
        if (status.needsLogin()) {
            source.sendFailure(Component.literal(target + " connected, but authentication is not ready. " + authHint(status.provider())));
            return;
        }
        ChatFormatting style = status.reachable() ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
        String modelSummary = status.modelsAvailable() ? ". Models: " + status.modelCount() + "." : ". Models: not exposed.";
        source.sendSuccess(() -> Component.literal(target + " connected. Auth: " + status.authLabel() + modelSummary).withStyle(style), false);
        if (!status.defaultModel().isEmpty()) {
            source.sendSuccess(() -> Component.literal(status.provider().displayName() + " default model: " + status.defaultModel()).withStyle(ChatFormatting.GRAY), false);
        }
        if (status.currentModelAvailable()) {
            String efforts = status.supportedEfforts().isEmpty() ? "" : ". Supported efforts: " + String.join(", ", status.supportedEfforts());
            source.sendSuccess(() -> Component.literal("Current model works with " + status.provider().displayName() + " as " + status.normalizedCurrentModel() + efforts + ".").withStyle(ChatFormatting.GREEN), false);
        } else if (status.modelsAvailable()) {
            source.sendFailure(Component.literal("Current model `" + currentModel + "` was not found in " + status.provider().displayName() + ". Try /model list " + status.provider().id() + "."));
        }
        if (!status.detail().isBlank()) {
            source.sendSuccess(() -> Component.literal(status.detail()).withStyle(ChatFormatting.GRAY), false);
        }
    }

    private static String modelListLabel(AiProvider provider) {
        return switch (provider) {
            case OPENROUTER -> "OpenRouter";
            case CODEX_LOCAL -> "Codex";
            case HERMES -> "Hermes";
            case CURSOR -> "Cursor";
        };
    }

    private static String authHint(AiProvider provider) {
        return switch (provider) {
            case OPENROUTER -> "Set an OpenRouter key with /apikey <key>.";
            case CODEX_LOCAL -> "Run `codex login` on the Codex host, then retry.";
            case HERMES -> "Set the Hermes token with /hermestoken <token> if your endpoint requires one.";
            case CURSOR -> "Run Cursor CLI authentication on the bridge host, then retry.";
        };
    }

    private static boolean modelAvailable(List<ProviderModel> models, String currentModel) {
        String requested = currentModel == null ? "" : currentModel.trim();
        if (requested.isBlank()) {
            return false;
        }
        for (ProviderModel model : models) {
            if (requested.equals(model.id()) || requested.equals(model.displayName())) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static Boolean parseEnabled(String value) {
        if (ENABLED_VALUES.contains(value)) {
            return true;
        }
        if (DISABLED_VALUES.contains(value)) {
            return false;
        }
        return null;
    }
}
