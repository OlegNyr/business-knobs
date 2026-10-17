package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterContext;
import io.github.olegnyr.knobs.ParameterJson;
import io.github.olegnyr.knobs.ParameterMissingException;
import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterRule;
import io.github.olegnyr.knobs.ParameterType;
import io.github.olegnyr.knobs.ParameterTypeMismatchException;
import io.github.olegnyr.knobs.boot.group.AbstractCapabilitiesGroup;
import io.github.olegnyr.knobs.boot.group.CapabilitiesGroup;
import io.github.olegnyr.knobs.boot.group.SampleGroup;
import io.github.olegnyr.knobs.impl.ParameterStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Группа параметров в Spring: интерфейс с геттерами регистрируется
 * бином по {@code @EnableKnobs}; геттер при каждом вызове идёт через {@code Binder}
 * Spring в слой параметров — и только в него: значение по умолчанию живёт в {@code knobs.yaml},
 * а не в {@code application.yaml} и не в окружении.
 */
@DisplayName("Группа параметров — бин Spring с живыми геттерами")
class ParameterGroupTest {

    private static final Map<String, Object> POD_ENVIRONMENT = Map.of(
            "KNOBS_DC", "dc2", "KNOBS_DIMENSIONS", "dc,tenant,segment,channel",
            "SAMPLE_HOST", "crm.dc2.example",
            "APP_SAMPLE_READ_TIMEOUT", "PT1S",
            "APP_SAMPLE_TOKEN_NAME", "from-environment");

    private static final ContextPath VIP = ContextPath.builder().put("segment", "VIP").build();

