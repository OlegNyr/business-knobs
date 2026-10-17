package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.ParameterRecordApplier;
import io.github.olegnyr.knobs.Parameters;
import io.github.olegnyr.knobs.ParametersChanged;
import io.github.olegnyr.knobs.impl.ParameterObserver;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Метрики пода по параметрам. */
@DisplayName("Метрики параметров")
class ParameterMetricsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String KEY = "app.sample.read-timeout";

    @Test
    @DisplayName("Исходы записей считаются с тегом исхода")
    void apply_shouldCountOutcome_whenRecordApplied() {
        try (ConfigurableApplicationContext context = run()) {
            applier(context).apply(KEY, body("\"PT30S\"", 8L));
            applier(context).apply(KEY, body("\"abc\"", 9L));

            assertThat(count(context, "applied")).isEqualTo(1.0);
            assertThat(count(context, "rejected_type")).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("Число заплаток и признак «работаю на файле»")
    void gauges_shouldReportPatchesAndFileOnly_whenPodRuns() {
        try (ConfigurableApplicationContext context = run()) {
            context.getBean(ParameterEndpoint.class).patch("дежурный", "проба", KEY + ": {value: 5s}",
                    SecurityContext.NONE);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertThat(registry.get("knobs.parameters.patches").gauge().value()).isEqualTo(1.0);
            assertThat(registry.get("knobs.parameters.file.only").gauge().value())
                    .as("реплики в контексте нет — под на файле").isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("Сервис и ЦОД пода — метрикой")
    void podGauge_shouldCarryServiceAndDc_whenPodRuns() {
        try (ConfigurableApplicationContext context = run()) {
            Meter pod = context.getBean(MeterRegistry.class).get("knobs.parameters.pod").meter();

            assertThat(pod.getId().getTags()).contains(Tag.of("service", "orders"), Tag.of("dc", "dc2"));
        }
    }

    @Test
    @DisplayName("Ключ параметра ни в одном теге")
    void meters_shouldNotTagParameterKey_whenRecordsApplied() {
        try (ConfigurableApplicationContext context = run()) {
            applier(context).apply(KEY, body("\"PT30S\"", 8L));

            assertThat(context.getBean(MeterRegistry.class).getMeters())
                    .filteredOn(meter -> meter.getId().getName().startsWith("knobs.parameters"))
                    .allSatisfy(meter -> assertThat(meter.getId().getTags())
                            .noneMatch(tag -> tag.getValue().contains("app.sample")));
        }
    }

    @Test
    @DisplayName("Отказ потребителя события — в метрике")
    void publish_shouldCountConsumerFailure_whenListenerThrows() {
        try (ConfigurableApplicationContext context = run()) {
            applier(context).apply(KEY, body("\"PT30S\"", 8L));

            assertThat(context.getBean(MeterRegistry.class).get("knobs.parameters.consumer.failures").counter().count())
                    .isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("Файла стенда нет — снимок пустой: под и ЦОД метрикой, запись — чужой ключ, значения нет")
    void context_shouldRunOnEmptySnapshot_whenStandFileMissing() {
        try (ConfigurableApplicationContext context = run("dev")) {
            Meter pod = context.getBean(MeterRegistry.class).get("knobs.parameters.pod").meter();
            ParameterRecordApplier.Outcome outcome = applier(context).apply(KEY, body("\"PT30S\"", 8));

            assertThat(pod.getId().getTags()).contains(Tag.of("service", "orders"), Tag.of("dc", "dc2"));
            assertThat(outcome).isEqualTo(ParameterRecordApplier.Outcome.FOREIGN);
            assertThat(context.getBean(Parameters.class).value(KEY)).isEmpty();
        }
    }

    private static double count(ConfigurableApplicationContext context, String outcome) {
        return context.getBean(MeterRegistry.class).get("knobs.parameters.records").tag("outcome", outcome)
                .counter().count();
    }

    private static ParameterRecordApplier applier(ConfigurableApplicationContext context) {
        return context.getBean(ParameterRecordApplier.class);
    }

    private static JsonNode body(String value, long version) {
        return MAPPER.readTree("""
                {"key": "%s", "type": "DURATION", "value": %s, "rules": [], "version": %d}
                """.formatted(KEY, value, version));
    }

    @Test
    @DisplayName("Свой ParameterObserver сервиса рядом с Micrometer — контекст поднимается, исходы идут в него")
    void observer_shouldBeServiceOwn_whenServiceDeclaresOne() {
        try (ConfigurableApplicationContext context = run("prod", TestBeans.class, OwnObserver.class)) {
            applier(context).apply(KEY, body("\"PT30S\"", 8L));

            assertThat(context.getBean(RecordingObserver.class).outcomes)
                    .containsExactly(ParameterRecordApplier.Outcome.APPLIED);
        }
    }

    private static ConfigurableApplicationContext run() {
        return run("prod");
    }

    private static ConfigurableApplicationContext run(String profile) {
        return run(profile, TestBeans.class);
    }

    private static ConfigurableApplicationContext run(String profile, Class<?>... sources) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("KNOBS_DC", "dc2", "KNOBS_DIMENSIONS", "dc,tenant,segment,channel",
                                "SAMPLE_HOST", "crm.dc2.example")));
        return new SpringApplicationBuilder(sources)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .profiles(profile)
                .properties("spring.application.name=orders")
                .run();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(ParameterAutoConfiguration.class)
    static class TestBeans {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        FailingListener failingListener() {
            return new FailingListener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnObserver {

        @Bean
        RecordingObserver recordingObserver() {
            return new RecordingObserver();
        }
    }

    /** Наблюдатель сервиса: запоминает исходы записей. */
    static final class RecordingObserver implements ParameterObserver {

        final List<ParameterRecordApplier.Outcome> outcomes = new CopyOnWriteArrayList<>();

        @Override
        public void recordOutcome(ParameterRecordApplier.Outcome outcome) {
            outcomes.add(outcome);
        }
    }

    /** Потребитель события, который всегда отказывает. */
    static final class FailingListener {

        @EventListener
        void onChange(ParametersChanged event) {
            throw new IllegalStateException("потребитель отказал");
        }
    }
}
