package io.compprov.analytics.processing;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import io.compprov.analytics.Commons;
import io.compprov.analytics.ai.CallDetails;
import io.compprov.analytics.ai.Prompt;
import io.compprov.analytics.ai.PromptProcessingResult;
import io.compprov.analytics.ai.ReducedPromptProcessingResult;
import tools.jackson.core.type.TypeReference;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import static io.compprov.analytics.Commons.ENV;
import static io.compprov.analytics.Commons.LOGGER;
import static io.compprov.analytics.Commons.read;
import static io.compprov.analytics.Commons.save;
import static io.compprov.analytics.Commons.serialize;

public abstract class Executor {

    protected final List<Prompt> activePrompts;
    protected final Double cleanThreshold;
    protected final Double suspiciousThreshold;


    public Executor(List<Prompt> activePrompts, Double cleanThreshold, Double suspiciousThreshold) {
        this.activePrompts = activePrompts;
        this.cleanThreshold = cleanThreshold;
        this.suspiciousThreshold = suspiciousThreshold;
    }

    protected ProcessingResult buildProcessingResult(File cpgDir, String sourceFile) {
        final var result = new ProcessingResult(cpgDir.getAbsolutePath(), sourceFile);

        final var chronoCpg = ENV.fromJson(read(cpgDir.toPath().resolve("chrono-violations.json").toFile().getAbsolutePath()));
        if ((!chronoCpg.operations().isEmpty()) || (!chronoCpg.variables().isEmpty())) {
            result.invalidateChronology();
        }
        final var calcCpg = ENV.fromJson(read(cpgDir.toPath().resolve("violated-computations.json").toFile().getAbsolutePath()));
        if ((!calcCpg.operations().isEmpty()) || (!calcCpg.variables().isEmpty())) {
            result.invalidateCalculation();
        }

        ENV.getMapper().readValue(
                read(cpgDir.toPath().resolve("highlights.json").toFile().getAbsolutePath()),
                new TypeReference<List<String>>() {
                }).forEach(h -> result.addHighlight(h));

        return result;
    }

