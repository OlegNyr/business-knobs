package io.github.olegnyr.knobs.impl.evaluation;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.impl.TestDimensions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Запись пути правила и контекста в тестах: {@code [сервис]/ЦОД/арендатор/сегмент/канал} — все четыре
 * места в порядке {@link #DIMENSIONS}.
 * <ul>
 *     <li>{@code *} — часть не названа: у правила это звёздочка, «подходит любое значение»; у
 *     контекста — «не задано»;</li>
 *     <li>хвост из {@code *} можно не писать: {@code /dc2} — то же, что {@code /dc2/&#42;/&#42;/&#42;};</li>
 *     <li>сервис — перед первой косой чертой: {@code orders/dc2}.</li>
 * </ul>
 */
final class RulePaths {

    /**
     * Измерения пути в тестах — в том порядке, в каком их задал бы сервис.
     */
    static final ContextDimensions DIMENSIONS = TestDimensions.ALL;

    private RulePaths() {
    }

    /**
     * Части пути из записи.
     *
     * @param text запись пути
     * @return измерение → значение в порядке записи
     */
    static Map<String, String> parse(String text) {
        Map<String, String> parts = new LinkedHashMap<>();
        int slash = text.indexOf('/');
        String service = text.substring(0, slash);
        if (!service.isEmpty() && !"*".equals(service)) {
            parts.put(ContextPath.SERVICE, service);
        }
        String[] places = text.substring(slash + 1).split("/");
        for (int i = 0; i < places.length; i++) {
            if (!places[i].isEmpty() && !"*".equals(places[i])) {
                parts.put(DIMENSIONS.order().get(i), places[i]);
            }
        }
        return parts;
    }

    /**
     * Запись пути из частей — обратное к {@link #parse}: не названные места — {@code *}, хвост из
     * {@code *} отброшен, путь без частей — {@code /*}.
     *
     * @param parts измерение → значение
     * @return запись пути
     */
    static String format(Map<String, String> parts) {
        List<String> places = new ArrayList<>();
        for (String dimension : DIMENSIONS.order()) {
            places.add(parts.getOrDefault(dimension, "*"));
        }
        int named = places.size();
        while (named > 0 && "*".equals(places.get(named - 1))) {
            named--;
        }
        String path = named == 0 ? "/*" : "/" + String.join("/", places.subList(0, named));
        return parts.getOrDefault(ContextPath.SERVICE, "") + path;
    }

    /**
     * Путь контекста из записи.
     *
     * @param text запись пути
     * @return путь контекста
     */
    static ContextPath context(String text) {
        ContextPath.Builder context = ContextPath.builder();
        parse(text).forEach(context::put);
        return context.build();
    }
}
