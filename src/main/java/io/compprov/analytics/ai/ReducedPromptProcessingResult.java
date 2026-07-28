package io.compprov.analytics.ai;

/**
 * The subset of a {@link PromptProcessingResult} kept in {@link io.compprov.analytics.ProcessingResult}
 * for summary tables — verdict and confidence, without the (often large) {@code markdown_report}.
 * Unlike {@link PromptProcessingResult}, this isn't deserialized from the model's JSON response
 * (it's built manually from an already-parsed {@link PromptProcessingResult}), so it uses
 * ordinary Java camelCase rather than matching the model's JSON field names.
 */
public record ReducedPromptProcessingResult(String verdict, Double confidenceScore) {
}
