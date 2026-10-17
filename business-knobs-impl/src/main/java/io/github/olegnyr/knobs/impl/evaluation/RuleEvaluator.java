package io.github.olegnyr.knobs.impl.evaluation;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterRule;
import io.github.olegnyr.knobs.impl.model.ParameterDefinition;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Значение параметра для пути контекста.
 *
 * <p>Выбор в два шага:
 * <ol>
 *     <li>один раз, при сборке снимка пода, правила параметра готовятся к чтению
 *     ({@link #prepared}): правило, которое не подошло бы ни к одному пути, отбрасывается, остальные
 *     выстраиваются по точности — самое точное первым; порядок правил в файле, реплике и топике
 *     роли не играет;</li>
 *     <li>при каждом чтении правила проверяются в этом порядке ({@link #evaluate}): первое
 *     подошедшее ({@link #matches}) действует, дальше не смотрят. Не подошло ни одно — действует
 *     значение параметра; нет и его — значения нет.</li>
 * </ol>
 * Для ЦОД {@code dc2} и сегмента VIP порядок такой: {@code /dc2/VIP}, {@code /&#42;/VIP},
 * {@code /dc2}, затем значение параметра.
 *
 * <p>Каждый шаг пишется в лог на уровне TRACE: ключ, путь правила и почему оно подошло или нет.
 * Значения параметра в лог не идут никогда — только ключи и значения измерений пути.
 */
@Slf4j
public final class RuleEvaluator {

    private RuleEvaluator() {
    }

    /**
     * Правила, готовые к чтению: без тех, что не подошли бы ни к одному пути, по точности — самое
     * точное первым. Вызывается один раз, при сборке снимка пода, чтобы при чтении оставалось одно
     * сравнение частей пути.
     *
     * <p>Не подошло бы ни к одному пути правило без значения (подстановка в файле не разрешилась
     * либо в записи значения нет), правило с измерением, которого библиотека не знает, и правило с
     * частью без значения. Каждое такое правило пишется в лог на уровне TRACE с причиной — один
     * раз, при сборке, а не при каждом чтении.
     *
     * @param key        ключ параметра — для лога
     * @param rules      правила параметра в любом порядке
     * @param dimensions измерения пути в порядке точности
     * @return неизменяемый список правил, которые могут подойти, в порядке точности
     */
    public static List<ParameterRule> prepared(String key, List<ParameterRule> rules,
                                               ContextDimensions dimensions) {
        List<ParameterRule> usable = new ArrayList<>(rules.size());
        for (ParameterRule rule : rules) {
            Optional<String> reason = whyNeverMatches(rule, dimensions);
            if (reason.isPresent()) {
                log.trace("Ключ {}: правило {} в выборе не участвует — {}", key, rule.getWhen(), reason.get());
            } else {
                usable.add(rule);
            }
        }
        return mostSpecificFirst(usable, dimensions);
    }

    /**
     * Почему правило не подошло бы ни к одному пути.
     *
     * @param rule       правило
     * @param dimensions измерения пути
     * @return причина; пусто — правило может подойти
     */
    private static Optional<String> whyNeverMatches(ParameterRule rule, ContextDimensions dimensions) {
        if (!hasValue(rule.getValue())) {
            return Optional.of("без значения");
        }
        for (Map.Entry<String, String> part : rule.getWhen().entrySet()) {
            String dimension = part.getKey();
            if (!dimensions.isKnown(dimension)) {
                return Optional.of("измерение " + dimension + " не задано в измерениях пути " + dimensions);
            }
            if (part.getValue() == null) {
                return Optional.of("у части " + dimension + " нет значения");
            }
        }
        return Optional.empty();
    }

    /**
     * Действующее значение параметра для пути контекста: первое подошедшее правило, иначе значение
     * параметра.
     *
     * <p>Правила должны быть готовы к чтению ({@link #prepared}) — так их кладёт снимок пода при
     * каждой сборке. Тогда первое подошедшее и есть самое точное из подошедших: правила, которые
     * могут подойти к одному пути одновременно, по точности никогда не равны.
     *
     * @param parameter параметр с правилами, готовыми к чтению
     * @param context   путь контекста
     * @return копия значения правила с самым точным подошедшим путём, иначе значения параметра;
     * пусто, если значения нет
     */
    public static Optional<JsonNode> evaluate(ParameterDefinition parameter, ContextPath context) {
        String key = parameter.getKey();
        for (ParameterRule rule : parameter.getRules()) {
            if (matches(key, rule.getWhen(), context)) {
                log.trace("Параметр {}: действует правило {} — первое подошедшее по точности", key, rule.getWhen());
                return Optional.of(rule.getValue());
            }
        }
        JsonNode value = parameter.getValue();
        if (hasValue(value)) {
            log.trace("Параметр {}: ни одно правило не подошло — действует значение параметра", key);
            return Optional.of(value);
        }
        log.trace("Параметр {}: ни одно правило не подошло, у параметра значения нет — значения нет", key);
        return Optional.empty();
    }

    /**
     * Подходит ли правило к пути контекста: каждая названная в нём часть совпала со своей частью
     * пути. Части, которой нет в контексте, ничто не равно: «не задано» не совпадает. Не названная
     * часть — «звёздочка»: её не сравнивают. Правило с измерением, которого библиотека не знает, и
     * правило с частью без значения сюда не доходят — их снимает {@link #prepared}.
     *
     * <p>Сравнение каждой части пишется в лог на уровне TRACE: совпавшие — по одной строке,
     * первая несовпавшая — с причиной, на ней проверка и заканчивается.
     *
     * @param key     ключ параметра — для лога
     * @param when    путь правила
     * @param context путь контекста
     * @return {@code true} — правило подходит
     */
    static boolean matches(String key, Map<String, String> when, ContextPath context) {
        for (Map.Entry<String, String> part : when.entrySet()) {
            String dimension = part.getKey();
            String actual = context.get(dimension).orElse(null);
            if (actual == null || !actual.equals(part.getValue())) {
                if (log.isTraceEnabled()) {
                    log.trace("Параметр {}: правило {} не подходит — {}", key, when,
                            new Mismatch(dimension, part.getValue(), actual).asTrace());
                }
                return false;
            }
            log.trace("Параметр {}: правило {}: {} совпала — {}", key, when, dimension, actual);
        }
        return true;
    }

    /**
     * Часть пути правила, не совпавшая с путём контекста.
     *
     * @param dimension измерение
     * @param expected  значение части в правиле
     * @param actual    значение части в пути; {@code null} — часть не задана
     */
    private record Mismatch(String dimension, String expected, String actual) {

        /**
         * Почему часть не совпала — для лога TRACE. Здесь значения измерений пути, а не значения
         * параметра.
         *
         * @return строка вида {@code tenant: в правиле t2, в пути не задано}
         */
        String asTrace() {
            return dimension + ": в правиле " + expected + ", в пути " + (actual == null ? "не задано" : actual);
        }
    }

    /**
     * Правила по точности — самое точное первым. Порядок на входе роли не играет; правила равной
     * точности сохраняют взаимный порядок (они не могут подойти к одному пути одновременно).
     *
     * @param rules      правила параметра в любом порядке
     * @param dimensions измерения пути в порядке точности
     * @return неизменяемый список правил в порядке точности
     */
    static List<ParameterRule> mostSpecificFirst(List<ParameterRule> rules, ContextDimensions dimensions) {
        List<String> order = dimensions.order();
        List<ParameterRule> ranked = new ArrayList<>(rules);
        ranked.sort((first, second) -> compareSpecificity(second.getWhen(), first.getWhen(), order));
        return Collections.unmodifiableList(ranked);
    }

    /**
     * Какой из двух путей правил точнее: больше нуля — {@code first}, меньше — {@code second}, ноль —
     * равны. Критерии по очереди, следующий — только при равенстве предыдущего:
     * <ol>
     *     <li>последняя названная часть стоит дальше в порядке измерений:
     *     {@code /&#42;/&#42;/VIP} точнее {@code /dc2};</li>
     *     <li>названо больше частей: {@code /dc2/&#42;/VIP} точнее {@code /&#42;/&#42;/VIP};</li>
     *     <li>правило своего сервиса точнее правила для всех: {@code orders/dc2} точнее {@code /dc2};</li>
     *     <li>названные части стоят дальше: с конца порядка измерений точнее тот, кто первым
     *     назвал часть, которой нет у другого — {@code /&#42;/t2/VIP} точнее {@code /dc2/&#42;/VIP}.</li>
     * </ol>
     * Условие {@code service} в первые два критерия не входит: оно лишь разводит правило своего
     * сервиса и правило для всех при прочих равных.
     */
    private static int compareSpecificity(Map<String, String> first, Map<String, String> second,
                                          List<String> order) {
        int byLastPart = Integer.compare(lastNamed(first, order), lastNamed(second, order));
        if (byLastPart != 0) {
            return byLastPart;
        }
        int byPartCount = Integer.compare(named(first, order), named(second, order));
        if (byPartCount != 0) {
            return byPartCount;
        }
        int byOwnService = Boolean.compare(first.containsKey(ContextPath.SERVICE),
                second.containsKey(ContextPath.SERVICE));
        if (byOwnService != 0) {
            return byOwnService;
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            String dimension = order.get(i);
            int byPlace = Boolean.compare(first.containsKey(dimension), second.containsKey(dimension));
            if (byPlace != 0) {
                return byPlace;
            }
        }
        return 0;
    }

    /**
     * Место последней названной части пути в порядке измерений.
     *
     * @return индекс; {@code -1} — ни одна часть не названа
     */
    private static int lastNamed(Map<String, String> when, List<String> order) {
        for (int i = order.size() - 1; i >= 0; i--) {
            if (when.containsKey(order.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Сколько частей пути из порядка измерений названо.
     */
    private static int named(Map<String, String> when, List<String> order) {
        int named = 0;
        for (String dimension : order) {
            if (when.containsKey(dimension)) {
                named++;
            }
        }
        return named;
    }

    private static boolean hasValue(JsonNode value) {
        return value != null && !value.isNull() && !value.isMissingNode();
    }
}
