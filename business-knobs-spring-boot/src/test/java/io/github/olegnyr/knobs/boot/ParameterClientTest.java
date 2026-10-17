package io.github.olegnyr.knobs.boot;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.Value;
import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterContext;
import io.github.olegnyr.knobs.impl.ParameterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Чтение параметра с контекстом обращения: {@code Client} OpenFeature и выборка по префиксу.
 */
@DisplayName("Параметр с контекстом обращения")
class ParameterClientTest {

    private static final ContextPath VIP = ContextPath.builder().put("segment", "VIP").build();

    @Test
    @DisplayName("Client без явного контекста видит путь, заполненный на входе")
    void getStringValue_shouldApplySegmentRule_whenContextIsOpenOnThread() {
        try (ConfigurableApplicationContext context = run();
                ParameterContext.Scope ignored = ParameterContext.open(VIP)) {
            assertThat(client(context).client().getStringValue("app.sample.wait-notice", "нет")).isEqualTo("vip");
        }
    }

    @Test
    @DisplayName("Значения нет — отличимо от значения: FLAG_NOT_FOUND и значение по умолчанию вызывающего")
    void getStringDetails_shouldReportNotFound_whenParameterHasNoValue() {
        try (ConfigurableApplicationContext context = run()) {
            FlagEvaluationDetails<String> details =
                    client(context).client().getStringDetails("app.sample.token-name", "по умолчанию");

            assertThat(details.getErrorCode()).isEqualTo(ErrorCode.FLAG_NOT_FOUND);
            assertThat(details.getValue()).isEqualTo("по умолчанию");
        }
    }

    @Test
    @DisplayName("Длительность — Duration, по ЦОД пода")
    void duration_shouldReturnDuration_whenParameterIsDuration() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(client(context).duration("app.sample.read-timeout")).contains(Duration.ofSeconds(15));
        }
    }

    @Test
    @DisplayName("В другом потоке контекст не перенесён — правило по сегменту не действует")
    void getStringValue_shouldIgnoreSegmentRule_whenReadOnAnotherThread() {
        try (ConfigurableApplicationContext context = run();
                ParameterContext.Scope ignored = ParameterContext.open(VIP)) {
            String value = CompletableFuture.supplyAsync(
                    () -> client(context).client().getStringValue("app.sample.wait-notice", "нет")).join();

            assertThat(value).isEqualTo("обычный");
        }
    }

    @Test
    @DisplayName("Параметр запрошен не тем типом — TYPE_MISMATCH")
    void getBooleanDetails_shouldReportTypeMismatch_whenParameterIsString() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(client(context).client().getBooleanDetails("app.sample.wait-notice", false).getErrorCode())
                    .isEqualTo(ErrorCode.TYPE_MISMATCH);
        }
    }

    @Test
    @DisplayName("Выборка по префиксу — все параметры префикса со значениями для контекста")
    void byPrefix_shouldReturnAllParametersOfPrefix_whenContextIsOpen() {
        try (ConfigurableApplicationContext context = run();
                ParameterContext.Scope ignored = ParameterContext.open(VIP)) {
            assertThat(client(context).byPrefix("app.capabilities.")).isEqualTo(Map.of(
                    "app.capabilities.stickers", true,
                    "app.capabilities.max-files", 5L,
                    "app.capabilities.greeting", "Здравствуйте"));
        }
    }

    @Test
    @DisplayName("Явный контекст вызова OpenFeature дополняет путь")
    void getStringValue_shouldApplySegmentRule_whenEvaluationContextCarriesSegment() {
        try (ConfigurableApplicationContext context = run()) {
            String value = client(context).client().getStringValue("app.sample.wait-notice", "нет",
                    new ImmutableContext(Map.of("segment", new Value("VIP"))));

            assertThat(value).isEqualTo("vip");
        }
    }

    @Test
    @DisplayName("Целое за пределами int — TYPE_MISMATCH, а не исключение")
    void getIntegerDetails_shouldReportTypeMismatch_whenValueExceedsInt() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(client(context).client().getIntegerDetails("app.sample.big-number", 0).getErrorCode())
                    .isEqualTo(ErrorCode.TYPE_MISMATCH);
        }
    }

    private static ParameterClient client(ConfigurableApplicationContext context) {
        return context.getBean(ParameterClient.class);
    }

    private static ConfigurableApplicationContext run() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("KNOBS_DC", "dc2", "KNOBS_DIMENSIONS", "dc,tenant,segment,channel",
                                "SAMPLE_HOST", "crm.dc2.example")));
        return new SpringApplicationBuilder(TestBeans.class)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties("spring.application.name=orders")
                .run();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(ParameterAutoConfiguration.class)
    static class TestBeans {
    }
}
