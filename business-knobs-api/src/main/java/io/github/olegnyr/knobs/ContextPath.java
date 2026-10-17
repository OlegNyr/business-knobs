package io.github.olegnyr.knobs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Путь контекста: значения измерений, с которыми сравниваются пути правил.
 *
 * <p>Какие измерения есть и в каком порядке они уточняют друг друга, задаёт сервис при сборке
 * библиотеки ({@code Knobs.builder().dimensions(...)} либо свойство {@code knobs.dimensions}). Как
 * заполнять путь, тоже решает сервис: ЦОД под берёт из окружения, остальные измерения (например,
 * арендатора, сегмент клиента, канал) ставит фильтр HTTP либо перехватчик брокера на входе.
 * Незаполненная часть пути — значит «неизвестно», и правило, которое её называет, не совпадает.
 */
public final class ContextPath {

    /**
     * ЦОД — измерение пода: его значение берётся из настроек пода, а не из обращения.
     */
    public static final String DC = "dc";

    /**
     * Сервис — условие общего параметра: должно совпасть, в точность пути не входит и лишь
     * разводит правило своего сервиса и правило для всех при прочих равных.
     */
    public static final String SERVICE = "service";

    public static final ContextPath EMPTY = ContextPath.builder().build();

    private final Map<String, String> values;

    private ContextPath(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /**
     * Новый сборщик пути.
     *
     * @return пустой сборщик
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Значение измерения в контексте.
     *
     * @param dimension ключ измерения
     * @return значение; пусто, если часть пути не заполнена
     */
    public Optional<String> get(String dimension) {
        return Optional.ofNullable(values.get(dimension));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContextPath path && values.equals(path.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    /**
     * Путь для лога: {@code service}, затем {@code dc}, затем прочие измерения по алфавиту;
     * незаполненные части не печатаются. Здесь только значения измерений — значений параметров в
     * пути не бывает.
     *
     * @return путь вида {@code {service=orders, dc=dc2, segment=VIP}}
     */
    @Override
    public String toString() {
        StringJoiner path = new StringJoiner(", ", "{", "}");
        get(SERVICE).ifPresent(value -> path.add(SERVICE + "=" + value));
        get(DC).ifPresent(value -> path.add(DC + "=" + value));
        values.keySet().stream()
                .filter(dimension -> !SERVICE.equals(dimension) && !DC.equals(dimension))
                .sorted()
                .forEach(dimension -> path.add(dimension + "=" + values.get(dimension)));
        return path.toString();
    }

    /**
     * Сборщик пути. Пустое значение в путь не попадает: «не заполнено» и «пусто» не различаются.
     */
    public static final class Builder {

        private final Map<String, String> values = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Задаёт часть пути.
         *
         * @param dimension ключ измерения либо {@link ContextPath#SERVICE}
         * @param value     значение; пустое и {@code null} пропускаются
         * @return этот сборщик
         */
        public Builder put(String dimension, String value) {
            if (value != null && !value.isBlank()) {
                values.put(dimension, value);
            }
            return this;
        }

        /**
         * Собирает неизменяемый путь.
         *
         * @return путь контекста
         */
        public ContextPath build() {
            return new ContextPath(values);
        }
    }
}
