package com.angel.aibuilder.ai;

public record AiRequestOptions(
        AiProvider provider,
        String openRouterApiKey,
        String codexUrl,
        String hermesUrl,
        String hermesToken,
        String model,
        String effort,
        boolean streaming
) {
    public AiRequestOptions(AiProvider provider, String openRouterApiKey, String codexUrl, String model, String effort, boolean streaming) {
        this(provider, openRouterApiKey, codexUrl, "", "", model, effort, streaming);
    }

    public String targetDescription() {
        return switch (provider) {
            case OPENROUTER -> model + " via OpenRouter";
            case CODEX_LOCAL -> model + " via Codex bridge at " + codexUrl;
            case HERMES -> model + " via Hermes at " + hermesUrl;
            case CURSOR -> model + " via Cursor bridge at " + codexUrl;
        };
    }
}
