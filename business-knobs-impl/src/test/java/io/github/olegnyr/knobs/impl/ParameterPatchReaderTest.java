package io.github.olegnyr.knobs.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Текст аварийной заплатки сверяется с ограничениями каталога, а ловушки YAML 1.1 не превращают
 * значение молча в другое.
 */
@DisplayName("Заплатка — allowed, min, max и ловушки YAML")
class ParameterPatchReaderTest {

    private Knobs settings;

    @BeforeEach
    void setUp() {
        settings = Knobs.builder().service("orders").stand("patch").dimensions(TestDimensions.ALL).build();
    }

    @AfterEach
    void tearDown() {
        settings.close();
    }

    @Test
    @DisplayName("Значение вне allowed каталога отбивается с причиной")
    void apply_shouldReject_whenValueIsNotAllowed() {
        assertThat(errors("app.patch.mode: {value: medium}"))
                .containsExactly(Map.entry("app.patch.mode", "значение вне allowed каталога"));
    }

    @Test
    @DisplayName("«*» в when не значит «любое» — отказ, а не правило, которое никогда не подойдёт")
    void apply_shouldReject_whenRuleNamesStar() {
        assertThat(errors("app.patch.mode: {value: slow, rules: [{when: {dc: '*', segment: VIP}, value: slow}]}"))
                .extractingByKey("app.patch.mode").asString().contains("*");
    }

    @Test
    @DisplayName("Правило без when — отказ: оно перекрыло бы value правки на любом пути")
    void apply_shouldReject_whenRuleHasNoWhen() {
        assertThat(errors("app.patch.mode: {value: slow, rules: [{value: slow}]}"))
                .extractingByKey("app.patch.mode").asString().contains("when");
    }

    @Test
    @DisplayName("Значение из allowed принимается")
    void apply_shouldAccept_whenValueIsAllowed() {
        assertThat(errors("app.patch.mode: {value: slow}")).isEmpty();
        assertThat(settings.parameters().value("app.patch.mode")).contains("slow");
    }

    @Test
    @DisplayName("Число меньше min и больше max отбивается; граница принимается")
    void apply_shouldRejectOutOfRange_whenIntegerCrossesBounds() {
        assertThat(List.of(errors("app.patch.limit: {value: 0}"), errors("app.patch.limit: {value: 101}"),
                errors("app.patch.limit: {value: 100}"))).containsExactly(
                Map.of("app.patch.limit", "значение меньше min каталога"),
                Map.of("app.patch.limit", "значение больше max каталога"),
                Map.of());
    }

    @Test
    @DisplayName("Длительность сравнивается как длительность: 2m больше PT1M")
    void apply_shouldCompareDurations_whenBoundIsDuration() {
        assertThat(errors("app.patch.timeout: {value: 2m}"))
                .containsExactly(Map.entry("app.patch.timeout", "значение больше max каталога"));
    }

    @Test
    @DisplayName("Ограничение действует и на значение правила, и на элемент списка")
    void apply_shouldCheckRulesAndListElements_whenConstrained() {
        assertThat(List.of(errors("app.patch.limit: {rules: [{when: {dc: dc2}, value: 500}]}"),
                errors("app.patch.sizes: {value: [1, 11]}"))).containsExactly(
                Map.of("app.patch.limit", "значение больше max каталога"),
                Map.of("app.patch.sizes", "значение больше max каталога"));
    }

    @Test
    @DisplayName("YAML on/off/yes/no у признака — причина, а не молчаливое true/false")
    void apply_shouldReject_whenBooleanIsYaml11Word() {
        assertThat(errors("app.patch.flag: {value: off}")).containsExactly(Map.entry("app.patch.flag",
                "on/off/yes/no — не признак: пишите true или false"));
    }

    @Test
    @DisplayName("YAML on/off у строки — та самая строка, а не признак")
    void apply_shouldKeepText_whenStringLooksLikeYaml11Boolean() {
        assertThat(errors("app.patch.label: {value: off}")).isEmpty();
        assertThat(settings.parameters().value("app.patch.label")).contains("off");
    }

    @Test
    @DisplayName("Число с ведущим нулём — причина, а не восьмеричное 8")
    void apply_shouldReject_whenIntegerHasLeadingZero() {
        assertThat(errors("app.patch.limit: {value: 010}")).containsExactly(Map.entry("app.patch.limit",
                "число с ведущим нулём либо 0x/0o/0b: пишите десятичное без ведущего нуля"));
    }

    @Test
    @DisplayName("Строка с ведущим нулём остаётся строкой")
    void apply_shouldKeepText_whenStringHasLeadingZero() {
        assertThat(errors("app.patch.label: {value: 010}")).isEmpty();
        assertThat(settings.parameters().value("app.patch.label")).contains("010");
    }

    @Test
    @DisplayName("Дата без кавычек у строки — текст даты, а не метка времени")
    void apply_shouldKeepText_whenStringLooksLikeDate() {
        assertThat(errors("app.patch.label: {value: 2026-11-01}")).isEmpty();
        assertThat(settings.parameters().value("app.patch.label")).contains("2026-11-01");
    }

    @Test
    @DisplayName("Дата без кавычек у даты принимается; со временем — отбивается, а не обрезается")
    void apply_shouldRejectTimestamp_whenDateHasTime() {
        assertThat(List.of(errors("app.patch.until: {value: 2026-12-01}"),
                errors("app.patch.until: {value: 2026-12-01 10:00:00}"))).containsExactly(
                Map.of(),
                Map.of("app.patch.until", "значение не читается как DATE"));
        assertThat(settings.parameters().date("app.patch.until")).isEqualTo(Optional.of(LocalDate.of(2026, 12, 1)));
    }

    private Map<String, String> errors(String text) {
        return settings.patches().apply("дежурный", "svc-ops", "инцидент", text).errors();
    }
}
