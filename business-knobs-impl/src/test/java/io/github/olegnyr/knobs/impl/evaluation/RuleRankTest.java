package io.github.olegnyr.knobs.impl.evaluation;

import io.github.olegnyr.knobs.ParameterRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Подготовка правил при сборке снимка: правило, которое не подошло бы ни к одному
 * пути, отбрасывается, остальные выстраиваются по точности — самое точное первым, — и при чтении
 * действует первое подошедшее.
 *
 * <p>Пути записаны как в {@link RulePaths}: {@code [сервис]/ЦОД/арендатор/сегмент/канал},
 * {@code *} — часть не названа. Критерии точности по очереди, следующий — только при равенстве:
 * последняя названная часть дальше → названо больше частей → правило своего сервиса → названные
 * части стоят дальше.
 */
@DisplayName("Подготовка правил при сборке снимка: какие не участвуют и какое правило лучшее")
class RuleRankTest {

    private static final String KEY = "app.sample.key";

    private static final JsonNode VALUE = JsonNodeFactory.instance.stringNode("x");

    @Test
    @DisplayName("Правило, которое не подошло бы ни к одному пути, к чтению не готовится: без значения, "
            + "незнакомое измерение, часть без значения")
    void prepared_shouldDropRulesThatNeverMatch() {
        List<ParameterRule> rules = new ArrayList<>(rules("/dc2, /*/*/VIP"));
        rules.add(ParameterRule.builder().whenPart("dc", "dc1").build());
        rules.add(ParameterRule.builder().whenPart("region", "north").value(VALUE).build());
        rules.add(ParameterRule.builder().whenPart("dc", null).value(VALUE).build());

        assertThat(paths(RuleEvaluator.prepared(KEY, rules, RulePaths.DIMENSIONS))).isEqualTo("/*/*/VIP, /dc2");
    }

    static Stream<Arguments> rankings() {
        return Stream.of(
                ranking("1. последняя часть дальше: сегмент точнее ЦОД",
                        "/dc2, /*/*/VIP",
                        "/*/*/VIP, /dc2"),
                ranking("1. дальняя последняя часть сильнее числа частей",
                        "/dc2/t2, /*/*/VIP",
                        "/*/*/VIP, /dc2/t2"),
                ranking("1. канал — самое дальнее место пути",
                        "/dc2/t2/VIP, /*/*/*/gw",
                        "/*/*/*/gw, /dc2/t2/VIP"),
                ranking("2. последняя часть та же — точнее, у кого частей больше",
                        "/*/*/VIP, /dc2/*/VIP",
                        "/dc2/*/VIP, /*/*/VIP"),
                ranking("3. правило своего сервиса точнее правила для всех на том же пути",
                        "/dc2, orders/dc2",
                        "orders/dc2, /dc2"),
                ranking("3. сервис в точность пути не входит: сегмент всё равно сильнее",
                        "orders/dc2, /*/*/VIP",
                        "/*/*/VIP, orders/dc2"),
                ranking("4. всё прочее равно — точнее, у кого части дальше: арендатор дальше ЦОД",
                        "/dc2/*/VIP, /*/t2/VIP",
                        "/*/t2/VIP, /dc2/*/VIP"),
                ranking("правило без частей — последним",
                        "/*, /dc2",
                        "/dc2, /*"),
                ranking("типичный набор из файла: /dc2/VIP, /*/VIP, /dc2",
                        "/dc2, /*/*/VIP, /dc2/*/VIP",
                        "/dc2/*/VIP, /*/*/VIP, /dc2"));
    }

    @ParameterizedTest(name = "{0}: {1} → {2}")
    @MethodSource("rankings")
    @DisplayName("Правила по точности — самое точное первым")
    void mostSpecificFirst_shouldPutMostSpecificRuleFirst(String title, String rules, String ranked) {
        List<ParameterRule> sorted = RuleEvaluator.mostSpecificFirst(rules(rules), RulePaths.DIMENSIONS);

        assertThat(paths(sorted)).as(title).isEqualTo(ranked);
    }

    @Test
    @DisplayName("Порядок на входе не важен: все 720 перестановок дают один порядок")
    void mostSpecificFirst_shouldNotDependOnInputOrder() {
        List<String> rules = List.of("/dc2", "/*", "orders/dc2", "/*/*/VIP", "/dc2/*/VIP", "/*/t2/VIP");

        Set<String> rankings = permutations(rules).stream()
                .map(order -> paths(RuleEvaluator.mostSpecificFirst(rules(String.join(", ", order)),
                        RulePaths.DIMENSIONS)))
                .collect(Collectors.toSet());

        assertThat(rankings).containsExactly("/*/t2/VIP, /dc2/*/VIP, /*/*/VIP, orders/dc2, /dc2, /*");
    }

    @Test
    @DisplayName("Порядок измерений задаёт сервис: при обратном порядке ЦОД точнее сегмента")
    void mostSpecificFirst_shouldFollowConfiguredOrder() {
        ParameterRule byDc = ParameterRule.builder().whenPart("dc", "dc2").value(VALUE).build();
        ParameterRule bySegment = ParameterRule.builder().whenPart("segment", "VIP").value(VALUE).build();

        List<ParameterRule> ranked = RuleEvaluator.mostSpecificFirst(List.of(bySegment, byDc),
                ContextDimensions.of("segment", "dc"));

        assertThat(ranked).containsExactly(byDc, bySegment);
    }

    private static Arguments ranking(String title, String rules, String ranked) {
        return Arguments.of(title, rules, ranked);
    }

    private static List<ParameterRule> rules(String paths) {
        return Stream.of(paths.split(", "))
                .map(path -> ParameterRule.builder()
                        .when(RulePaths.parse(path))
                        .value(JsonNodeFactory.instance.stringNode(path))
                        .build())
                .toList();
    }

    private static String paths(List<ParameterRule> rules) {
        return rules.stream()
                .map(rule -> RulePaths.format(rule.getWhen()))
                .collect(Collectors.joining(", "));
    }

    private static List<List<String>> permutations(List<String> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<String>> result = new ArrayList<>();
        for (String first : items) {
            List<String> rest = new ArrayList<>(items);
            rest.remove(first);
            for (List<String> tail : permutations(rest)) {
                List<String> order = new ArrayList<>();
                order.add(first);
                order.addAll(tail);
                result.add(order);
            }
        }
        return result;
    }
}
