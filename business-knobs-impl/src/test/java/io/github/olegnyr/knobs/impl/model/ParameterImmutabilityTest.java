package io.github.olegnyr.knobs.impl.model;

import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterRule;
import io.github.olegnyr.knobs.ParameterType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Значения параметра наружу не меняют параметр: снимок в памяти общий для всех потоков пода.
 */
@DisplayName("Значения параметра наружу — копии")
class ParameterImmutabilityTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("Правка значения параметра, полученного геттером, параметр не меняет")
    void getValue_shouldReturnCopy_whenParameterValueIsMutated() {
        ParameterDefinition parameter = ParameterDefinition.builder().key("app.x").name("X")
                .type(ParameterType.STRING).list(true).value(json("[\"jpg\"]"))
                .allowedValue(json("\"jpg\"")).build();

        ((ArrayNode) parameter.getValue()).add("exe");

        assertThat(parameter.getValue()).isEqualTo(json("[\"jpg\"]"));
    }

    @Test
    @DisplayName("Правка значения правила, полученного геттером, правило не меняет")
    void getValue_shouldReturnCopy_whenRuleValueIsMutated() {
        ParameterRule rule = ParameterRule.builder().whenPart("dc", "dc2").value(json("[\"jpg\"]")).build();

        ((ArrayNode) rule.getValue()).add("exe");

        assertThat(rule.getValue()).isEqualTo(json("[\"jpg\"]"));
    }

    @Test
    @DisplayName("Правка значения записи, полученного геттером, запись не меняет")
    void getValue_shouldReturnCopy_whenRecordValueIsMutated() {
        ParameterRecord record = ParameterRecord.builder().key("app.x").type(ParameterType.STRING).list(true)
                .value(json("[\"jpg\"]")).version(1L).build();

        ((ArrayNode) record.getValue()).add("exe");

        assertThat(record.getValue()).isEqualTo(json("[\"jpg\"]"));
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }
}
