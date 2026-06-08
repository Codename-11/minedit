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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class CodexLocalClient {
    private static final Gson GSON = new Gson();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public AiCompletion complete(String baseUrl, String model, String effort, String prompt) throws IOException, InterruptedException {
        return complete(baseUrl, model, effort, prompt, new CancellationToken());
    }

    public AiCompletion complete(String baseUrl, String model, String effort, String prompt, CancellationToken token) throws IOException, InterruptedException {
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
        return agentBuild(baseUrl, model, effort, prompt, width, depth, new CancellationToken(), progress);
    }

    public AiCompletion agentBuild(String baseUrl, String model, String effort, String prompt, int width, int depth, CancellationToken token, Consumer<String> progress) throws IOException, InterruptedException {
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
        return agentStepByStepBuild(baseUrl, model, effort, prompt, width, depth, new CancellationToken(), progress, batchConsumer);
    }

    public AiCompletion agentStepByStepBuild(String baseUrl, String model, String effort, String prompt, int width, int depth, CancellationToken token, Consumer<String> progress, Consumer<StepBatch> batchConsumer) throws IOException, InterruptedException {
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

        return new Status(needsLogin, authLabel, modelCount, defaultModel, currentModelAvailable, normalizedModel, supportedEfforts);
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
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && !value.isJsonNull() ? value.getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && !value.isJsonNull() ? value.getAsInt() : fallback;
    }

    public record Status(
            boolean needsLogin,
            String authLabel,
            int modelCount,
            String defaultModel,
            boolean currentModelAvailable,
            String normalizedCurrentModel,
            List<String> supportedEfforts
    ) {
    }

    public record StepBatch(int id, String code, int operationCount, int nonAirBlocks, String summary) {
    }
}
