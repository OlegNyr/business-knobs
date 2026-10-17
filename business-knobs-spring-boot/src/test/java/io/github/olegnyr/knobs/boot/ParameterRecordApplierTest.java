package io.github.olegnyr.knobs.boot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterRecordApplier;
import io.github.olegnyr.knobs.ParameterTopicRecord;
import io.github.olegnyr.knobs.ParameterType;
import io.github.olegnyr.knobs.ParametersChanged;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Применение записи топика {@code knobs.parameters.v1} подом. */
@DisplayName("Под применяет запись топика")
class ParameterRecordApplierTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String KEY = "app.sample.read-timeout";

    @Test
    @DisplayName("Запись новее памяти — строка реплики обновлена, в памяти новое значение")
    void apply_shouldWriteReplicaAndMemory_whenVersionIsNewer() {
        try (ConfigurableApplicationContext context = run()) {
            InMemoryReplica replica = context.getBean(InMemoryReplica.class);

            applier(context).apply(KEY, body(KEY, "\"PT30S\"", 8L));

            assertThat(replica.find(KEY)).get().extracting(ParameterRecord::getVersion).isEqualTo(8L);
            assertThat(context.getEnvironment().getProperty(KEY)).isEqualTo("PT30S");
        }
    }

    @Test
    @DisplayName("Применение публикует ParametersChanged с ключом записи")
    void apply_shouldPublishChangedKey_whenValueChanged() {
        try (ConfigurableApplicationContext context = run()) {
            applier(context).apply(KEY, body(KEY, "\"PT30S\"", 8L));

            assertThat(context.getBean(Listeners.class).received).containsExactly("first", "second");
        }
    }

    @Test
    @DisplayName("Версия не больше версии в памяти — запись проигнорирована, к базе не ходим")
    void apply_shouldIgnore_whenVersionIsNotNewer() {
        try (ConfigurableApplicationContext context = run()) {
            ParameterRecordApplier applier = applier(context);
            applier.apply(KEY, body(KEY, "\"PT30S\"", 8L));
            InMemoryReplica replica = context.getBean(InMemoryReplica.class);
            int callsBefore = replica.calls.get();

            ParameterRecordApplier.Outcome outcome = applier.apply(KEY, body(KEY, "\"PT40S\"", 6L));

            assertThat(outcome).isEqualTo(ParameterRecordApplier.Outcome.IGNORED);
            assertThat(replica.calls.get()).isEqualTo(callsBefore);
        }
    }

    @Test
    @DisplayName("Реплика уже новее записи — в память идёт строка реплики")
    void apply_shouldTakeReplicaRow_whenAnotherPodWroteNewerVersion() {
        try (ConfigurableApplicationContext context = run()) {
            context.getBean(InMemoryReplica.class).put(record(KEY, "\"PT50S\"", 9L));

            applier(context).apply(KEY, body(KEY, "\"PT30S\"", 8L));

            assertThat(context.getEnvironment().getProperty(KEY)).isEqualTo("PT50S");
        }
    }

    @Test
    @DisplayName("Ключа нет в каталоге — запись пропущена, в реплике строки нет")
    void apply_shouldSkip_whenKeyIsNotInCatalog() {
        try (ConfigurableApplicationContext context = run()) {
            String foreign = "billing.stickers.enabled";

            ParameterRecordApplier.Outcome outcome = applier(context).apply(foreign, body(foreign, "true", 8L));

            assertThat(outcome).isEqualTo(ParameterRecordApplier.Outcome.FOREIGN);
            assertThat(context.getBean(InMemoryReplica.class).find(foreign)).isEmpty();
        }
    }

    @Test
    @DisplayName("База недоступна — запись применена в память напрямую")
    void apply_shouldApplyToMemory_whenReplicaFails() {
        try (ConfigurableApplicationContext context = run()) {
            context.getBean(InMemoryReplica.class).failing = true;

            applier(context).apply(KEY, body(KEY, "\"PT30S\"", 8L));

            assertThat(context.getEnvironment().getProperty(KEY)).isEqualTo("PT30S");
        }
    }

    @Test
    @DisplayName("Первый потребитель события отказал — второй событие получил")
    void apply_shouldDeliverToOtherListeners_whenOneListenerFails() {
        try (ConfigurableApplicationContext context = run()) {
            Listeners listeners = context.getBean(Listeners.class);
            listeners.failFirst = true;

            applier(context).apply(KEY, body(KEY, "\"PT30S\"", 8L));

            assertThat(listeners.received).containsExactly("second");
        }
    }

    @Test
    @DisplayName("Пачка 3, 7, 5 при реплике 2 — применена только 7, событие одно")
    void applyAll_shouldApplyOnlyNewest_whenBatchHasSeveralVersions() {
        try (ConfigurableApplicationContext context = run()) {
            context.getBean(InMemoryReplica.class).put(record(KEY, "\"PT20S\"", 2L));

            applier(context).applyAll(List.of(
                    new ParameterTopicRecord(KEY, body(KEY, "\"PT30S\"", 3L)),
                    new ParameterTopicRecord(KEY, body(KEY, "\"PT70S\"", 7L)),
                    new ParameterTopicRecord(KEY, body(KEY, "\"PT50S\"", 5L))));

            assertThat(context.getEnvironment().getProperty(KEY)).isEqualTo("PT70S");
            assertThat(context.getBean(Listeners.class).received).containsExactly("first", "second");
        }
    }

    @Test
    @DisplayName("База вернулась — переопубликация той же версии дописывает реплику")
    void apply_shouldRepairReplica_whenSameVersionArrivesAfterFailure() {
        try (ConfigurableApplicationContext context = run()) {
            InMemoryReplica replica = context.getBean(InMemoryReplica.class);
            replica.failing = true;
            applier(context).apply(KEY, body(KEY, "\"PT30S\"", 8L));
            replica.failing = false;

            ParameterRecordApplier.Outcome outcome = applier(context).apply(KEY, body(KEY, "\"PT30S\"", 8L));

            assertThat(outcome).isEqualTo(ParameterRecordApplier.Outcome.REPAIRED);
            assertThat(replica.find(KEY)).get().extracting(ParameterRecord::getVersion).isEqualTo(8L);
        }
    }

    @Test
    @DisplayName("Запись deleted — действует значение файла, исход deleted")
    void apply_shouldFallBackToFile_whenRecordIsDeleted() {
        assertRejected(deleted(body(KEY, "\"PT30S\"", 8L)), ParameterRecordApplier.Outcome.DELETED);
    }

    @Test
    @DisplayName("Значение не читается по типу — действует значение файла, исход rejected_type")
    void apply_shouldFallBackToFile_whenValueDoesNotMatchType() {
        assertRejected(body(KEY, "\"abc\"", 8L), ParameterRecordApplier.Outcome.REJECTED_TYPE);
    }

    @Test
    @DisplayName("Значение с ${ — действует значение файла, исход rejected_placeholder")
    void apply_shouldFallBackToFile_whenValueHasPlaceholder() {
        assertRejected(body(KEY, "\"${APP_SECRET}\"", 8L), ParameterRecordApplier.Outcome.REJECTED_PLACEHOLDER);
    }

    @Test
    @DisplayName("Тело не разбирается — исход rejected_unreadable, подписчик не падает")
    void apply_shouldReportUnreadable_whenBodyIsBroken() {
        try (ConfigurableApplicationContext context = run()) {
            ParameterRecordApplier.Outcome outcome = applier(context).apply(KEY,
                    MAPPER.readTree("{\"version\": \"восемь\"}"));

            assertThat(outcome).isEqualTo(ParameterRecordApplier.Outcome.REJECTED_UNREADABLE);
        }
    }

    private static void assertRejected(JsonNode body, ParameterRecordApplier.Outcome expected) {
        try (ConfigurableApplicationContext context = run()) {
            applier(context).apply(KEY, body(KEY, "\"PT30S\"", 7L));

            ParameterRecordApplier.Outcome outcome = applier(context).apply(KEY, body);

            assertThat(outcome).isEqualTo(expected);
            assertThat(context.getEnvironment().getProperty(KEY)).isEqualTo("PT10S");
        }
    }

    private static JsonNode deleted(JsonNode body) {
        return ((ObjectNode) body).put("deleted", true);
    }

    @Test
    @DisplayName("Смена только правила по сегменту — событие есть, хотя свойство пода не сменилось")
    void apply_shouldPublishChange_whenOnlyRequestRuleChanged() {
        try (ConfigurableApplicationContext context = run()) {
            applier(context).apply(KEY, MAPPER.readTree("""
                    {"key": "%s", "type": "DURATION", "value": "PT10S",
                     "rules": [{"when": {"segment": "VIP"}, "value": "PT99S"}], "version": 8}
                    """.formatted(KEY)));

            assertThat(context.getBean(Listeners.class).received).containsExactly("first", "second");
        }
    }

    @Test
    @DisplayName("Отказ потребителя — в логе класс причины и ключи, текста исключения нет")
    void apply_shouldLogFailureClass_whenListenerFails() {
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ParametersChangedPublisher.class);
        logger.addAppender(log);
        try (ConfigurableApplicationContext context = run()) {
            context.getBean(Listeners.class).failFirst = true;

            applier(context).apply(KEY, body(KEY, "\"PT30S\"", 8L));

            assertThat(log.list).extracting(ILoggingEvent::getFormattedMessage).singleElement()
                    .satisfies(line -> assertThat(line).contains("IllegalStateException", KEY)
                            .doesNotContain("потребитель отказал"));
        } finally {
            logger.detachAppender(log);
        }
    }

    private static ParameterRecordApplier applier(ConfigurableApplicationContext context) {
        return context.getBean(ParameterRecordApplier.class);
    }

    private static JsonNode body(String key, String value, long version) {
        return MAPPER.readTree("""
                {"key": "%s", "name": "Таймаут", "type": "DURATION", "value": %s, "rules": [], "version": %d}
                """.formatted(key, value, version));
    }

    private static ParameterRecord record(String key, String value, long version) {
        return ParameterRecord.builder().key(key).type(ParameterType.DURATION).value(MAPPER.readTree(value))
                .version(version).build();
    }

    private static ConfigurableApplicationContext run() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("KNOBS_DC", "dc1", "KNOBS_DIMENSIONS", "dc,tenant,segment,channel",
                                "SAMPLE_HOST", "crm.dc1.example")));
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

        @Bean
        InMemoryReplica inMemoryReplica() {
            return new InMemoryReplica();
        }

        @Bean
        Listeners listeners() {
            return new Listeners();
        }
    }

    /** Два потребителя события; первый умеет отказывать. */
    static final class Listeners {

        private final List<String> received = new ArrayList<>();

        private boolean failFirst;

        @EventListener
        @Order(1)
        void first(ParametersChanged event) {
            if (failFirst) {
                throw new IllegalStateException("потребитель отказал");
            }
            received.add("first");
        }

        @EventListener
        @Order(2)
        void second(ParametersChanged event) {
            received.add("second");
        }
    }
}
