package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterRecordApplier;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Locale;

/**
 * Метрики параметров пода: {@code knobs.parameters.records{outcome}},
 * {@code knobs.parameters.consumer.failures}, {@code knobs.parameters.patches},
 * {@code knobs.parameters.file.only} и {@code knobs.parameters.pod{service, dc}} — по ней видно, где
 * под и какой сервис. Ключ параметра — ни в одном теге.
 */
public final class MicrometerParameterObserver implements ParameterObserver {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    /**
     * Наблюдатель над реестром метрик сервиса.
     *
     * @param registry реестр метрик
     * @param store    снимок пода; без файла стенда — пустой
     */
    public MicrometerParameterObserver(MeterRegistry registry, ParameterStore store) {
        this.registry = registry;
        Gauge.builder("knobs.parameters.patches", store, source -> source.parameters().patches().size())
                .description("Действующие аварийные правки пода").register(registry);
        Gauge.builder("knobs.parameters.file.only", store, source -> source.isReplicaLoaded() ? 0 : 1)
                .description("Под работает на файле: реплика не прочитана").register(registry);
        ContextPath pod = store.podPath();
        Gauge.builder("knobs.parameters.pod", () -> 1)
                .description("Сервис и ЦОД пода")
                .tag("service", pod.get(ContextPath.SERVICE).orElse(UNKNOWN))
                .tag("dc", pod.get(ContextPath.DC).orElse(UNKNOWN))
                .register(registry);
    }

    @Override
    public void recordOutcome(ParameterRecordApplier.Outcome outcome) {
        registry.counter("knobs.parameters.records", "outcome", outcome.name().toLowerCase(Locale.ROOT)).increment();
    }

    @Override
    public void consumerFailed() {
        registry.counter("knobs.parameters.consumer.failures").increment();
    }
}
