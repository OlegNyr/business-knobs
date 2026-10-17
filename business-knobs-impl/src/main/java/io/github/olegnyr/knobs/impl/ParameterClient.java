package io.github.olegnyr.knobs.impl;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.Parameters;
import io.github.olegnyr.knobs.impl.evaluation.ContextDimensions;
import io.github.olegnyr.knobs.impl.model.ParameterDefinition;
import io.github.olegnyr.knobs.impl.snapshot.ParameterSet;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Чтение параметра с контекстом обращения.
 *
 * <p>Путь — путь пода (сервис, ЦОД), дополненный путём обращения текущего потока
 * ({@link io.github.olegnyr.knobs.ParameterContext}). {@link #client()} — стандартный {@code Client} OpenFeature над
 * параметрами (домен {@code business-knobs}); длительность и дату OpenFeature не знает, их
 * отдают {@link #duration(String)} и {@link #date(String)}. «Значения нет» отличимо от
 * значения: пустой {@code Optional} здесь, {@code FLAG_NOT_FOUND} у {@code Client}.
 */
public class ParameterClient implements Parameters, AutoCloseable {

    /**
     * Домен OpenFeature, в котором зарегистрирован провайдер параметров.
     */
    public static final String DOMAIN = "business-knobs";

    private final ParameterStore store;

    private final ParameterLookup lookup;

    private final OpenFeatureAPI api;

    private final Client client;

    private ParameterClient(ParameterStore store, OpenFeatureAPI api) {
        this.store = Objects.requireNonNull(store);
        this.lookup = new ParameterLookup(this.store);
        this.api = api;
        this.client = api.getClient(DOMAIN);
    }

    /**
     * Клиент над снимком пода с провайдером OpenFeature в изолированном экземпляре API: регистрация в
     * процессном синглтоне перетёрла бы провайдер другого контекста того же процесса.
     *
     * @param store снимок пода; без файла стенда — пустой, значений нет
     * @return клиент; закрыть вместе с владельцем
     */
    public static ParameterClient create(ParameterStore store) {
        OpenFeatureAPI api = OpenFeatureAPI.createIsolated();
        ParameterClient parameters = new ParameterClient(store, api);
        api.setProviderAndWait(DOMAIN, new ParameterFeatureProvider(parameters));
        return parameters;
    }

    /**
     * Закрывает изолированный экземпляр OpenFeature вместе с владельцем.
     */
    @Override
    public void close() {
        api.shutdown();
    }

    @Override
    public Client client() {
        return client;
    }

    @Override
    public Optional<Object> value(String key) {
        return lookup(key).map(ParameterLookup.Typed::value);
    }

    @Override
    public Optional<Duration> duration(String key) {
        return value(key).filter(Duration.class::isInstance).map(Duration.class::cast);
    }

    @Override
    public Optional<LocalDate> date(String key) {
        return value(key).filter(LocalDate.class::isInstance).map(LocalDate.class::cast);
    }

    @Override
    public Map<String, Object> byPrefix(String prefix) {
        ParameterSet snapshot = store.parameters();
        Map<String, Object> values = new LinkedHashMap<>();
        String segments = prefix.endsWith(".") ? prefix : prefix + ".";
        for (ParameterDefinition parameter : snapshot.parameters()) {
            if (parameter.getKey().startsWith(segments) || parameter.getKey().equals(prefix)) {
                lookup(parameter.getKey(), ContextPath.EMPTY)
                        .ifPresent(typed -> values.put(parameter.getKey(), typed.value()));
            }
        }
        return Map.copyOf(values);
    }

    /**
     * Измерения пути действующего снимка.
     *
     * @return измерения в порядке точности
     */
    ContextDimensions dimensions() {
        return store.parameters().dimensions();
    }

    Optional<ParameterLookup.Typed> lookup(String key) {
        return lookup(key, ContextPath.EMPTY);
    }

    /**
     * Значение параметра для пути вызова — через общий выбор {@link ParameterLookup}.
     *
     * @param key      ключ параметра
     * @param explicit явный путь вызова — атрибуты контекста OpenFeature; сильнее пути потока
     * @return значение с описанием параметра; пусто — значения нет
     */
    Optional<ParameterLookup.Typed> lookup(String key, ContextPath explicit) {
        return lookup.lookup(key, explicit);
    }
}
