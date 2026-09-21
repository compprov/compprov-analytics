package io.compprov.analytics.ai;

public record CallDetails(
        String requestedAt,
        long durationMs,
        String modelName,
        String responseId,
        String finishReason,
        Integer inputTokenCount,
        Integer outputTokenCount,
        Integer totalTokenCount) {

}
