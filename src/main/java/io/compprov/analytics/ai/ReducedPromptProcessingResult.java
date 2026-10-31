package io.compprov.analytics.ai;

/**
 * Verdict label and risk score of one prompt; {@code riskScore} is {@code null} when the prompt failed.
 */
public record ReducedPromptProcessingResult(String verdict, Double riskScore) {

    public static final String FAILED = "FAILED";

    public static ReducedPromptProcessingResult failed() {
        return new ReducedPromptProcessingResult(FAILED, null);
    }

    public boolean isFailed() {
        return riskScore == null;
    }

    /**
     * Verdict with the score in parentheses, or just {@code FAILED}.
     */
    public String display() {
        return isFailed() ? verdict : verdict + " (" + riskScore + ")";
    }
}
