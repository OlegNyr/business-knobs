package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterContext;
import io.github.olegnyr.knobs.impl.evaluation.ContextDimensions;
import io.github.olegnyr.knobs.impl.evaluation.RuleEvaluator;
import io.github.olegnyr.knobs.impl.model.ParameterDefinition;
import io.github.olegnyr.knobs.impl.snapshot.ParameterSet;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * Выбор значения параметра по пути вызова — одна функция на все
 * входы: {@code Parameters}, {@code Client} OpenFeature и Spring-источник свойств. Путь — путь пода
 * (сервис, ЦОД), дополненный явным путём вызова и путём обращения текущего потока
 * ({@link ParameterContext}). Каждый шаг пишется в лог на уровне TRACE, значения — никогда.
 */
@Slf4j
public final class ParameterLookup {

    private final ParameterStore store;

    /**
     * Выбор над снимком пода.
     *
     * @param store снимок пода
     */
    public ParameterLookup(ParameterStore store) {
        this.store = Objects.requireNonNull(store);
    }

    /**
     * Снимок, над которым идёт выбор.
     *
     * @return хранилка снимка
     */
    public ParameterStore store() {
        return store;
    }

    /**
     * Значение параметра для пути вызова.
     *
     * @param key      ключ параметра
     * @param explicit явный путь вызова — атрибуты контекста OpenFeature; сильнее пути потока
     * @return значение с описанием параметра; пусто — значения нет
     */
    public Optional<Typed> lookup(String key, ContextPath explicit) {
        return lookup(store.parameters(), key, explicit);
    }

    /**
     * Значение параметра по шагам.
     * <ol>
     *     <li>Действующее определение ключа в снимке пода. Источник — аварийная правка, реплика или
     *     файл — снимок выбрал заранее, при сборке ({@link ParameterSet}).</li>
     *     <li>Путь выбора правила ({@link SelectionPath#of}).</li>
     *     <li>Правило с самым точным подошедшим путём, иначе значение параметра
     *     ({@link RuleEvaluator}).</li>
     *     <li>Значение в типе Java.</li>
     * </ol>
     * Снимок передаётся один на весь вызов: замена снимка посреди чтения не смешает определение
     * одного снимка с источником другого.
     */
    private Optional<Typed> lookup(ParameterSet snapshot, String key, ContextPath explicit) {
        Optional<ParameterDefinition> found = snapshot.find(key);
        if (found.isEmpty()) {
            log.trace("Параметр {}: в снимке пода нет — ключ вне каталога либо значения нет ни в одном источнике",
                    key);
            return Optional.empty();
        }
        ParameterDefinition parameter = found.get();
        SelectionPath selection = SelectionPath.of(store.podPath(), explicit,
                ParameterContext.current().orElse(ContextPath.EMPTY), snapshot.dimensions());
        if (log.isTraceEnabled()) {
            log.trace("Параметр {}: источник {}; путь выбора {}", key, snapshot.source(key).orElse(null),
                    selection.asTrace());
        }
        return RuleEvaluator.evaluate(parameter, selection.path())
                .map(node -> new Typed(parameter, node, parameter.getType().read(node)));
    }

    /**
     * Путь, по которому выбирается правило, и то, из чего он собран.
     *
     * @param path     путь выбора
     * @param pod      путь пода: сервис и ЦОД из настроек пода
     * @param explicit явный путь вызова — атрибуты контекста OpenFeature
     * @param thread   путь обращения текущего потока ({@link ParameterContext})
     */
    private record SelectionPath(ContextPath path, ContextPath pod, ContextPath explicit, ContextPath thread) {

        /**
         * Собирает путь выбора: {@code service} и {@code dc} — только пода. ЦОД из вызова или потока
         * не берётся: вызов уходит из сети этого пода, какой бы ЦОД ни назвал контекст. Остальные
         * измерения — из явного пути вызова, иначе из пути потока; нет ни там ни там — часть не
         * задана, и правило, которое её называет, не подойдёт.
         *
         * @param pod        путь пода
         * @param explicit   явный путь вызова
         * @param thread     путь обращения потока
         * @param dimensions измерения пути
         * @return путь выбора с его источниками
         */
        static SelectionPath of(ContextPath pod, ContextPath explicit, ContextPath thread,
                                ContextDimensions dimensions) {
            ContextPath.Builder path = ContextPath.builder()
                    .put(ContextPath.SERVICE, pod.get(ContextPath.SERVICE).orElse(null))
                    .put(ContextPath.DC, pod.get(ContextPath.DC).orElse(null));
            for (String dimension : dimensions.order()) {
                if (!ContextPath.DC.equals(dimension)) {
                    path.put(dimension, explicit.get(dimension).or(() -> thread.get(dimension)).orElse(null));
                }
            }
            return new SelectionPath(path.build(), pod, explicit, thread);
        }

        /**
         * Путь выбора и его источники — для лога TRACE. Здесь значения измерений, а не значения
         * параметра.
         *
         * @return путь выбора, затем пути пода, вызова и потока
         */
        String asTrace() {
            return path + " — под " + pod + ", вызов " + explicit + ", поток " + thread;
        }
    }

    /**
     * Вычисленное значение с описанием параметра.
     *
     * @param parameter параметр
     * @param node      значение как записано: скаляр либо массив скаляров
     * @param value     значение в типе Java
     */
    public record Typed(ParameterDefinition parameter, JsonNode node, Object value) {
    }
}
