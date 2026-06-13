package com.angel.aibuilder.ai;

import java.util.List;

public record ProviderStatus(
        AiProvider provider,
        String target,
        boolean reachable,
        boolean authenticated,
        boolean needsLogin,
        String authLabel,
        boolean modelsAvailable,
        int modelCount,
        String defaultModel,
        boolean currentModelAvailable,
        String normalizedCurrentModel,
        List<String> supportedEfforts,
        String detail
) {
    public ProviderStatus {
        target = target == null ? "" : target.trim();
        authLabel = authLabel == null ? "" : authLabel.trim();
        defaultModel = defaultModel == null ? "" : defaultModel.trim();
        normalizedCurrentModel = normalizedCurrentModel == null ? "" : normalizedCurrentModel.trim();
        supportedEfforts = supportedEfforts == null ? List.of() : List.copyOf(supportedEfforts);
        detail = detail == null ? "" : detail.trim();
    }
}
