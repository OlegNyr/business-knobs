package io.github.olegnyr.knobs;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * JSON значений и правил параметра: колонки реплики в базе сервиса. Чтение записи
 * топика с проверкой формата — в исполнении библиотеки, не здесь.
 *
 * <p>Свой {@link JsonMapper}, а не общий маппер приложения: библиотека не знает, какие модули
 * подключил сервис, и не полагается на них. Незнакомые поля пропускаются — так контракт может расти;
 * {@code null} у признака читается как его отсутствие.
 */
public final class ParameterJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private static final TypeReference<List<ParameterRule>> RULES = new TypeReference<>() {
    };

    private ParameterJson() {
    }

    /**
     * Значение в JSON.
     *
     * @param value значение; {@code null} — значения нет
     * @return текст JSON либо {@code null}
     */
    public static String writeValue(JsonNode value) {
        return value == null || value.isNull() ? null : MAPPER.writeValueAsString(value);
    }

    /**
     * Значение из JSON.
     *
     * @param json текст JSON либо {@code null}
     * @return значение либо {@code null}
     */
    public static JsonNode readValue(String json) {
        return json == null ? null : MAPPER.readTree(json);
    }

    /**
     * Правила в JSON-массив.
     *
     * @param rules правила
     * @return текст JSON-массива
     */
    public static String writeRules(List<ParameterRule> rules) {
        return MAPPER.writeValueAsString(rules);
    }

    /**
     * Правила из JSON-массива.
     *
     * @param json текст JSON-массива
     * @return правила
     */
    public static List<ParameterRule> readRules(String json) {
        return json == null ? List.of() : List.copyOf(MAPPER.readValue(json, RULES));
    }
}
