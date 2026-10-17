package io.github.olegnyr.knobs.example;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Пример без Spring проходит все шаги и печатает то, что видит код сервиса.
 */
@DisplayName("Пример без Spring: магазин shop читает параметры на ходу")
class ExampleApplicationTest {

    private static List<String> lines;

    @BeforeAll
    static void runExample() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            ExampleApplication.run(out, Map.of(
                    "KNOBS_STAND", "prod",
                    "KNOBS_DC", "dc2",
                    "PAYMENT_URL", "https://pay.example.com")::get);
        }
        lines = buffer.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    @DisplayName("Путь пода: значения файла стенда prod и правило ЦОД dc2, подстановка из окружения")
    void run_shouldPrintPodValues() {
        assertThat(lines).containsSubsequence(
                "== Путь пода {service=shop, dc=dc2}",
                "max-items = 20",
                "payment-timeout = PT15S",
                "greeting = Добро пожаловать!",
                "express-delivery = false",
                "payment-url = https://pay.example.com");
    }

    @Test
    @DisplayName("Путь обращения арендатора acme и сегмента VIP: действуют правила по ним")
    void run_shouldPrintRequestValues() {
        assertThat(lines).containsSubsequence(
                "== Обращение {segment=VIP, tenant=acme}",
                "max-items = 100",
                "greeting = Добро пожаловать в магазин ACME!",
                "express-delivery = true");
    }

    @Test
    @DisplayName("Запись топика меняет значение без рестарта, слушатель узнаёт ключ")
    void run_shouldApplyTopicRecord() {
        assertThat(lines).containsSubsequence(
                "== Запись топика: app.checkout.max-items = 50",
                "изменились ключи: [app.checkout.max-items]",
                "исход: [APPLIED]",
                "max-items = 50");
    }

    @Test
    @DisplayName("Аварийная правка на поде меняет таймаут")
    void run_shouldApplyEmergencyPatch() {
        assertThat(lines).containsSubsequence(
                "== Аварийная правка: app.checkout.payment-timeout = 30s",
                "поставлены: [app.checkout.payment-timeout]",
                "payment-timeout = PT30S");
    }

    @Test
    @DisplayName("Тот же параметр читается стандартным клиентом OpenFeature")
    void run_shouldReadThroughOpenFeature() {
        assertThat(lines).containsSubsequence(
                "== OpenFeature",
                "max-items = 50");
    }

    @Test
    @DisplayName("Файла стенда в classpath нет — пример подсказывает, как его получить, и не падает")
    void run_shouldExplain_whenStandFileIsMissing() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            ExampleApplication.run(out, Map.of("KNOBS_STAND", "nowhere")::get);
        }

        assertThat(buffer.toString(StandardCharsets.UTF_8))
                .contains("knobs/knobs-nowhere.json")
                .contains("./mvnw -f business-knobs-example/pom.xml process-resources")
                .doesNotContain("== Путь пода");
    }
}
