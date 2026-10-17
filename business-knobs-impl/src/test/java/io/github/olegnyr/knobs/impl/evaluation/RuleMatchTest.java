package io.github.olegnyr.knobs.impl.evaluation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сравнение частей пути правила с путём контекста.
 *
 * <p>Правило и контекст записаны путём {@code [сервис]/ЦОД/арендатор/сегмент/канал} ({@link RulePaths}):
 * {@code *} — часть не названа, хвост из {@code *} можно не писать, сервис — перед первой косой
 * чертой. В исходном файле путь короче — {@code /ЦОД/сегмент}: {@code /&#42;/VIP} оттуда здесь
 * записан как {@code /&#42;/&#42;/VIP}.
 *
 * <p>Колонка «как сравнивали» — части в том порядке, в каком {@link RuleEvaluator#matches} пишет
 * их в лог TRACE: совпавшая — «dc совпала — dc2», несовпавшая — «измерение: в правиле …, в пути
 * …», на ней сравнение заканчивается.
 */
@DisplayName("Сравнение частей пути правила с путём контекста")
class RuleMatchTest {

    private static final String KEY = "app.sample.key";

    private static final String PASSES = "подходит";

    private static final String FAILS = "не подходит";

    private final Logger evaluator = (Logger) LoggerFactory.getLogger(RuleEvaluator.class);

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    private Level previous;

    @BeforeEach
    void setUp() {
        previous = evaluator.getLevel();
        evaluator.setLevel(Level.TRACE);
        log.start();
        evaluator.addAppender(log);
    }

    @AfterEach
    void tearDown() {
        evaluator.detachAppender(log);
        evaluator.setLevel(previous);
    }

    static Stream<Arguments> comparisons() {
        return Stream.of(
                // ---------- подходит ----------
                passes("часть совпала",
                        "/dc2", "/dc2",
                        "dc совпала — dc2"),
                passes("все четыре части совпали",
                        "/dc2/tenant-2/VIP/chat", "/dc2/tenant-2/VIP/chat",
                        "dc совпала — dc2; tenant совпала — tenant-2; segment совпала — VIP; "
                                + "channel совпала — chat"),
                passes("ЦОД и сегмент совпали, арендатор не назван",
                        "/dc2/*/VIP", "/dc2/*/VIP",
                        "dc совпала — dc2; segment совпала — VIP"),
                passes("звёздочка на месте ЦОД — правило для любого ЦОД",
                        "/*/*/VIP", "/dc1/*/VIP",
                        "segment совпала — VIP"),
                passes("лишняя часть контекста не сравнивается",
                        "/dc2", "/dc2/*/VIP",
                        "dc совпала — dc2"),
                passes("правило без частей — для любого контекста",
                        "/*", "/dc2/*/VIP",
                        "сравнивать нечего"),
                passes("условие своего сервиса",
                        "orders/*", "orders/dc2",
                        "service совпала — orders"),
                // ---------- не подходит ----------
                fails("ЦОД другой",
                        "/dc2/*/VIP", "/dc1/*/VIP",
                        "dc: в правиле dc2, в пути dc1"),
                fails("сегмент другой",
                        "/*/*/VIP", "/dc2/*/MASS",
                        "segment: в правиле VIP, в пути MASS"),
                fails("части нет в контексте — «не задано» не равно ничему",
                        "/*/tenant-2", "/dc2",
                        "tenant: в правиле tenant-2, в пути не задано"),
                fails("стоп на первом расхождении — канал уже не сравнивается",
                        "/dc2/*/MASS/gw", "/dc2/*/VIP/gw",
                        "dc совпала — dc2; segment: в правиле MASS, в пути VIP"),
                fails("чужой сервис",
                        "billing/*", "orders/*",
                        "service: в правиле billing, в пути orders"),
                fails("регистр учитывается",
                        "/*/*/vip", "/*/*/VIP",
                        "segment: в правиле vip, в пути VIP"));
    }

    @ParameterizedTest(name = "{0}: правило {1}, контекст {2} → {3}: {4}")
    @MethodSource("comparisons")
    @DisplayName("Подходит ли правило к контексту и как сравнивались части")
    void matches_shouldCompareEveryNamedPart(String title, String rule, String context, String verdict,
            String comparison) {
        assertComparison(title, RulePaths.parse(rule), context, verdict, comparison);
    }

    /**
     * Правило, которое путём не записать: оно приходит записью файла или топика, где путь — объект
     * «измерение → значение». Правило с незнакомым измерением и правило с частью без значения сюда
     * не доходят: их снимает снимок при сборке ({@link RuleRankTest}).
     */
    static Stream<Arguments> recordEdges() {
        return Stream.of(
                fails("буквальная «*» в записи — не звёздочка",
                        "{dc=*, segment=VIP}", "/dc2/*/VIP",
                        "dc: в правиле *, в пути dc2"));
    }

    @ParameterizedTest(name = "{0}: правило {1}, контекст {2} → {3}: {4}")
    @MethodSource("recordEdges")
    @DisplayName("Правило из записи, которое путём не записать")
    void matches_shouldCompareRecordEdges(String title, String rule, String context, String verdict,
            String comparison) {
        assertComparison(title, record(rule), context, verdict, comparison);
    }

    @Test
    @DisplayName("Строки лога целиком: путь /dc2/*/MASS в логе — правило {dc=dc2, segment=MASS}")
    void matches_shouldWriteFullTraceLines() {
        RuleEvaluator.matches(KEY, RulePaths.parse("/dc2/*/MASS"), RulePaths.context("/dc2/*/VIP"));

        assertThat(trace()).containsExactly(
                "Параметр app.sample.key: правило {dc=dc2, segment=MASS}: dc совпала — dc2",
                "Параметр app.sample.key: правило {dc=dc2, segment=MASS} не подходит — segment: в правиле MASS, "
                        + "в пути VIP");
    }

    private void assertComparison(String title, Map<String, String> rule, String context, String verdict,
            String comparison) {
        boolean matches = RuleEvaluator.matches(KEY, rule, RulePaths.context(context));

        assertThat(matches ? PASSES : FAILS).as(title).isEqualTo(verdict);
        assertThat(comparison(rule)).as(title).isEqualTo(comparison);
    }

    private static Arguments passes(String title, String rule, String context, String comparison) {
        return Arguments.of(title, rule, context, PASSES, comparison);
    }

    private static Arguments fails(String title, String rule, String context, String comparison) {
        return Arguments.of(title, rule, context, FAILS, comparison);
    }

    /**
     * Путь правила, как он лежит в записи файла или топика: {@code {dc=dc2, segment=VIP}}.
     */
    private static Map<String, String> record(String text) {
        Map<String, String> rule = new LinkedHashMap<>();
        for (String part : text.substring(1, text.length() - 1).split(",")) {
            String[] pair = part.strip().split("=", 2);
            rule.put(pair[0], pair[1]);
        }
        return rule;
    }

    /**
     * Как сравнивались части — из лога: по строке на часть, без префикса «Параметр …: правило …».
     */
    private String comparison(Map<String, String> rule) {
        String prefix = "Параметр " + KEY + ": правило " + rule;
        List<String> parts = trace().stream()
                .map(line -> line.substring(prefix.length()))
                .map(part -> part.startsWith(": ") ? part.substring(": ".length())
                        : part.substring(" не подходит — ".length()))
                .toList();
        return parts.isEmpty() ? "сравнивать нечего" : String.join("; ", parts);
    }

    private List<String> trace() {
        return log.list.stream()
                .filter(event -> event.getLevel() == Level.TRACE)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
