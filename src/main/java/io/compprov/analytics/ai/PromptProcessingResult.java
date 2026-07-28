package io.compprov.analytics.ai;

/**
 * A {@link Prompt}'s chat-model response, deserialized directly from its JSON reply. Field names
 * are {@code snake_case} to match the JSON schema the prompt asks the model to return
 * ({@code verdict}, {@code confidence_score}, {@code markdown_report}) — see
 * {@code shared_system.md}'s {@code RESPONSE FORMAT} section.
 *
 * @param verdict          one of the values listed in the prompt's {@code <VERDICT>} array (e.g. {@code "CLEAN"})
 * @param confidence_score how confident the model is in {@code verdict}, 0-100
 * @param markdown_report  the detailed markdown write-up (anomaly localization + details)
 */
public record PromptProcessingResult(String verdict, Double confidence_score, String markdown_report) {

}
