package com.angel.aibuilder.ai;

public record AiCompletion(String text, String usageSummary, String pendingUsageId) {
    public AiCompletion(String text, String usageSummary) {
        this(text, usageSummary, "");
    }

    public boolean hasUsageSummary() {
        return usageSummary != null && !usageSummary.isBlank();
    }

    public boolean hasPendingUsage() {
        return pendingUsageId != null && !pendingUsageId.isBlank();
    }
}
