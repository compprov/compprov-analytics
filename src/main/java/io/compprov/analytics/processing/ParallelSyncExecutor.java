package io.compprov.analytics.processing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.compprov.analytics.ai.Prompt;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;

import static io.compprov.analytics.Commons.LOGGER;

public class ParallelSyncExecutor extends Executor {

    private final ChatModel chatModel;

    public ParallelSyncExecutor(List<Prompt> activePrompts, ChatModel chatModel, Double cleanThreshold, Double suspiciousThreshold) {
        super(activePrompts, cleanThreshold, suspiciousThreshold);
        this.chatModel = chatModel;
    }

    public List<ProcessingResult> processAndShutdown(List<ProcessingResult> preProcessed, int threads) throws Exception {

        final var result = new ArrayList<ProcessingResult>();
        final var executors = Executors.newFixedThreadPool(threads);
        try {

            //queue
            final var resultFutures = new ArrayList<Future<ProcessingResult>>();
            for (var ppr : preProcessed) {
                final var cpgDir = new File(ppr.getProcessingDir());
                final var prompts = collectPrompts(cpgDir);
                resultFutures.add(executors.submit(() -> processCpg(cpgDir, ppr.getSourceFile(), prompts)));
            }

            //await
            for (int i = 0; i < preProcessed.size(); i++) {
                final var ppr = preProcessed.get(i);
                final var f = resultFutures.get(i);
                try {
                    result.add(f.get());
                } catch (Throwable th) {
                    LOGGER.log(Level.SEVERE, "Failed to process file: " + ppr.getSourceFile(), th);
                    ppr.markFailed();
                    result.add(ppr);
                }
            }
        } finally {
            executors.shutdownNow();
        }
        return result;
    }

    private ProcessingResult processCpg(File cpgDir, String sourceFile, Map<Prompt, ChatRequest> prompts) {

        //execute prompts
        final var result = buildProcessingResult(cpgDir, sourceFile);
        for (var e : prompts.entrySet()) {
            final var prompt = e.getKey();
            final var request = e.getValue();

            final var requestedAt = Instant.now();
            final var response = chatModel.chat(request);
            final var promptProcessingResult = processPromptResult(cpgDir, requestedAt, prompt, response);
            final var reduced = toReducedPromptProcessingResult(promptProcessingResult);
            LOGGER.info(cpgDir.getName() + " " + prompt.getTemplateName() + ". Verdict: " + reduced.display());
            result.addLlmResult(prompt, reduced);
        }
        return result;
    }
}