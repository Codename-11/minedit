package com.angel.aibuilder.hermes;

import com.angel.aibuilder.ai.AiCompletion;
import com.angel.aibuilder.ai.AiProvider;
import com.angel.aibuilder.ai.CancellationToken;
import com.angel.aibuilder.ai.ProviderModel;
import com.angel.aibuilder.ai.ProviderStatus;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public final class HermesRunsClient {
    private static final Gson GSON = new Gson();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public ProviderStatus providerStatus(String baseUrl, String authToken, String currentModel) throws IOException, InterruptedException {
        int statusCode = probeRunsApi(baseUrl, authToken);
        boolean needsLogin = statusCode == 401 || statusCode == 403;
        boolean authenticated = !needsLogin;
        String authLabel;
        if (needsLogin) {
            authLabel = authToken == null || authToken.isBlank() ? "token required" : "token rejected";
        } else {
            authLabel = authToken == null || authToken.isBlank() ? "not required or unchecked" : "token accepted or not required";
        }

        return new ProviderStatus(
                AiProvider.HERMES,
                "Hermes runs API",
                true,
                authenticated,
                needsLogin,
                authLabel,
                false,
                0,
                "",
                false,
                currentModel,
                List.of(),
                "Runs API probe returned HTTP " + statusCode + ". Hermes model listing is not exposed by the /v1/runs client."
        );
    }

    public List<ProviderModel> listProviderModels(String baseUrl, String authToken) {
        return List.of();
    }

    public AiCompletion complete(String baseUrl, String authToken, String model, String effort, String prompt, CancellationToken token, Consumer<String> progress) throws IOException, InterruptedException {
        token.throwIfCancelled();
        JsonObject body = new JsonObject();
        body.addProperty("input", prompt);
        body.addProperty("engagement_mode", "observe");
        body.addProperty("forge_contract_version", "minedit-1");
        if (model != null && !model.isBlank()) {
            body.addProperty("model", model);
        }
        if (effort != null && !effort.isBlank()) {
            body.addProperty("reasoning_effort", effort);
        }

        JsonObject started = sendJson(endpoint(baseUrl, "/runs"), "POST", body, authToken, Duration.ofSeconds(45));
        String runId = firstString(started, "run_id", "id");
        if (runId.isBlank()) {
            throw new IOException("Hermes did not return a run id.");
        }

        Closeable cancelRun = () -> stopRunAsync(baseUrl, authToken, runId);
        token.register(cancelRun);
        try {
            progress.accept("Hermes run " + runId + " started.");
            String text = readEvents(baseUrl, authToken, runId, token, progress);
            return new AiCompletion(text, "");
        } finally {
            token.unregister(cancelRun);
        }
    }

    private String readEvents(String baseUrl, String authToken, String runId, CancellationToken token, Consumer<String> progress) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(baseUrl, "/runs/" + encodePath(runId) + "/events"))
                .timeout(Duration.ofHours(2))
                .header("Accept", "text/event-stream");
        addAuth(builder, authToken);

        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new IOException("Cannot connect to Hermes at " + baseUrl + ".", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream stream = response.body()) {
                String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException(errorMessage(body, "Hermes event stream returned HTTP " + response.statusCode()));
            }
        }

        EventState state = new EventState();
        StringBuilder text = new StringBuilder();
        StringBuilder data = new StringBuilder();
        String eventName = "";

        try (InputStream stream = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                token.throwIfCancelled();
                if (line.isEmpty()) {
                    handleEvent(eventName, data.toString(), text, progress, state);
                    if (state.failedMessage != null) {
                        throw new IOException(state.failedMessage);
                    }
                    if (state.completed) {
                        String finalText = state.finalText.isBlank() ? text.toString() : state.finalText;
                        if (finalText.isBlank()) {
                            throw new IOException("Hermes completed without returning text.");
                        }
                        return finalText;
                    }
                    data.setLength(0);
                    eventName = "";
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
        }

        if (!data.isEmpty()) {
            handleEvent(eventName, data.toString(), text, progress, state);
        }
        if (state.failedMessage != null) {
            throw new IOException(state.failedMessage);
        }
        if (state.completed && !state.finalText.isBlank()) {
            return state.finalText;
        }
        if (!text.isEmpty()) {
            return text.toString();
        }
        throw new IOException("Hermes event stream ended before the run completed.");
    }

    private void handleEvent(String eventName, String data, StringBuilder text, Consumer<String> progress, EventState state) {
        if (data == null || data.isBlank()) {
            return;
        }
        if ("[DONE]".equals(data.trim())) {
            state.completed = true;
            return;
        }

        JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(data);
            if (!parsed.isJsonObject()) {
                return;
            }
            object = parsed.getAsJsonObject();
        } catch (RuntimeException ignored) {
            return;
        }

        String type = firstString(object, "event", "type", "name");
        if (type.isBlank()) {
            type = eventName == null ? "" : eventName;
        }

        switch (type) {
            case "message.delta", "agent_message_chunk" -> {
                String delta = messageDelta(object);
                if (!delta.isBlank()) {
                    text.append(delta);
                }
            }
            case "message.completed", "run.completed", "completed" -> {
                state.completed = true;
                String finalText = finalText(object);
                if (!finalText.isBlank()) {
                    state.finalText = finalText;
                }
            }
            case "run.failed", "failed", "error" -> {
                state.failedMessage = firstString(object, "error", "message", "detail");
                if (state.failedMessage.isBlank()) {
                    state.failedMessage = "Hermes run failed.";
                }
            }
            case "run.cancelled", "cancelled" -> state.failedMessage = "Hermes run was stopped.";
            case "tool.started" -> {
                String name = firstString(object, "tool_name", "name");
                progress.accept(name.isBlank() ? "Hermes started a tool." : "Hermes started tool: " + name + ".");
            }
            case "tool.completed" -> {
                String name = firstString(object, "tool_name", "name");
                progress.accept(name.isBlank() ? "Hermes completed a tool." : "Hermes completed tool: " + name + ".");
            }
            case "approval.request" -> progress.accept("Hermes requested approval. Minedit does not approve agent actions automatically.");
            case "reasoning.available" -> progress.accept("Hermes sent a reasoning summary.");
            default -> {
                String message = firstString(object, "message", "summary", "status");
                if (!message.isBlank()) {
                    progress.accept("Hermes: " + message);
                }
            }
        }
    }

    private JsonObject sendJson(URI endpoint, String method, JsonObject body, String authToken, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        addAuth(builder, authToken);
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new IOException("Cannot connect to Hermes at " + baseUrl(endpoint) + ".", e);
        }

        JsonObject json = parseJson(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(errorMessage(response.body(), "Hermes returned HTTP " + response.statusCode()));
        }
        return json;
    }

    private int probeRunsApi(String baseUrl, String authToken) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(baseUrl, "/runs"))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .GET();
        addAuth(builder, authToken);

        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new IOException("Cannot connect to Hermes at " + baseUrl + ".", e);
        }
    }

    private void stopRunAsync(String baseUrl, String authToken, String runId) {
        JsonObject body = new JsonObject();
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(baseUrl, "/runs/" + encodePath(runId) + "/stop"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        addAuth(builder, authToken);
        client.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding())
                .exceptionally(ignored -> null);
    }

    private static JsonObject parseJson(String body) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new IOException("Hermes returned non-object JSON.");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Hermes returned invalid JSON: " + body, e);
        }
    }

    private static String errorMessage(String body, String fallback) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                String message = firstString(parsed.getAsJsonObject(), "error", "message", "detail");
                if (!message.isBlank()) {
                    return message;
                }
            }
        } catch (RuntimeException ignored) {
            // Keep the HTTP fallback below.
        }
        return fallback;
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

    private static void addAuth(HttpRequest.Builder builder, String authToken) {
        if (authToken != null && !authToken.isBlank()) {
            builder.header("Authorization", "Bearer " + authToken.trim());
        }
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String messageDelta(JsonObject object) {
        String value = firstString(object, "delta", "text", "content");
        if (!value.isBlank()) {
            return value;
        }
        JsonObject message = object.getAsJsonObject("message");
        if (message != null) {
            value = firstString(message, "delta", "text", "content");
            if (!value.isBlank()) {
                return value;
            }
        }
        JsonObject data = object.getAsJsonObject("data");
        if (data != null) {
            return firstString(data, "delta", "text", "content");
        }
        return "";
    }

    private static String finalText(JsonObject object) {
        String value = firstString(object, "output", "text", "content");
        if (!value.isBlank()) {
            return value;
        }
        JsonObject result = object.getAsJsonObject("result");
        if (result != null) {
            value = firstString(result, "output", "text", "content");
            if (!value.isBlank()) {
                return value;
            }
        }
        JsonObject data = object.getAsJsonObject("data");
        if (data != null) {
            return firstString(data, "output", "text", "content");
        }
        return "";
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive() && !value.isJsonNull()) {
                String string = value.getAsString();
                if (string != null && !string.isBlank()) {
                    return string;
                }
            }
        }
        return "";
    }

    private static final class EventState {
        private boolean completed;
        private String finalText = "";
        private String failedMessage;
    }
}
