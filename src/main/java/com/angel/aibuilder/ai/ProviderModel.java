package com.angel.aibuilder.ai;

import java.util.List;

public record ProviderModel(
        AiProvider provider,
        String id,
        String displayName,
        boolean hidden,
        boolean defaultModel,
        List<String> supportedEfforts
) {
    public ProviderModel {
        id = id == null ? "" : id.trim();
        displayName = displayName == null ? "" : displayName.trim();
        supportedEfforts = supportedEfforts == null ? List.of() : List.copyOf(supportedEfforts);
    }
}
