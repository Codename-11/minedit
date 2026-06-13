package com.angel.aibuilder.codex;

import com.angel.aibuilder.ai.AiCompletion;
import com.angel.aibuilder.ai.CancellationToken;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Closeable;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class CodexLocalClient {
    private static final Gson GSON = new Gson();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public AiCompletion complete(String baseUrl, String model, String effort, String prompt) throws IOException, InterruptedException {
        return complete(baseUrl, "", model, effort, prompt, new CancellationToken());
    }

    public AiCompletion complete(String baseUrl, String model, String effort, String prompt, CancellationToken token) throws IOException, InterruptedException {
        return complete(baseUrl, "", model, effort, prompt, token);
    }

    public AiCompletion complete(String baseUrl, String codexToken, String model, String effort, String prompt, CancellationToken token) throws IOException, InterruptedException {
        if (isDirectAppServerUrl(baseUrl)) {
            return completeDirect(baseUrl, codexToken, model, effort, prompt, token);
        }
        token.throwIfCancelled();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("effort", effort);
        body.addProperty("prompt", prompt);

        JsonObject json = sendJson(endpoint(baseUrl, "/complete"), "POST", body, Duration.ofMinutes(12));
        token.throwIfCancelled();
        JsonElement text = json.get("text");
        if (text == null || !text.isJsonPrimitive()) {
            throw new IOException("Codex bridge response did not include text.");
        }
        return new AiCompletion(text.getAsString(), "");
    }

    public AiCompletion agentBuild(String baseUrl, String model, String effort, String prompt, int width, int depth, Consumer<String> progress) throws IOException, InterruptedException {
        return agentBuild(baseUrl, "", model, effort, prompt, width, depth, new CancellationToken(), progress);
    }

    public AiCompletion agentBuild(String baseUrl, String model, String effort, String prompt, int width, int depth, CancellationToken token, Consumer<String> progress) throws IOException, InterruptedException {
        return agentBuild(baseUrl, "", model, effort, prompt, width, depth, token, progress);
    }

    public AiCompletion agentBuild(String baseUrl, String codexToken, String model, String effort, String prompt, int width, int depth, CancellationToken token, Consumer<String> progress) throws IOException, InterruptedException {
        if (isDirectAppServerUrl(baseUrl)) {
            progress.accept("Codex direct app-server: generating build code.");
            return completeDirect(baseUrl, codexToken, model, effort, prompt, token);
        }
        token.throwIfCancelled();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("effort", effort);
        body.addProperty("prompt", prompt);
        body.addProperty("width", width);
        body.addProperty("depth", depth);

        JsonObject started = sendJson(endpoint(baseUrl, "/agent-build/start"), "POST", body, Duration.ofSeconds(45));
        String jobId = string(started, "jobId", "");
        if (jobId.isBlank()) {
            throw new IOException("Codex bridge did not return an agent job id.");
        }

        Closeable cancelJob = () -> cancelAgentJobAsync(baseUrl, jobId);
        token.register(cancelJob);
        long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
        int afterSeq = 0;
        try {
            while (System.nanoTime() < deadline) {
                token.throwIfCancelled();
                JsonObject status = sendJson(endpoint(baseUrl, "/agent-build/status?id=" + encode(jobId) + "&after=" + afterSeq), "GET", null, Duration.ofSeconds(30));
                JsonArray events = status.getAsJsonArray("events");
                if (events != null) {
                    for (JsonElement eventElement : events) {
                        if (!eventElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject event = eventElement.getAsJsonObject();
                        afterSeq = Math.max(afterSeq, integer(event, "seq", afterSeq));
                        String message = string(event, "message", "");
                        if (!message.isBlank()) {
                            progress.accept(message);
                        }
                    }
                }

                String state = string(status, "status", "running");
                if ("completed".equals(state)) {
                    JsonElement text = status.get("text");
                    if (text == null || !text.isJsonPrimitive()) {
                        throw new IOException("Codex agent completed without returning build code.");
                    }
                    String summary = string(status, "summary", "");
                    return new AiCompletion(text.getAsString(), summary);
                }
                if ("cancelled".equals(state)) {
                    throw new InterruptedException("Codex agent build was stopped.");
                }
                if ("failed".equals(state)) {
                    throw new IOException(string(status, "error", "Codex agent build failed."));
                }

                Thread.sleep(2000);
            }
        } finally {
            token.unregister(cancelJob);
        }

        throw new IOException("Codex agent build timed out after 20 minutes.");
    }

    public AiCompletion agentStepByStepBuild(String baseUrl, String model, String effort, String prompt, int width, int depth, Consumer<String> progress, Consumer<StepBatch> batchConsumer) throws IOException, InterruptedException {
        return agentStepByStepBuild(baseUrl, "", model, effort, prompt, width, depth, new CancellationToken(), progress, batchConsumer);
    }

    public AiCompletion agentStepByStepBuild(String baseUrl, String model, String effort, String prompt, int width, int depth, CancellationToken token, Consumer<String> progress, Consumer<StepBatch> batchConsumer) throws IOException, InterruptedException {
        return agentStepByStepBuild(baseUrl, "", model, effort, prompt, width, depth, token, progress, batchConsumer);
    }

    public AiCompletion agentStepByStepBuild(String baseUrl, String codexToken, String model, String effort, String prompt, int width, int depth, CancellationToken token, Consumer<String> progress, Consumer<StepBatch> batchConsumer) throws IOException, InterruptedException {
        if (isDirectAppServerUrl(baseUrl)) {
            progress.accept("Codex direct app-server: step-by-step bridge tools are not available, generating one direct batch.");
            AiCompletion completion = completeDirect(baseUrl, codexToken, model, effort, prompt, token);
            batchConsumer.accept(new StepBatch(1, completion.text(), 0, 0, "Codex direct app-server batch"));
            return completion;
        }
        token.throwIfCancelled();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("effort", effort);
        body.addProperty("prompt", prompt);
        body.addProperty("width", width);
        body.addProperty("depth", depth);

        JsonObject started = sendJson(endpoint(baseUrl, "/agent-build/step-by-step/start"), "POST", body, Duration.ofSeconds(45));
        String jobId = string(started, "jobId", "");
        if (jobId.isBlank()) {
            throw new IOException("Codex bridge did not return a step-by-step agent job id.");
        }

        Closeable cancelJob = () -> cancelAgentJobAsync(baseUrl, jobId);
        token.register(cancelJob);
        long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
        int afterSeq = 0;
        int afterBatch = 0;
        StringBuilder combinedCode = new StringBuilder();
        try {
            while (System.nanoTime() < deadline) {
                token.throwIfCancelled();
                JsonObject status = sendJson(endpoint(baseUrl, "/agent-build/status?id=" + encode(jobId) + "&after=" + afterSeq + "&afterBatch=" + afterBatch), "GET", null, Duration.ofSeconds(30));
                JsonArray events = status.getAsJsonArray("events");
                if (events != null) {
                    for (JsonElement eventElement : events) {
                        if (!eventElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject event = eventElement.getAsJsonObject();
                        afterSeq = Math.max(afterSeq, integer(event, "seq", afterSeq));
                        String message = string(event, "message", "");
                        if (!message.isBlank()) {
                            progress.accept(message);
                        }
                    }
                }

                JsonArray batches = status.getAsJsonArray("batches");
                if (batches != null) {
                    for (JsonElement batchElement : batches) {
                        if (!batchElement.isJsonObject()) {
                            continue;
                        }
                        token.throwIfCancelled();
                        JsonObject batchObject = batchElement.getAsJsonObject();
                        int batchId = integer(batchObject, "id", afterBatch);
                        afterBatch = Math.max(afterBatch, batchId);
                        String code = string(batchObject, "code", "");
                        if (code.isBlank()) {
                            continue;
                        }
                        StepBatch batch = new StepBatch(
                                batchId,
                                code,
                                integer(batchObject, "operationCount", 0),
                                integer(batchObject, "nonAirBlocks", 0),
                                string(batchObject, "summary", "")
                        );
                        if (!combinedCode.isEmpty()) {
                            combinedCode.append("\n\n");
                        }
                        combinedCode.append("// step ").append(batch.id()).append("\n").append(batch.code());
                        batchConsumer.accept(batch);
                    }
                }

                String state = string(status, "status", "running");
                if ("completed".equals(state)) {
                    String text = string(status, "text", combinedCode.toString());
                    if (text.isBlank()) {
                        text = combinedCode.toString();
                    }
                    String summary = string(status, "summary", "");
                    return new AiCompletion(text, summary);
                }
                if ("cancelled".equals(state)) {
                    throw new InterruptedException("Codex step-by-step agent build was stopped.");
                }
                if ("failed".equals(state)) {
                    throw new IOException(string(status, "error", "Codex step-by-step agent build failed."));
                }

                Thread.sleep(1000);
            }
        } finally {
            token.unregister(cancelJob);
        }

        throw new IOException("Codex step-by-step agent build timed out after 30 minutes.");
    }

    public Status status(String baseUrl, String currentModel) throws IOException, InterruptedException {
        return status(baseUrl, "", currentModel);
    }

    public Status status(String baseUrl, String codexToken, String currentModel) throws IOException, InterruptedException {
        if (isDirectAppServerUrl(baseUrl)) {
            return statusDirect(baseUrl, codexToken, currentModel);
        }
        JsonObject json = sendJson(endpoint(baseUrl, "/status"), "GET", null, Duration.ofSeconds(45));
        boolean needsLogin = bool(json, "needsLogin");
        boolean requiresOpenaiAuth = bool(json, "requiresOpenaiAuth");
        String authLabel = "not required";
        JsonObject account = json.getAsJsonObject("account");
        if (account != null) {
            String type = string(account, "type", "unknown");
            String email = string(account, "email", "");
            String planType = string(account, "planType", "");
            authLabel = type + (email.isEmpty() ? "" : " (" + email + ")") + (planType.isEmpty() ? "" : ", " + planType);
        } else if (requiresOpenaiAuth) {
            authLabel = "not logged in";
        }

        String normalizedModel = normalizeCodexModel(currentModel);
        int modelCount = 0;
        String defaultModel = "";
        boolean currentModelAvailable = false;
        List<String> supportedEfforts = new ArrayList<>();
        JsonArray models = json.getAsJsonArray("models");
        if (models != null) {
            modelCount = models.size();
            for (JsonElement modelElement : models) {
                if (!modelElement.isJsonObject()) {
                    continue;
                }
                JsonObject modelObject = modelElement.getAsJsonObject();
                String id = string(modelObject, "id", "");
                String model = string(modelObject, "model", "");
                if (bool(modelObject, "isDefault")) {
                    defaultModel = model.isEmpty() ? id : model;
                }
                if (normalizedModel.equals(id) || normalizedModel.equals(model)) {
                    currentModelAvailable = true;
                    JsonArray efforts = modelObject.getAsJsonArray("supportedReasoningEfforts");
                    if (efforts != null) {
                        for (JsonElement effort : efforts) {
                            if (effort.isJsonPrimitive()) {
                                supportedEfforts.add(effort.getAsString());
                            }
                        }
                    }
                }
            }
        }

        return new Status(needsLogin, authLabel, modelCount, defaultModel, currentModelAvailable, normalizedModel, supportedEfforts, false);
    }

    private AiCompletion completeDirect(String baseUrl, String codexToken, String model, String effort, String prompt, CancellationToken token) throws IOException, InterruptedException {
        if (prompt == null || prompt.isBlank()) {
            throw new IOException("Missing prompt.");
        }
        try (DirectCodexAppServerSession session = DirectCodexAppServerSession.connect(client, URI.create(baseUrl.trim()), codexToken)) {
            token.register(session);
            try {
                token.throwIfCancelled();
                session.initialize();
                JsonObject account = session.request("account/read", object(Map.of("refreshToken", false)), Duration.ofSeconds(30));
                if (bool(account, "requiresOpenaiAuth") && !account.has("account")) {
                    throw new IOException("Codex is not logged in. Run `codex login` on the Codex app-server host, then retry.");
                }

                JsonArray models = listModelsDirect(session);
                JsonObject modelEntry = findModel(models, model);
                if (modelEntry == null) {
                    String visible = visibleModels(models);
                    throw new IOException("Codex model '" + normalizeCodexModel(model) + "' was not found. Try /model gpt-5.5 or use /codex status. Available examples: " + (visible.isBlank() ? "none returned" : visible));
                }

                List<String> supportedEfforts = effortList(modelEntry);
                if (!supportedEfforts.isEmpty() && !supportedEfforts.contains(effort)) {
                    throw new IOException("Codex model '" + modelName(modelEntry) + "' does not support reasoning effort '" + effort + "'. Supported: " + String.join(", ", supportedEfforts));
                }

                String modelToUse = modelName(modelEntry);
                JsonObject threadParams = new JsonObject();
                threadParams.addProperty("model", modelToUse);
                threadParams.addProperty("cwd", System.getProperty("user.dir", "."));
                threadParams.addProperty("ephemeral", true);
                threadParams.addProperty("serviceName", "minedit");
                threadParams.addProperty("approvalPolicy", "never");
                threadParams.addProperty("sandbox", "read-only");
                threadParams.addProperty("personality", "pragmatic");
                threadParams.addProperty("developerInstructions", "You are a text-only Minecraft builder-code generator for Minedit. Do not inspect files, run commands, or use tools. Return only the requested JavaScript build(api) function or a fenced JavaScript block containing it.");
                JsonObject threadResult = session.request("thread/start", threadParams, Duration.ofSeconds(30));
                String threadId = string(object(threadResult, "thread"), "id", "");
                if (threadId.isBlank()) {
                    throw new IOException("Codex app-server did not return a thread id.");
                }

                String text = runDirectTurn(session, threadId, modelToUse, effort, prompt, token);
                return new AiCompletion(text, "");
            } finally {
                token.unregister(session);
            }
        }
    }

    private Status statusDirect(String baseUrl, String codexToken, String currentModel) throws IOException, InterruptedException {
        try (DirectCodexAppServerSession session = DirectCodexAppServerSession.connect(client, URI.create(baseUrl.trim()), codexToken)) {
            session.initialize();
            JsonObject account = session.request("account/read", object(Map.of("refreshToken", false)), Duration.ofSeconds(30));
            JsonArray models = listModelsDirect(session);
            boolean needsLogin = bool(account, "requiresOpenaiAuth") && !account.has("account");
            String authLabel = "not required";
            JsonObject accountObject = object(account, "account");
            if (accountObject != null) {
                String type = string(accountObject, "type", "unknown");
                String email = string(accountObject, "email", "");
                String planType = string(accountObject, "planType", "");
                authLabel = type + (email.isEmpty() ? "" : " (" + email + ")") + (planType.isEmpty() ? "" : ", " + planType);
            } else if (bool(account, "requiresOpenaiAuth")) {
                authLabel = "not logged in";
            }
            return statusFromModels(needsLogin, authLabel, models, currentModel, true);
        }
    }

    private String runDirectTurn(DirectCodexAppServerSession session, String threadId, String model, String effort, String prompt, CancellationToken token) throws IOException, InterruptedException {
        JsonObject turnParams = new JsonObject();
        turnParams.addProperty("threadId", threadId);
        JsonArray input = new JsonArray();
        JsonObject textInput = new JsonObject();
        textInput.addProperty("type", "text");
        textInput.addProperty("text", prompt);
        input.add(textInput);
        turnParams.add("input", input);
        turnParams.addProperty("model", model);
        turnParams.addProperty("effort", effort);
        turnParams.addProperty("approvalPolicy", "never");

        JsonObject turnResult = session.request("turn/start", turnParams, Duration.ofSeconds(30));
        JsonObject startedTurn = object(turnResult, "turn");
        String turnId = string(startedTurn, "id", "");
        if (turnId.isBlank()) {
            throw new IOException("Codex app-server did not return a turn id.");
        }
        if ("completed".equals(string(startedTurn, "status", ""))) {
            return completedTurnText(startedTurn, Map.of());
        }

        Map<String, String> deltasByItemId = new ConcurrentHashMap<>();
        Map<String, String> completedAgentMessages = new ConcurrentHashMap<>();
        long deadline = System.nanoTime() + Duration.ofMinutes(12).toNanos();
        while (System.nanoTime() < deadline) {
            token.throwIfCancelled();
            JsonObject notification = session.pollNotification(Duration.ofMillis(250));
            if (notification == null) {
                continue;
            }
            JsonObject params = object(notification, "params");
            if (params == null || !threadId.equals(string(params, "threadId", ""))) {
                continue;
            }
            String method = string(notification, "method", "");
            if ("item/agentMessage/delta".equals(method)) {
                String itemId = string(params, "itemId", "message");
                deltasByItemId.merge(itemId, string(params, "delta", ""), String::concat);
            } else if ("item/completed".equals(method)) {
                JsonObject item = object(params, "item");
                if (item != null && "agentMessage".equals(string(item, "type", ""))) {
                    String text = string(item, "text", "");
                    if (!text.isBlank()) {
                        completedAgentMessages.put(string(item, "id", Integer.toString(completedAgentMessages.size())), text);
                    }
                }
            } else if ("turn/completed".equals(method)) {
                JsonObject turn = object(params, "turn");
                if (turnId.equals(string(turn, "id", ""))) {
                    return completedTurnText(turn, completedAgentMessages.isEmpty() ? deltasByItemId : completedAgentMessages);
                }
            }
        }
        throw new IOException("Codex turn timed out after 12 minutes.");
    }

    private String completedTurnText(JsonObject turn, Map<String, String> fallbackByItemId) throws IOException {
        String status = string(turn, "status", "");
        if ("failed".equals(status)) {
            JsonObject error = object(turn, "error");
            throw new IOException(string(error, "message", "Codex turn failed."));
        }
        if (!"completed".equals(status)) {
            throw new IOException("Codex turn ended with status '" + status + "'.");
        }

        JsonArray items = turn == null ? null : turn.getAsJsonArray("items");
        String lastAgentMessage = "";
        if (items != null) {
            for (JsonElement element : items) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                if (!"agentMessage".equals(string(item, "type", ""))) {
                    continue;
                }
                String text = string(item, "text", "");
                if ("final_answer".equals(string(item, "phase", "")) && !text.isBlank()) {
                    return text.trim();
                }
                if (!text.isBlank()) {
                    lastAgentMessage = text;
                }
            }
        }
        String text = lastAgentMessage.isBlank() ? String.join("", fallbackByItemId.values()) : lastAgentMessage;
        if (text.isBlank()) {
            throw new IOException("Codex completed but returned no agent message text.");
        }
        return text.trim();
    }

    private JsonArray listModelsDirect(DirectCodexAppServerSession session) throws IOException, InterruptedException {
        JsonObject result = session.request("model/list", object(Map.of("limit", 200, "includeHidden", true)), Duration.ofSeconds(30));
        JsonArray models = result.getAsJsonArray("data");
        return models == null ? new JsonArray() : models;
    }

    private Status statusFromModels(boolean needsLogin, String authLabel, JsonArray models, String currentModel, boolean directAppServer) {
        String normalizedModel = normalizeCodexModel(currentModel);
        int modelCount = 0;
        String defaultModel = "";
        boolean currentModelAvailable = false;
        List<String> supportedEfforts = new ArrayList<>();
        if (models != null) {
            modelCount = models.size();
            for (JsonElement modelElement : models) {
                if (!modelElement.isJsonObject()) {
                    continue;
                }
                JsonObject modelObject = modelElement.getAsJsonObject();
                String id = string(modelObject, "id", "");
                String model = string(modelObject, "model", "");
                if (bool(modelObject, "isDefault")) {
                    defaultModel = model.isEmpty() ? id : model;
                }
                if (normalizedModel.equals(id) || normalizedModel.equals(model)) {
                    currentModelAvailable = true;
                    supportedEfforts = effortList(modelObject);
                }
            }
        }
        return new Status(needsLogin, authLabel, modelCount, defaultModel, currentModelAvailable, normalizedModel, supportedEfforts, directAppServer);
    }

    private static JsonObject object(Map<String, Object> values) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean bool) {
                result.addProperty(entry.getKey(), bool);
            } else if (value instanceof Number number) {
                result.addProperty(entry.getKey(), number);
            } else if (value != null) {
                result.addProperty(entry.getKey(), value.toString());
            }
        }
        return result;
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonObject findModel(JsonArray models, String requested) {
        String normalized = normalizeCodexModel(requested);
        for (JsonElement modelElement : models) {
            if (!modelElement.isJsonObject()) {
                continue;
            }
            JsonObject model = modelElement.getAsJsonObject();
            if (normalized.equals(string(model, "id", "")) || normalized.equals(string(model, "model", "")) || requested.equals(string(model, "displayName", ""))) {
                return model;
            }
        }
        return null;
    }

    private static String visibleModels(JsonArray models) {
        List<String> visible = new ArrayList<>();
        for (JsonElement modelElement : models) {
            if (!modelElement.isJsonObject()) {
                continue;
            }
            JsonObject model = modelElement.getAsJsonObject();
            if (bool(model, "hidden")) {
                continue;
            }
            visible.add(modelName(model));
            if (visible.size() >= 12) {
                break;
            }
        }
        return String.join(", ", visible);
    }

    private static String modelName(JsonObject model) {
        String name = string(model, "model", "");
        return name.isBlank() ? string(model, "id", "") : name;
    }

    private static List<String> effortList(JsonObject model) {
        List<String> efforts = new ArrayList<>();
        JsonArray entries = model == null ? null : model.getAsJsonArray("supportedReasoningEfforts");
        if (entries == null) {
            return efforts;
        }
        for (JsonElement effortElement : entries) {
            if (effortElement.isJsonPrimitive()) {
                efforts.add(effortElement.getAsString());
            } else if (effortElement.isJsonObject()) {
                String effort = string(effortElement.getAsJsonObject(), "reasoningEffort", "");
                if (!effort.isBlank()) {
                    efforts.add(effort);
                }
            }
        }
        return efforts;
    }

    private JsonObject sendJson(URI endpoint, String method, JsonObject body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new IOException("Cannot connect to Codex bridge at " + baseUrl(endpoint) + ". Start it with `npm --prefix bridge start` in the Minedit repo.", e);
        }

        JsonObject json = parseJson(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || !bool(json, "ok")) {
            String message = string(json, "error", "Codex bridge returned HTTP " + response.statusCode());
            throw new IOException(message);
        }
        return json;
    }

    private void cancelAgentJobAsync(String baseUrl, String jobId) {
        JsonObject body = new JsonObject();
        body.addProperty("jobId", jobId);
        HttpRequest request = HttpRequest.newBuilder(endpoint(baseUrl, "/agent-build/cancel"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ignored -> null);
    }

    private static JsonObject parseJson(String body) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new IOException("Codex bridge returned non-object JSON.");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Codex bridge returned invalid JSON: " + body, e);
        }
    }

    private static URI endpoint(String baseUrl, String path) {
        String normalized = baseUrl.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized + path);
    }

    private static String baseUrl(URI endpoint) {
        int port = endpoint.getPort();
        return endpoint.getScheme() + "://" + endpoint.getHost() + (port >= 0 ? ":" + port : "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String normalizeCodexModel(String model) {
        String trimmed = model.trim();
        return trimmed.startsWith("openai/") ? trimmed.substring("openai/".length()) : trimmed;
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() && !value.isJsonNull() ? value.getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() && !value.isJsonNull() ? value.getAsInt() : fallback;
    }

    private static boolean isDirectAppServerUrl(String url) {
        String normalized = url == null ? "" : url.trim().toLowerCase();
        return normalized.startsWith("ws://") || normalized.startsWith("wss://");
    }

    private static final class DirectCodexAppServerSession implements WebSocket.Listener, Closeable {
        private final AtomicInteger nextId = new AtomicInteger(1);
        private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
        private final BlockingQueue<JsonObject> notifications = new LinkedBlockingQueue<>();
        private final StringBuilder textBuffer = new StringBuilder();
        private WebSocket webSocket;
        private volatile boolean closed;

        private static DirectCodexAppServerSession connect(HttpClient client, URI uri, String token) throws IOException, InterruptedException {
            DirectCodexAppServerSession session = new DirectCodexAppServerSession();
            WebSocket.Builder builder = client.newWebSocketBuilder();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token.trim());
            }
            try {
                session.webSocket = builder.buildAsync(uri, session).get(10, TimeUnit.SECONDS);
                return session;
            } catch (ExecutionException e) {
                throw new IOException("Cannot connect to Codex app-server at " + uri + ": " + rootMessage(e), e);
            } catch (TimeoutException e) {
                throw new IOException("Timed out connecting to Codex app-server at " + uri + ".", e);
            }
        }

        private void initialize() throws IOException, InterruptedException {
            JsonObject params = new JsonObject();
            JsonObject clientInfo = new JsonObject();
            clientInfo.addProperty("name", "minedit_mod");
            clientInfo.addProperty("title", "Minedit Mod");
            clientInfo.addProperty("version", "0.1.0");
            JsonObject capabilities = new JsonObject();
            capabilities.addProperty("experimentalApi", true);
            params.add("clientInfo", clientInfo);
            params.add("capabilities", capabilities);
            request("initialize", params, Duration.ofSeconds(30));
            notify("initialized", new JsonObject());
        }

        private JsonObject request(String method, JsonObject params, Duration timeout) throws IOException, InterruptedException {
            if (closed) {
                throw new IOException("Codex app-server WebSocket is closed.");
            }
            int id = nextId.getAndIncrement();
            JsonObject message = new JsonObject();
            message.addProperty("method", method);
            message.addProperty("id", id);
            message.add("params", params == null ? new JsonObject() : params);
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            pending.put(id, future);
            send(message);
            JsonObject response;
            try {
                response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw new IOException(rootMessage(e), e);
            } catch (TimeoutException e) {
                pending.remove(id);
                throw new IOException(method + " timed out after " + timeout.toSeconds() + "s.", e);
            }

            JsonObject error = object(response, "error");
            if (error != null) {
                throw new IOException(string(error, "message", "Codex app-server error."));
            }
            JsonObject result = object(response, "result");
            return result == null ? new JsonObject() : result;
        }

        private JsonObject pollNotification(Duration timeout) throws InterruptedException {
            return notifications.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void notify(String method, JsonObject params) throws IOException {
            JsonObject message = new JsonObject();
            message.addProperty("method", method);
            message.add("params", params == null ? new JsonObject() : params);
            send(message);
        }

        private void send(JsonObject message) throws IOException {
            if (webSocket == null || closed) {
                throw new IOException("Codex app-server WebSocket is not open.");
            }
            try {
                webSocket.sendText(GSON.toJson(message), true).join();
            } catch (CompletionException e) {
                throw new IOException("Could not send Codex app-server message: " + rootMessage(e), e);
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                handleMessage(textBuffer.toString());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            failAll(new IOException("Codex app-server WebSocket closed (" + statusCode + (reason == null || reason.isBlank() ? "" : ": " + reason) + ")."));
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed = true;
            failAll(new IOException("Codex app-server WebSocket error: " + rootMessage(error), error));
        }

        private void handleMessage(String text) {
            JsonObject message;
            try {
                message = JsonParser.parseString(text).getAsJsonObject();
            } catch (RuntimeException ignored) {
                return;
            }
            if (message.has("id") && message.has("method")) {
                handleServerRequest(message);
                return;
            }
            if (message.has("id")) {
                int id = integer(message, "id", -1);
                CompletableFuture<JsonObject> future = pending.remove(id);
                if (future != null) {
                    future.complete(message);
                }
                return;
            }
            if (message.has("method")) {
                notifications.offer(message);
            }
        }

        private void handleServerRequest(JsonObject message) {
            String method = string(message, "method", "");
            JsonObject response = new JsonObject();
            response.add("id", message.get("id"));
            if ("item/commandExecution/requestApproval".equals(method) || "item/fileChange/requestApproval".equals(method)) {
                response.add("result", object(Map.of("decision", "cancel")));
            } else if ("tool/requestUserInput".equals(method) || "item/tool/requestUserInput".equals(method)) {
                JsonObject result = new JsonObject();
                result.add("answers", new JsonObject());
                response.add("result", result);
            } else if ("item/tool/call".equals(method)) {
                JsonObject result = new JsonObject();
                JsonArray contentItems = new JsonArray();
                JsonObject content = new JsonObject();
                content.addProperty("type", "inputText");
                content.addProperty("text", "Minedit direct Codex mode does not expose bridge dynamic tools.");
                contentItems.add(content);
                result.addProperty("success", false);
                result.add("contentItems", contentItems);
                response.add("result", result);
            } else {
                JsonObject error = new JsonObject();
                error.addProperty("code", -32601);
                error.addProperty("message", "Minedit does not handle Codex server request '" + method + "'.");
                response.add("error", error);
            }
            try {
                send(response);
            } catch (IOException ignored) {
            }
        }

        private void failAll(IOException error) {
            for (CompletableFuture<JsonObject> future : pending.values()) {
                future.completeExceptionally(error);
            }
            pending.clear();
        }

        @Override
        public void close() {
            closed = true;
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Minedit request finished");
            }
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    public record Status(
            boolean needsLogin,
            String authLabel,
            int modelCount,
            String defaultModel,
            boolean currentModelAvailable,
            String normalizedCurrentModel,
            List<String> supportedEfforts,
            boolean directAppServer
    ) {
    }

    public record StepBatch(int id, String code, int operationCount, int nonAirBlocks, String summary) {
    }
}
