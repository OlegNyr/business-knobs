package io.github.olegnyr.knobs.impl.format;

import io.github.olegnyr.knobs.ParameterType;
import io.github.olegnyr.knobs.impl.model.ParameterDefinition;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * Сверка значения с ограничениями каталога — {@code allowed}, {@code min}, {@code max}.
 * Ввод в админке их проверяет сама админка; здесь — ручной ввод на поде,
 * аварийная заплатка. Значение уже прочитано своим типом; сравнение — в типе Java: длительность
 * как {@link java.time.Duration}, дата как {@link java.time.LocalDate}. Причина — без значения.
 */
public final class ParameterConstraints {

    private ParameterConstraints() {
    }

    /**
     * Нарушение ограничений каталога; у списка — первое нарушение среди элементов.
     *
     * @param value      значение параметра или правила, читаемое типом параметра
     * @param definition параметр каталога
     * @return причина без значения; пусто — ограничения соблюдены либо их нет
     */
    public static Optional<String> violation(JsonNode value, ParameterDefinition definition) {
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (value.isArray()) {
            for (JsonNode element : value) {
                Optional<String> violation = violation(element, definition);
                if (violation.isPresent()) {
                    return violation;
                }
            }
            return Optional.empty();
        }
        if (value.isString() && value.stringValue().contains("${")) {
            // Подстановка проверяется не здесь: в правке её отбивают раньше, в файле — после разрешения.
            return Optional.empty();
        }
        ParameterType type = definition.getType();
        Object actual = type.read(value);
        List<JsonNode> allowed = definition.getAllowed();
        if (!allowed.isEmpty() && allowed.stream().map(type::read).noneMatch(actual::equals)) {
            return Optional.of("значение вне allowed каталога");
        }
        if (definition.getMin() != null && compare(actual, type.read(definition.getMin())) < 0) {
            return Optional.of("значение меньше min каталога");
        }
        if (definition.getMax() != null && compare(actual, type.read(definition.getMax())) > 0) {
            return Optional.of("значение больше max каталога");
        }
        return Optional.empty();
    }

    // unchecked: обе стороны прочитаны одним типом параметра — Long, Double, Duration, LocalDate,
    // String; все они Comparable самим себе. min/max не своего типа отбивает генератор.
    @SuppressWarnings("unchecked")
    private static int compare(Object actual, Object bound) {
        return ((Comparable<Object>) actual).compareTo(bound);
    }
}
