package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.impl.ParameterLookup;
import io.github.olegnyr.knobs.impl.ParameterStore;
import io.github.olegnyr.knobs.impl.model.ParameterDefinition;
import io.github.olegnyr.knobs.impl.snapshot.ParameterSet;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Источник свойств параметров — вид Spring на снимок пода
 * {@link ParameterStore}; снимком владеет хранилка, источник его только читает.
 *
 * <p>Значение считается на каждом чтении общим выбором {@link ParameterLookup}: путь пода (сервис,
 * ЦОД), дополненный путём обращения текущего потока. На потоке {@code main} при связывании бинов
 * пути обращения нет — свойство связывается значением пода; геттер группы параметров на потоке
 * обращения видит правило по измерениям обращения — например, по сегменту клиента. Значение отдаётся как записано —
 * строкой либо списком строк, — конвертацию к типу читателя делает Spring; в {@code /actuator/env}
 * администратор видит ту же запись, что в файле.
 *
 * <p>Имена — все ключи каталога снимка, есть у них значение для текущего пути или нет: от этого
 * массива {@code Binder} кэширует отображение имён, и плавать от контекста он не должен. Ключ без
 * значения — {@code null} из {@link #getProperty}, и Spring идёт в следующий источник; поэтому
 * {@link #containsProperty} отвечает по значению, а не по имени.
 */
class ParameterPropertySource extends EnumerablePropertySource<ParameterStore> {

    /** Имя источника в {@code Environment}. */
    static final String NAME = "knobsParameters";

    private final ParameterLookup lookup;

    /**
     * Источник над хранилкой снимка.
     *
     * @param store снимок пода
     */
    ParameterPropertySource(ParameterStore store) {
        super(NAME, store);
        this.lookup = new ParameterLookup(store);
    }

    /**
     * Хранилка снимка из окружения.
     *
     * @param environment окружение сервиса
     * @return хранилка; пусто — слоя параметров нет
     */
    static Optional<ParameterStore> store(ConfigurableEnvironment environment) {
        return environment.getPropertySources().get(NAME) instanceof ParameterPropertySource layer
                ? Optional.of(layer.getSource()) : Optional.empty();
    }

    @Override
    public String[] getPropertyNames() {
        ParameterSet snapshot = getSource().parameters();
        return snapshot.parameters().stream().map(ParameterDefinition::getKey).toArray(String[]::new);
    }

    @Override
    public boolean containsProperty(String name) {
        return getProperty(name) != null;
    }

    @Override
    public Object getProperty(String name) {
        return lookup.lookup(name, ContextPath.EMPTY).map(typed -> asWritten(typed.node())).orElse(null);
    }

    private static Object asWritten(JsonNode node) {
        if (!node.isArray()) {
            return node.asString();
        }
        List<String> elements = new ArrayList<>(node.size());
        node.forEach(element -> elements.add(element.asString()));
        return List.copyOf(elements);
    }

    /**
     * Прочитана ли реплика: {@code false} — под работает на файле.
     *
     * @return признак слоя реплики
     */
    boolean isReplicaLoaded() {
        return getSource().isReplicaLoaded();
    }
}