    protected Optional<PromptProcessingResult> processPromptResult(File cpgDir, Instant requestedAt, Prompt prompt, ChatResponse chatResponse) {
        try {
            final var resultJsonFile = cpgDir.toPath().resolve(prompt.jsonResultFilename()).toFile();
            final var resultMdFile = cpgDir.toPath().resolve(prompt.markdownResultFilename()).toFile();
            final var callDetailsFile = cpgDir.toPath().resolve(prompt.callDetailsFilename()).toFile();

            //we don't have these metrics for batch
            final var now = Instant.now();
            final var durationMs = (requestedAt == null) ? -1 : now.toEpochMilli() - requestedAt.toEpochMilli();
            final var finishReason = chatResponse.finishReason();
            final var resultStr = chatResponse.aiMessage() != null ? chatResponse.aiMessage().text() : null;

            final var tokenUsage = chatResponse.tokenUsage();
            //TODO: cached token details are not provided
            final var callDetails = new CallDetails(
                    (requestedAt == null) ? null : requestedAt.toString(),
                    durationMs,
                    chatResponse.modelName(),
                    chatResponse.id(),
                    finishReason != null ? finishReason.toString() : null,
                    tokenUsage != null ? tokenUsage.inputTokenCount() : null,
                    tokenUsage != null ? tokenUsage.outputTokenCount() : null,
                    tokenUsage != null ? tokenUsage.totalTokenCount() : null);
            save(serialize(callDetails), callDetailsFile.getAbsolutePath());

            if (resultStr == null || resultStr.isBlank()) {
                LOGGER.log(Level.SEVERE, "Empty response for prompt: " + prompt.getDescription()
                        + " (finishReason=" + finishReason + "). This usually means the model exhausted its "
                        + "output token budget (e.g. on internal reasoning) before producing any visible text -- "
                        + "consider raising maxTokens or asking for a more concise report.");
                return Optional.empty();
            }
            if (finishReason == FinishReason.LENGTH) {
                LOGGER.log(Level.WARNING, "Response for prompt: " + prompt.getDescription()
                        + " was truncated (finishReason=LENGTH) before completing -- raw partial response saved to "
                        + prompt.jsonResultFilename() + ", but it may not parse as valid JSON.");
            }

            save(resultStr, resultJsonFile.getAbsolutePath());

            PromptProcessingResult result;
            try {
                result = ENV.getMapper().readValue(resultStr, PromptProcessingResult.class);
            } catch (Throwable ex) {
                LOGGER.log(Level.WARNING, "Unable to parse response as JSON", ex);

                result = tryExtractMarkdownedJson(resultStr);
                if (result == null) {
                    result = tryExtractJsonByFields(resultStr);
                    if (result == null) {
                        LOGGER.log(Level.SEVERE, "Unable to re-parse response as JSON. Response saved into " + prompt.jsonResultFilename(), ex);
                        return Optional.empty();
                    }
                }
            }

            if (result.risk_score() == null || result.risk_score().isNaN()) {
                LOGGER.log(Level.SEVERE, "Prompt " + prompt.getDescription() + " returned no usable risk_score. Response saved into " + prompt.jsonResultFilename());
                return Optional.empty();
            }
            result = clampRiskScore(result, prompt);

            final var report = result.markdown_report() != null ? result.markdown_report() : "";
            try (final var is = Executor.class.getResourceAsStream("/templates/markdown_result.md")) {
                final var markdownResult = new String(is.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("$VERDICT$", toVerdict(result.risk_score()))
                        .replace("$SCORE$", Double.toString(result.risk_score()))
                        .replace("$MARKDOWN$", report);
                save(markdownResult, resultMdFile.getAbsolutePath());
            }
            return Optional.of(result);
        } catch (Throwable e) {
            throw new IllegalStateException("Unable to process prompt: " + prompt.getDescription(), e);
        }
    }

    protected Map<Prompt, ChatRequest> collectPrompts(File cpgDir) throws Exception {
        final var result = new LinkedHashMap<Prompt, ChatRequest>();
        for (var prompt : activePrompts) {
            final var userPromptFile = cpgDir.toPath().resolve(prompt.jsonUserPromptFilename()).toFile();
            final var systemPromptFile = cpgDir.toPath().resolve(prompt.jsonSystemPromptFilename()).toFile();
            if (!userPromptFile.exists()) {
                throw new Exception("Unable to find prompt file: " + userPromptFile.getAbsolutePath());
            }
            if (!systemPromptFile.exists()) {
                throw new Exception("Unable to find prompt file: " + systemPromptFile.getAbsolutePath());
            }
            result.put(prompt, ChatRequest.builder().messages(
                    new SystemMessage(Commons.read(systemPromptFile.getAbsolutePath())),
                    new UserMessage(Commons.read(userPromptFile.getAbsolutePath()))).build());
        }
        return result;
    }

    protected PromptProcessingResult tryExtractMarkdownedJson(String resultStr) {
        try {
            var start = resultStr.indexOf("```json");
            if (start < 0) {
                return null;
            }
            start += "```json".length();

            final var end = resultStr.lastIndexOf("```");
            if (end < 0) {
                return null;
            }
            if (start >= end) {
                return null;
            }

            final var r = ENV.getMapper().readValue(resultStr.substring(start, end), PromptProcessingResult.class);
            LOGGER.info("Processed output manually, extracted payload without losses");
            return r;
        } catch (Throwable throwable) {
            return null;
        }
    }

    protected PromptProcessingResult tryExtractJsonByFields(String resultStr) {
        try {
            final var riskScore = resultStr.indexOf("\"risk_score\"");
            if (riskScore < 0) {
                return null;
            }

            final var markdownreport = resultStr.indexOf("\"markdown_report\"");
            if (markdownreport < 0) {
                return null;
            }

            final var properJsonStr = "{ " + resultStr.substring(riskScore, markdownreport) + "\"markdown_report\": \"failed to parse, see json output for details\" }";
            final var r = ENV.getMapper().readValue(properJsonStr, PromptProcessingResult.class);
            LOGGER.info("Processed output manually, extracted payload without detailed report, but raw response is saved");
            return r;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * risk_score is contractually 0-100, but models occasionally overshoot; clamp rather than propagate a bad value.
     */
    protected static PromptProcessingResult clampRiskScore(PromptProcessingResult result, Prompt prompt) {
        final var raw = result.risk_score();
        final var clamped = Math.max(0.0, Math.min(100.0, raw));
        if (clamped != raw) {
            LOGGER.log(Level.WARNING, "Prompt " + prompt.getDescription() + " returned risk_score=" + raw + " outside [0, 100]; clamped to " + clamped);
            return new PromptProcessingResult(clamped, result.markdown_report());
        }
        return result;
    }

    protected String toVerdict(Double riskScore) {
        if (riskScore < cleanThreshold) {
            return "CLEAN";
        }

        if (riskScore < suspiciousThreshold) {
            return "SUSPICIOUS LOGIC";
        }

        return "ANOMALY DETECTED";
    }

    /**
     * An empty result (failed call or unusable response) becomes {@link ReducedPromptProcessingResult#failed()}.
     */
    protected ReducedPromptProcessingResult toReducedPromptProcessingResult(Optional<PromptProcessingResult> opt) {
        if (opt.isEmpty()) {
            return ReducedPromptProcessingResult.failed();
        }
        final var riskScore = opt.get().risk_score();
        return new ReducedPromptProcessingResult(toVerdict(riskScore), riskScore);
    }
}
