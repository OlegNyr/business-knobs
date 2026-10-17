package io.github.olegnyr.knobs.impl.evaluation;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterRule;
import io.github.olegnyr.knobs.ParameterType;
import io.github.olegnyr.knobs.impl.model.ParameterDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Вычисление значения параметра по самому точному подошедшему пути.
 *
 * <p>Порядок измерений в пути — ЦОД, арендатор, сегмент, канал ({@link RulePaths#DIMENSIONS}). Точнее тот путь, у
 * которого последняя названная часть стоит дальше; при равенстве — тот, где названо больше
 * частей; дальше — правило своего сервиса, затем путь, чьи названные части стоят дальше. Не
 * подошло ни одно правило — значение параметра.
 */
@DisplayName("Вычисление значения по самому точному пути")
class RuleEvaluatorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String KEY = "app.support.wait-notice";

    private static final ParameterDefinition PARAMETER = parameter(
            rule(Map.of("dc", "dc2"), "C"),
            rule(Map.of("segment", "VIP"), "B"),
            rule(Map.of("dc", "dc2", "segment", "VIP"), "A"));

    @Test
    @DisplayName("Полное совпадение, затем путь укорачивается, затем значение параметра")
    void evaluate_shouldPickMostSpecificPath_whenSeveralRulesMatch() {
        assertThat(valueFor(PARAMETER, Map.of("dc", "dc2", "segment", "VIP")))
                .as("/dc2/VIP — полное совпадение")
                .isEqualTo("A");
        assertThat(valueFor(PARAMETER, Map.of("dc", "dc1", "segment", "VIP")))
                .as("/*/VIP — путь той же длины со звёздочкой на месте ЦОД")
                .isEqualTo("B");
        assertThat(valueFor(PARAMETER, Map.of("dc", "dc2", "segment", "MASS")))
                .as("/dc2 — путь укорочен до ЦОД")
                .isEqualTo("C");
        assertThat(valueFor(PARAMETER, Map.of("dc", "dc1", "segment", "MASS")))
                .as("ни одно правило не подошло — значение параметра")
                .isEqualTo("D");
    }

    @Test
    @DisplayName("Дальняя последняя часть точнее, чем больше частей ближе к началу")
    void evaluate_shouldPreferFartherLastPart_overMoreNamedParts() {
        ParameterDefinition parameter = parameter(
                rule(Map.of("dc", "dc2", "tenant", "tenant-2"), "X"),
                rule(Map.of("segment", "VIP"), "Y"));

        assertThat(valueFor(parameter, Map.of("dc", "dc2", "tenant", "tenant-2", "segment", "VIP")))
                .isEqualTo("Y");
    }

    @Test
    @DisplayName("При равной точности выбор не зависит от порядка правил")
    void evaluate_shouldNotDependOnRuleOrder_whenPrecisionTies() {
        ParameterRule dc = rule(Map.of("dc", "dc2", "segment", "VIP"), "P");
        ParameterRule tenant = rule(Map.of("tenant", "tenant-2", "segment", "VIP"), "Q");
        Map<String, String> context = Map.of("dc", "dc2", "tenant", "tenant-2", "segment", "VIP");

        assertThat(List.of(valueFor(parameter(dc, tenant), context), valueFor(parameter(tenant, dc), context)))
                .as("названные части стоят дальше у пути с арендатором")
                .containsExactly("Q", "Q");
    }

    @Test
    @DisplayName("Правило своего сервиса точнее правила для всех сервисов на том же пути")
    void evaluate_shouldPreferOwnServiceRule_overAllServicesRule() {
        ParameterRule own = rule(Map.of("service", "orders", "dc", "dc2"), "S");
        ParameterRule all = rule(Map.of("dc", "dc2"), "T");
        Map<String, String> context = Map.of("service", "orders", "dc", "dc2");

        assertThat(List.of(valueFor(parameter(own, all), context), valueFor(parameter(all, own), context)))
                .containsExactly("S", "S");
    }

    @Test
    @DisplayName("Условие сервиса в точность пути не входит")
    void evaluate_shouldNotCountService_inPathPrecision() {
        ParameterDefinition parameter = parameter(
                rule(Map.of("service", "orders", "dc", "dc2"), "S"),
                rule(Map.of("segment", "VIP"), "B"));

        assertThat(valueFor(parameter, Map.of("service", "orders", "dc", "dc2", "segment", "VIP")))
                .isEqualTo("B");
    }

    @Test
    @DisplayName("Правило с частью пути, которой нет в контексте, не совпадает")
    void evaluate_shouldSkipRule_whenDimensionAbsentInContext() {
        ParameterDefinition parameter = parameter(rule(Map.of("tenant", "tenant-2"), "A"));

        assertThat(valueFor(parameter, Map.of("dc", "dc1")))
                .as("арендатора в контексте нет — правило по нему не совпадает")
                .isEqualTo("D");
    }

    @Test
    @DisplayName("Правило чужого сервиса не совпадает")
    void evaluate_shouldSkipRule_whenServiceDiffers() {
        ParameterDefinition parameter = parameter(rule(Map.of("service", "billing", "dc", "dc2"), "M"));

        assertThat(valueFor(parameter, Map.of("service", "orders", "dc", "dc2"))).isEqualTo("D");
    }

    @Test
    @DisplayName("Значение отдаётся копией: правка результата не меняет параметр")
    void evaluate_shouldReturnCopy_whenCallerMutatesResult() {
        ParameterDefinition parameter = ParameterDefinition.builder()
                .key("app.media.allowed-extensions")
                .name("Допустимые расширения")
                .type(ParameterType.STRING)
                .list(true)
                .value(json("[\"jpg\"]"))
                .build();
        ContextPath context = ContextPath.builder().build();

        ((ArrayNode) RuleEvaluator.evaluate(parameter, context).orElseThrow()).add("exe");

        assertThat(RuleEvaluator.evaluate(parameter, context)).contains(json("[\"jpg\"]"));
    }

    private static String valueFor(ParameterDefinition parameter, Map<String, String> context) {
        ContextPath.Builder path = ContextPath.builder();
        context.forEach(path::put);
        return RuleEvaluator.evaluate(parameter, path.build()).map(JsonNode::stringValue).orElseThrow();
    }

    /**
     * Параметр с правилами, готовыми к чтению, — так их кладёт снимок пода при каждой сборке.
     */
    private static ParameterDefinition parameter(ParameterRule... rules) {
        return ParameterDefinition.builder()
                .key(KEY)
                .name("Текст клиенту при ожидании оператора")
                .type(ParameterType.STRING)
                .value(json("\"D\""))
                .rules(RuleEvaluator.prepared(KEY, List.of(rules), RulePaths.DIMENSIONS))
                .build();
    }

    private static ParameterRule rule(Map<String, String> when, String value) {
        return ParameterRule.builder().when(when).value(json("\"" + value + "\"")).build();
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }
}