    @Test
    @DisplayName("Группы пакета зарегистрированы бинами и типизированы: Duration по правилу ЦОД, int из INTEGER")
    void groups_shouldBeRegisteredAndTyped() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(sample(context).readTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(context.getBean(CapabilitiesGroup.class).maxFiles()).isEqualTo(5);
            assertThat(sample(context).bigNumber()).isEqualTo(5_000_000_000L);
        }
    }

    /**
     * Тип бина группы известен до её создания: срезы и условия {@code @ConditionalOnBean} ищут бины по
     * типу без ранней инициализации, и описание бина обязано отвечать на это само. Атрибут типа фабрики Spring
     * принимает только {@code Class} или {@code ResolvableType}: строкой он ронял подъём среза
     * {@code BeanDefinitionStoreException} «Invalid value type for attribute 'factoryBeanObjectType'».
     */
    @Test
    @DisplayName("Бин группы находится по типу до создания фабрики — тип фабрики объявлен классом")
    void groups_shouldBeFoundByType_beforeFactoryIsCreated() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        ParameterGroupRegistrar registrar = new ParameterGroupRegistrar();
        registrar.setEnvironment(new StandardEnvironment());
        registrar.setResourceLoader(new DefaultResourceLoader());
        registrar.registerBeanDefinitions(AnnotationMetadata.introspect(GroupsConfiguration.class), registry);

        assertThat(registry.getBeanNamesForType(SampleGroup.class, true, false)).containsExactly("sampleGroup");
        assertThat(registry.getBeanNamesForType(AbstractCapabilitiesGroup.class, true, false))
                .containsExactly("abstractCapabilitiesGroup");
    }

    @Test
    @DisplayName("Группа на абстрактном классе зарегистрирована бином: абстрактные геттеры живые, конкретный метод работает")
    void abstractClassGroup_shouldBeRegisteredAndProxied() {
        try (ConfigurableApplicationContext context = run()) {
            AbstractCapabilitiesGroup capabilities = context.getBean(AbstractCapabilitiesGroup.class);

            assertThat(capabilities.maxFiles()).isEqualTo(5);
            assertThat(capabilities.maxFilesWithCover()).isEqualTo(6);
            assertThat(capabilities.stickers()).isFalse();
        }
    }

    @Test
    @DisplayName("Список — элементы как есть, пустой список — пустая коллекция")
    void lists_shouldBindElements() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(sample(context).extensions()).containsExactly("jpg", "a,b");
            assertThat(sample(context).blocked()).isEmpty();
        }
    }

    @Test
    @DisplayName("Геттер отвечает по контексту обращения на потоке, а не по снимку связывания")
    void waitNotice_shouldFollowThreadContext() {
        try (ConfigurableApplicationContext context = run()) {
            SampleGroup sample = sample(context);
            String plain = sample.waitNotice();
            String vip;
            try (ParameterContext.Scope ignored = ParameterContext.open(VIP)) {
                vip = sample.waitNotice();
            }
            String otherThread;
            try (ParameterContext.Scope ignored = ParameterContext.open(VIP)) {
                otherThread = CompletableFuture.supplyAsync(sample::waitNotice).join();
            }

            assertThat(plain).isEqualTo("обычный");
            assertThat(vip).isEqualTo("vip");
            assertThat(otherThread).isEqualTo("обычный");
        }
    }

    @Test
    @DisplayName("Запись топика сменила значение — следующий вызов геттера видит новое")
    void readTimeout_shouldSeeNewValue_afterSnapshotReplaced() {
        try (ConfigurableApplicationContext context = run()) {
            SampleGroup sample = sample(context);
            Duration before = sample.readTimeout();
            ParameterStore store = context.getBean(ParameterStore.class);
            store.replace(store.parameters().withRecord(ParameterRecord.builder()
                    .key("app.sample.read-timeout").type(ParameterType.DURATION)
                    .value(ParameterJson.readValue("\"PT42S\"")).version(10L).build()));

            assertThat(before).isEqualTo(Duration.ofSeconds(15));
            assertThat(sample.readTimeout()).isEqualTo(Duration.ofSeconds(42));
        }
    }

    @Test
    @DisplayName("Значения нет — Optional пуст, хотя ключ есть и в окружении, и в application.yaml: "
            + "умолчания только в knobs.yaml")
    void tokenName_shouldBeEmpty_whenOnlyEnvironmentAndApplicationHaveKey() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(context.getEnvironment().getProperty("app.sample.token-name")).isEqualTo("from-environment");
            assertThat(sample(context).tokenName()).isEmpty();
        }
    }

    @Test
    @DisplayName("Значения нет у примитива (запись оставила только правило чужого сегмента) — "
            + "ParameterMissingException с ключом, а не умолчание из окружения")
    void maxFiles_shouldThrowMissing_whenValueExistsOnlyForOtherSegment() {
        try (ConfigurableApplicationContext context = run()) {
            ParameterStore store = context.getBean(ParameterStore.class);
            store.replace(store.parameters().withRecord(ParameterRecord.builder()
                    .key("app.capabilities.max-files").type(ParameterType.INTEGER).version(10L)
                    .rule(ParameterRule.builder().when(Map.of("segment", "VIP"))
                            .value(ParameterJson.readValue("7")).build())
                    .build()));

            Throwable thrown = catchThrowable(() -> context.getBean(CapabilitiesGroup.class).maxFiles());

            assertThat(thrown).isInstanceOf(ParameterMissingException.class)
                    .hasMessageContaining("app.capabilities.max-files");
        }
    }

    @Test
    @DisplayName("Тип метода не подходит значению — ParameterTypeMismatchException с ключом")
    void readTimeoutAsInt_shouldThrowTypeMismatch() {
        try (ConfigurableApplicationContext context = run()) {
            Throwable thrown = catchThrowable(() -> sample(context).readTimeoutAsInt());

            assertThat(thrown).isInstanceOf(ParameterTypeMismatchException.class)
                    .hasMessageContaining("app.sample.read-timeout");
        }
    }

    @Test
    @DisplayName("default-метод интерфейса исполняется поверх живых геттеров")
    void slow_shouldInvokeDefaultMethod() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(sample(context).slow()).isTrue();
        }
    }

    private static SampleGroup sample(ConfigurableApplicationContext context) {
        return context.getBean(SampleGroup.class);
    }

    private static ConfigurableApplicationContext run() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        POD_ENVIRONMENT));
        return new SpringApplicationBuilder(GroupsConfiguration.class)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties(Map.of("spring.application.name", "orders", "app.sample.token-name", "from-application"))
                .run();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(ParameterAutoConfiguration.class)
    @EnableKnobs(basePackageClasses = SampleGroup.class)
    static class GroupsConfiguration {
    }
}
