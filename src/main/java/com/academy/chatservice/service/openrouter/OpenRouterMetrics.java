package com.academy.chatservice.service.openrouter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

@Component
public class OpenRouterMetrics {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterMetrics.class);

    private final LongAdder modelSwitches = new LongAdder();
    private final LongAdder rateLimits = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> responsesByModel = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> responseTimeByModel = new ConcurrentHashMap<>();

    public void recordRateLimit(String model) {
        rateLimits.increment();
        log.info("OpenRouter metrics: model={} rateLimits={}", model, rateLimits.sum());
    }

    public void recordSwitch(String fromModel, String toModel) {
        modelSwitches.increment();
        log.info("OpenRouter metrics: switchedModels={} from={} to={}", modelSwitches.sum(), fromModel, toModel);
    }

    public void recordResponse(String model, long elapsedMs, boolean finalModel) {
        responsesByModel.computeIfAbsent(model, ignored -> new LongAdder()).increment();
        responseTimeByModel.computeIfAbsent(model, ignored -> new LongAdder()).add(elapsedMs);
        log.info(
                "OpenRouter metrics: model={} elapsedMs={} responsesByModel={} finalModel={}",
                model,
                elapsedMs,
                responsesByModel.get(model).sum(),
                finalModel
        );
    }
}
