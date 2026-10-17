package io.github.olegnyr.knobs.generator;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Измерения из секции {@code dimensions} исходного файла и разбор путей правил по ним.
 *
 * <p>Порядок объявления без {@code service} — порядок точности, который библиотека возьмёт из
 * файла стенда, и порядок мест короткого пути {@code /dc2/&#42;/VIP}. Короткий путь привязан к
 * позициям: вставленное в середину измерение сдвинет смысл уже написанных путей. Поэтому значения
 * измерений {@code kind: code} сверяются со списком {@code values}, а для остального есть
 * именованная форма {@code {segment: VIP}}, которой порядок не важен.
 */
final class SourceDimensions {

    private static final String SERVICE = "service";

    private static final Set<String> KINDS = Set.of("code", "stand", "registry");

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final List<String> order;

    private final Map<String, Set<String>> codeValues;

    private final ArrayNode declarations;

    private SourceDimensions(List<String> order, Map<String, Set<String>> codeValues, ArrayNode declarations) {
        this.order = order;
        this.codeValues = codeValues;
        this.declarations = declarations;
    }

    /**
     * Читает секцию {@code dimensions}.
     *
     * @param section значение секции; {@code null} — измерений нет
     * @return измерения файла
     * @throws GeneratorException объявление негодно: нет {@code kind} либо он незнаком, у
     *                            {@code code} нет {@code values}
     */
    static SourceDimensions read(Object section) {
        List<String> order = new ArrayList<>();
        Map<String, Set<String>> codeValues = new LinkedHashMap<>();
        ArrayNode declarations = NODES.arrayNode();
        if (section == null) {
            return new SourceDimensions(List.of(), Map.of(), declarations);
        }
        if (!(section instanceof Map<?, ?> dimensions)) {
            throw new GeneratorException("dimensions: ожидается отображение «измерение → {kind, values}»");
        }
        dimensions.forEach((rawKey, rawDeclaration) -> {
            String key = String.valueOf(rawKey);
            if (!(rawDeclaration instanceof Map<?, ?> fields)) {
                throw new GeneratorException("измерение " + key + ": ожидается {kind, values}");
            }
            String kind = fields.get("kind") == null ? "" : String.valueOf(fields.get("kind")).toLowerCase(Locale.ROOT);
            if (!KINDS.contains(kind)) {
                throw new GeneratorException("измерение " + key + ": kind — code, stand либо registry");
            }
            ObjectNode node = NODES.objectNode();
            node.put("key", key);
            node.put("kind", kind.toUpperCase(Locale.ROOT));
            if (fields.get("values") != null) {
                if (!(fields.get("values") instanceof List<?> values)) {
                    throw new GeneratorException("измерение " + key + ": values — список");
                }
                ArrayNode array = node.putArray("values");
                values.forEach(value -> array.add(String.valueOf(value)));
                if ("code".equals(kind)) {
                    codeValues.put(key, new LinkedHashSet<>(values.stream().map(String::valueOf).toList()));
                }
            }
            if ("code".equals(kind) && !codeValues.containsKey(key)) {
                throw new GeneratorException("измерение " + key + ": у kind: code нужны values");
            }
            if (!SERVICE.equals(key)) {
                order.add(key);
            }
            declarations.add(node);
        });
        return new SourceDimensions(List.copyOf(order), codeValues, declarations);
    }

    /**
     * Объявления измерений для файла стенда.
     *
     * @return массив {@code {key, kind, values}}
     */
    ArrayNode declarations() {
        return declarations.deepCopy();
    }

    /**
     * Разбирает элемент {@code when}: короткий путь {@code /dc2/&#42;/VIP} (с {@code "*}{@code /…"} —
     * для всех сервисов) либо именованную форму {@code {dc: dc2, segment: VIP}}.
     *
     * @param when элемент списка {@code when}
     * @param key  ключ параметра — для отказа
     * @return путь правила
     * @throws GeneratorException путь негоден
     */
    RulePath parse(Object when, String key) {
        if (when instanceof Map<?, ?> named) {
            return named(named, key);
        }
        if (when instanceof String text) {
            return path(text, key);
        }
        throw new GeneratorException(key + ": элемент when — путь строкой либо {измерение: значение}");
    }

    private RulePath path(String text, String key) {
        boolean allServices = text.startsWith("*/");
        String rest = allServices ? text.substring(1) : text;
        if (!rest.startsWith("/")) {
            throw new GeneratorException(key + ": путь «" + text + "» должен начинаться с / либо с */");
        }
        List<String> parts = List.of(rest.substring(1).split("/", -1));
        if (parts.size() > order.size()) {
            throw new GeneratorException(key + ": путь «" + text + "» — места пути по измерениям файла " + order);
        }
        if (parts.contains("")) {
            throw new GeneratorException(key + ": путь «" + text + "» — пустое место: «любое значение» пишется *");
        }
        if (parts.stream().allMatch("*"::equals)) {
            throw new GeneratorException(key + ": путь «" + text + "» подходит всегда — для этого есть default");
        }
        if ("*".equals(parts.getLast())) {
            throw new GeneratorException(key + ": путь «" + text + "» — последняя часть звёздочкой не заменяется");
        }
        Map<String, String> when = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            if (!"*".equals(parts.get(i))) {
                when.put(order.get(i), checked(order.get(i), parts.get(i), key));
            }
        }
        return new RulePath(allServices, Collections.unmodifiableMap(when), parts.size() - 1);
    }

    private RulePath named(Map<?, ?> named, String key) {
        if (named.isEmpty()) {
            throw new GeneratorException(key + ": пустое when — для правила «везде» when не пишется");
        }
        Map<String, String> given = new LinkedHashMap<>();
        named.forEach((rawDimension, rawValue) -> {
            String dimension = String.valueOf(rawDimension);
            if (SERVICE.equals(dimension)) {
                throw new GeneratorException(key + ": service в when не пишется — правило всегда своего сервиса, "
                        + "для всех сервисов — путь \"*/…\"");
            }
            if (!order.contains(dimension)) {
                throw new GeneratorException(key + ": измерение " + dimension + " не объявлено в dimensions " + order);
            }
            if (rawValue == null) {
                throw new GeneratorException(key + ": у измерения " + dimension + " в when нет значения");
            }
            given.put(dimension, checked(dimension, String.valueOf(rawValue), key));
        });
        Map<String, String> when = new LinkedHashMap<>();
        int lastNamed = -1;
        for (int i = 0; i < order.size(); i++) {
            String dimension = order.get(i);
            if (given.containsKey(dimension)) {
                when.put(dimension, given.get(dimension));
                lastNamed = i;
            }
        }
        return new RulePath(false, Collections.unmodifiableMap(when), lastNamed);
    }

    private String checked(String dimension, String value, String key) {
        Set<String> allowed = codeValues.get(dimension);
        if (allowed != null && !allowed.contains(value)) {
            throw new GeneratorException(key + ": значение измерения " + dimension + " не из values " + allowed);
        }
        return value;
    }
}
