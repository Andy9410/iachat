package com.academy.chatservice.service.openrouter;

import com.academy.chatservice.config.OpenRouterProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class OpenRouterModelRouter {

    private final List<ModelState> models;
    private final Clock clock;
    private final Duration cooldown;
    private int activeIndex;

    @Autowired
    public OpenRouterModelRouter(OpenRouterProperties properties) {
        this(properties, Clock.systemUTC());
    }

    OpenRouterModelRouter(OpenRouterProperties properties, Clock clock) {
        this.models = configuredModels(properties).stream()
                .map(ModelState::new)
                .toList();
        this.clock = clock;
        this.cooldown = Duration.ofMinutes(Math.max(1, properties.modelCooldownMinutes()));
        this.activeIndex = 0;
    }

    public synchronized ModelSelection selectModel(Set<String> attemptedModels) {
        if (models.isEmpty()) {
            throw new OpenRouterUnavailableException("No hay modelos configurados para OpenRouter.");
        }

        Instant now = clock.instant();
        for (int offset = 0; offset < models.size(); offset++) {
            int index = (activeIndex + offset) % models.size();
            ModelState candidate = models.get(index);
            if (!attemptedModels.contains(candidate.name()) && candidate.isAvailable(now)) {
                return new ModelSelection(candidate.name(), index);
            }
        }

        throw new OpenRouterUnavailableException("Todos los modelos gratuitos de OpenRouter estan temporalmente en cooldown.");
    }

    public synchronized void markUnavailable(String modelName) {
        Instant unavailableUntil = clock.instant().plus(cooldown);
        for (int i = 0; i < models.size(); i++) {
            ModelState model = models.get(i);
            if (model.name().equals(modelName)) {
                model.unavailableUntil(unavailableUntil);
                activeIndex = (i + 1) % models.size();
                return;
            }
        }
    }

    public synchronized void markSuccessful(String modelName) {
        for (int i = 0; i < models.size(); i++) {
            ModelState model = models.get(i);
            if (model.name().equals(modelName)) {
                activeIndex = i;
                model.unavailableUntil(Instant.EPOCH);
                return;
            }
        }
    }

    public synchronized String activeModelName() {
        if (models.isEmpty()) {
            return "unknown";
        }
        return models.get(activeIndex).name();
    }

    public int modelCount() {
        return models.size();
    }

    private static List<String> configuredModels(OpenRouterProperties properties) {
        Set<String> ordered = new LinkedHashSet<>();
        if (properties.models() != null) {
            properties.models().stream()
                    .filter(model -> model != null && !model.isBlank())
                    .map(String::trim)
                    .forEach(ordered::add);
        }
        if (ordered.isEmpty() && properties.model() != null && !properties.model().isBlank()) {
            ordered.add(properties.model().trim());
        }
        return new ArrayList<>(ordered);
    }

    public record ModelSelection(String model, int index) {
    }

    private static final class ModelState {
        private final String name;
        private Instant unavailableUntil = Instant.EPOCH;

        private ModelState(String name) {
            this.name = name;
        }

        private String name() {
            return name;
        }

        private boolean isAvailable(Instant now) {
            return !unavailableUntil.isAfter(now);
        }

        private void unavailableUntil(Instant unavailableUntil) {
            this.unavailableUntil = unavailableUntil;
        }
    }
}
