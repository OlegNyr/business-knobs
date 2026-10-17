package io.github.olegnyr.knobs.boot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterReplica;
import io.github.olegnyr.knobs.ParameterType;
import io.github.olegnyr.knobs.ParametersChanged;
import io.github.olegnyr.knobs.impl.ParameterReplicaLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Реплика из базы читается синхронно до старта слушателей и перекрывает файл. */
@DisplayName("Реплика параметров на подъёме пода")
class ParameterReplicaLoaderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("Значение из реплики видно до старта слушателей")
    void afterSingletonsInstantiated_shouldApplyReplica_beforeLifecycleStarts() {
        try (ConfigurableApplicationContext context = run(ReplicaWithTimeout.class)) {
            assertThat(context.getBean(StartProbe.class).seenAtStart).isEqualTo("PT20S");
        }
    }

    @Test
    @DisplayName("Событие ParametersChanged несёт ключи, где реплика отличается от файла")
    void afterSingletonsInstantiated_shouldPublishChangedKeys_whenReplicaDiffersFromFile() {
        try (ConfigurableApplicationContext context = run(ReplicaWithTimeout.class)) {
            assertThat(context.getBean(ChangeProbe.class).events).singleElement()
                    .extracting(ParametersChanged::getKeys)
                    .isEqualTo(Set.of("app.sample.read-timeout"));
        }
    }

    @Test
    @DisplayName("База недоступна — под поднят на файле, слой отмечен «работаю на файле»")
    void afterSingletonsInstantiated_shouldStayOnFile_whenReplicaFails() {
        try (ConfigurableApplicationContext context = run(BrokenReplica.class)) {
            ParameterPropertySource layer = (ParameterPropertySource) context.getEnvironment().getPropertySources()
                    .get(ParameterPropertySource.NAME);

            assertThat(layer.isReplicaLoaded()).isFalse();
            assertThat(context.getEnvironment().getProperty("app.sample.read-timeout")).isEqualTo("PT15S");
        }
    }

    @Test
    @DisplayName("База недоступна — в логе «работаю на файле» и класс причины, текста причины нет")
    void afterSingletonsInstantiated_shouldLogFileOnly_whenReplicaFails() {
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ParameterReplicaLoader.class);
        logger.addAppender(log);
        try (ConfigurableApplicationContext ignored = run(BrokenReplica.class)) {
            assertThat(log.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(line -> assertThat(line).contains("работаю на файле", "IllegalStateException")
                            .doesNotContain("база недоступна"));
        } finally {
            logger.detachAppender(log);
        }
    }

    @Test
    @DisplayName("Пустой список в реплике перекрывает непустой список файла")
    void afterSingletonsInstantiated_shouldBindEmptyList_whenReplicaListIsEmpty() {
        try (ConfigurableApplicationContext context = run(ReplicaWithEmptyList.class)) {
            List<String> extensions = Binder.get(context.getEnvironment())
                    .bind("app.sample.extensions", Bindable.listOf(String.class))
                    .get();

            assertThat(extensions).isEmpty();
        }
    }

    private static ConfigurableApplicationContext run(Class<?> replica) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("KNOBS_DC", "dc2", "KNOBS_DIMENSIONS", "dc,tenant,segment,channel",
                                "SAMPLE_HOST", "crm.dc2.example")));
        return new SpringApplicationBuilder(Probes.class, replica)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties("spring.application.name=orders")
                .run();
    }

    private static ParameterRecord record(String key, ParameterType type, boolean list, String value) {
        return ParameterRecord.builder().key(key).type(type).list(list).value(MAPPER.readTree(value))
                .version(5L).build();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(ParameterAutoConfiguration.class)
    static class Probes {

        @Bean
        StartProbe startProbe(Environment environment) {
            return new StartProbe(environment);
        }

        @Bean
        ChangeProbe changeProbe() {
            return new ChangeProbe();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ReplicaWithTimeout {

        @Bean
        ParameterReplica parameterReplica() {
            InMemoryReplica replica = new InMemoryReplica();
            replica.put(record("app.sample.read-timeout", ParameterType.DURATION, false, "\"PT20S\""));
            return replica;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ReplicaWithEmptyList {

        @Bean
        ParameterReplica parameterReplica() {
            InMemoryReplica replica = new InMemoryReplica();
            replica.put(record("app.sample.extensions", ParameterType.STRING, true, "[]"));
            return replica;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BrokenReplica {

        @Bean
        ParameterReplica parameterReplica() {
            InMemoryReplica replica = new InMemoryReplica();
            replica.failing = true;
            return replica;
        }
    }

    /** Запоминает значение свойства в момент старта жизненного цикла — тогда стартуют слушатели Kafka. */
    static final class StartProbe implements SmartLifecycle {

        private final Environment environment;

        private String seenAtStart;

        private boolean running;

        StartProbe(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void start() {
            seenAtStart = environment.getProperty("app.sample.read-timeout");
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    /** Запоминает события смены параметров. */
    static final class ChangeProbe {

        private final List<ParametersChanged> events = new ArrayList<>();

        @EventListener
        void onChange(ParametersChanged event) {
            events.add(event);
        }
    }
}
