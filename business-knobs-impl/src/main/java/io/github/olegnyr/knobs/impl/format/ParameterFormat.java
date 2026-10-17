package io.github.olegnyr.knobs.impl.format;

import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterRule;
import io.github.olegnyr.knobs.ParameterType;
import io.github.olegnyr.knobs.impl.model.ParameterDefinition;
import io.github.olegnyr.knobs.impl.model.StandFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Проверка формата файла стенда и записи топика при чтении.
 *
 * <p>Битое содержимое отбраковывается сразу, с ключом и причиной, а не падает позже — на
 * вычислении значения, далеко от причины и без имени параметра.
 */
public final class ParameterFormat {

    private ParameterFormat() {
    }

    static StandFile check(StandFile file) {
        require(file.getService(), "файл стенда: нет поля service");
        require(file.getVersion(), "файл стенда: нет поля version");
        require(file.getStand(), "файл стенда: нет поля stand");
        Set<String> keys = new HashSet<>();
        for (ParameterDefinition parameter : file.getParameters()) {
            if (parameter == null || isBlank(parameter.getKey())) {
                throw new ParameterFormatException("файл стенда: у параметра нет поля key");
            }
            String key = parameter.getKey();
            if (!keys.add(key)) {
                throw new ParameterFormatException("параметр " + key + ": ключ повторяется");
            }
            if (parameter.isDeleted()) {
                continue;
            }
            if (isBlank(parameter.getName())) {
                throw new ParameterFormatException("параметр " + key + ": нет поля name");
            }
            if (parameter.getType() == null) {
                throw new ParameterFormatException("параметр " + key + ": нет поля type");
            }
            checkValue(key, "value", parameter.getValue(), parameter.getType(), parameter.isList());
            checkRules(key, parameter.getRules(), parameter.getType(), parameter.isList());
        }
        return file;
    }

    static ParameterRecord check(ParameterRecord record) {
        String key = record.getKey() == null ? "?" : record.getKey();
        Set<Map<String, String>> paths = new HashSet<>();
        for (ParameterRule rule : record.getRules()) {
            if (rule == null) {
                throw new ParameterFormatException("запись " + key + ": пустое правило");
            }
            checkWhen(key, rule.getWhen());
            if (!paths.add(rule.getWhen())) {
                // Одно правило на путь, как в файле стенда: с дублем выбор зависел бы от порядка правил.
                throw new ParameterFormatException("запись " + key + ": путь правила повторяется");
            }
        }
        return record;
    }

    /**
     * Читается ли значение типом и признаком списка: скаляр у скалярного, массив скаляров без
     * {@code null} у списка.
     *
     * @param value значение
     * @param type  тип параметра
     * @param list  признак списка
     * @return читается ли
     */
    public static boolean readable(JsonNode value, ParameterType type, boolean list) {
        if (value == null || value.isNull()) {
            return true;
        }
        if (value.isArray() != list) {
            return false;
        }
        try {
            // Элемент null в списке тоже отбраковывается: List.copyOf внутри read его не пропускает.
            type.read(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Приводит значение после подстановки к типу параметра: текст из переменной окружения
     * становится числом или признаком, если тип того требует.
     *
     * @param value значение после подстановки
     * @param type  тип параметра
     * @param list  признак списка
     * @return значение типа; пусто — текст не читается этим типом
     */
    public static Optional<JsonNode> coerce(JsonNode value, ParameterType type, boolean list) {
        if (value == null || value.isNull()) {
            return Optional.ofNullable(value);
        }
        JsonNode coerced;
        if (value.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : value) {
                Optional<JsonNode> item = coerceScalar(element, type);
                if (item.isEmpty()) {
                    return Optional.empty();
                }
                array.add(item.get());
            }
            coerced = array;
        } else {
            Optional<JsonNode> scalar = coerceScalar(value, type);
            if (scalar.isEmpty()) {
                return Optional.empty();
            }
            coerced = scalar.get();
        }
        return readable(coerced, type, list) ? Optional.of(coerced) : Optional.empty();
    }

    private static Optional<JsonNode> coerceScalar(JsonNode value, ParameterType type) {
        if (!value.isString()) {
            return Optional.of(value);
        }
        String text = value.stringValue().trim();
        try {
            // Те же записи, что Spring принимает у свойства из переменной окружения.
            return switch (type) {
                case BOOLEAN -> bool(text).map(JsonNodeFactory.instance::booleanNode);
                case INTEGER -> Optional.of(JsonNodeFactory.instance.numberNode(Long.parseLong(text)));
                case DOUBLE -> Optional.of(JsonNodeFactory.instance.numberNode(Double.parseDouble(text)));
                case DURATION -> Optional.of(JsonNodeFactory.instance.stringNode(Durations.parse(text).toString()));
                default -> Optional.of(JsonNodeFactory.instance.stringNode(text));
            };
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> bool(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> Optional.of(Boolean.TRUE);
            case "false", "off", "no", "0" -> Optional.of(Boolean.FALSE);
            default -> Optional.empty();
        };
    }

    private static void checkRules(String key, Iterable<ParameterRule> rules, ParameterType type, boolean list) {
        Set<Map<String, String>> paths = new HashSet<>();
        for (ParameterRule rule : rules) {
            if (rule == null) {
                throw new ParameterFormatException("параметр " + key + ": пустое правило");
            }
            checkWhen(key, rule.getWhen());
            if (!paths.add(rule.getWhen())) {
                // Одно правило на путь: с дублем выбор зависел бы от порядка правил.
                throw new ParameterFormatException("параметр " + key + ": путь правила повторяется");
            }
            checkValue(key, "значение правила", rule.getValue(), type, list);
        }
    }

    private static void checkWhen(String key, Map<String, String> when) {
        when.forEach((dimension, value) -> {
            if (value == null) {
                throw new ParameterFormatException("параметр " + key + ": в пути правила у " + dimension
                        + " нет значения");
            }
        });
    }

    private static void checkValue(String key, String what, JsonNode value, ParameterType type, boolean list) {
        if (value != null && value.isString() && value.stringValue().contains("${")) {
            // Подстановку в файле разрешает читатель; проверять её значение по типу нечем.
            return;
        }
        if (!readable(value, type, list)) {
            throw new ParameterFormatException("параметр " + key + ": " + what + " не читается как " + type
                    + (list ? " (список)" : ""));
        }
    }

    private static void require(String value, String message) {
        if (isBlank(value)) {
            throw new ParameterFormatException(message);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
