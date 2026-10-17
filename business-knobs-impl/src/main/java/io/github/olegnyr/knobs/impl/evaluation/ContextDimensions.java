package io.github.olegnyr.knobs.impl.evaluation;

import io.github.olegnyr.knobs.ContextPath;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Измерения пути контекста в порядке точности: чем дальше в списке последняя названная часть пути
 * правила, тем правило точнее.
 *
 * <p>Порядок задаёт файл стенда — секция {@code dimensions} без {@code service}, в том порядке, в
 * каком генератор разбирал пути правил. Сервис задаёт его сам ({@code knobs.dimensions},
 * {@code Knobs.builder().dimensions(...)}) только когда файл измерений не объявляет; заданный и
 * расходящийся с файлом порядок — отказ на старте. Из измерений библиотека особо знает только
 * {@link ContextPath#DC}: его значение берётся из настроек пода, а не из обращения.
 * {@link ContextPath#SERVICE} в список не входит: это условие правила, а не часть пути.
 */
public final class ContextDimensions {

    /**
     * Порядок без файла стенда и без настройки: одно измерение — ЦОД пода.
     */
    public static final ContextDimensions DEFAULT = of(ContextPath.DC);

    private final List<String> order;

    private ContextDimensions(List<String> order) {
        this.order = order;
    }

    /**
     * Измерения в порядке точности.
     *
     * @param order ключи измерений, от наименее точного к самому точному
     * @return измерения пути
     * @throws IllegalArgumentException список пуст, ключ пуст либо повторяется, в списке {@code service}
     */
    public static ContextDimensions of(String... order) {
        return of(List.of(order));
    }

    /**
     * Измерения в порядке точности.
     *
     * @param order ключи измерений, от наименее точного к самому точному
     * @return измерения пути
     * @throws IllegalArgumentException список пуст, ключ пуст либо повторяется, в списке {@code service}
     */
    public static ContextDimensions of(List<String> order) {
        Objects.requireNonNull(order, "order");
        if (order.isEmpty()) {
            throw new IllegalArgumentException("Список измерений пуст: нужно хотя бы одно измерение");
        }
        Set<String> seen = new HashSet<>();
        for (String dimension : order) {
            if (dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("Измерение пустое: " + order);
            }
            if (ContextPath.SERVICE.equals(dimension)) {
                throw new IllegalArgumentException("service — условие правила, а не измерение пути: " + order);
            }
            if (!seen.add(dimension)) {
                throw new IllegalArgumentException("Измерение повторяется: " + dimension);
            }
        }
        return new ContextDimensions(List.copyOf(order));
    }

    /**
     * Измерения в порядке точности.
     *
     * @return неизменяемый список ключей
     */
    public List<String> order() {
        return order;
    }

    /**
     * Может ли правило называть это измерение: {@code service} либо измерение из списка.
     *
     * @param dimension ключ измерения
     * @return признак известного измерения
     */
    public boolean isKnown(String dimension) {
        return ContextPath.SERVICE.equals(dimension) || order.contains(dimension);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContextDimensions dimensions && order.equals(dimensions.order);
    }

    @Override
    public int hashCode() {
        return order.hashCode();
    }

    @Override
    public String toString() {
        return order.toString();
    }
}
