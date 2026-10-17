package io.github.olegnyr.knobs;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.Map;

/**
 * Правило параметра: путь и значение. Идентичность правила — его путь, порядка у
 * правил нет: исполнение библиотеки выбирает значение по самому точному подошедшему пути.
 */
@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = ParameterRule.ParameterRuleBuilder.class)
public class ParameterRule {

    /**
     * Путь правила: измерение → значение. Измерения нет в пути — подходит любое значение
     * («звёздочка» исходного файла). Условие по {@code service} есть только у общего параметра.
     */
    @Singular("whenPart")
    Map<String, String> when;

    /**
     * Значение, которое даёт правило; тип — как у параметра. Правило без значения не участвует в выборе.
     */
    JsonNode value;

    /**
     * Значение правила — копия: снимок общий для всех потоков пода, и правка снаружи его бы испортила.
     *
     * @return копия значения либо {@code null}
     */
    public JsonNode getValue() {
        return value == null ? null : value.deepCopy();
    }

    /**
     * Билдер, который Jackson вызывает без префикса {@code with}.
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static class ParameterRuleBuilder {
    }
}
