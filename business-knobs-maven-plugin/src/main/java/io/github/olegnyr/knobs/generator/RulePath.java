package io.github.olegnyr.knobs.generator;

import java.util.Comparator;
import java.util.Map;

/**
 * Путь правила исходного файла после разбора.
 *
 * @param allServices правило для всех сервисов ({@code "*}{@code /dc2"})
 * @param when        измерение → значение в порядке измерений; места со звёздочкой сюда не входят
 * @param lastNamed   место последней названной части пути в порядке измерений
 */
record RulePath(boolean allServices, Map<String, String> when, int lastNamed) {

    /**
     * Порядок записи правил в файл: дальше последняя названная часть — раньше, при равенстве
     * раньше путь, где названо больше частей. Выбор при чтении от этого порядка не зависит —
     * библиотека выстраивает правила сама; порядок нужен, чтобы файл читался сверху вниз.
     */
    static final Comparator<RulePath> MOST_PRECISE_FIRST = Comparator
            .comparingInt(RulePath::lastNamed).reversed()
            .thenComparing(Comparator.comparingInt((RulePath path) -> path.when().size()).reversed());
}
