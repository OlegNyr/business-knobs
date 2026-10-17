package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.impl.ParameterStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Настройки библиотеки {@code knobs.*} — группа свойств Spring с описанием. */
@DisplayName("Настройки библиотеки knobs.*")
class ParameterSettingsPropertiesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("Рубильник и ЦОД связываются в группу свойств; по умолчанию реплика читается")
    void properties_shouldBindDefaults_whenNothingIsSet() {
        try (ConfigurableApplicationContext context = ParameterEndpointTest.run(Map.of())) {
            ParameterSettingsProperties properties = context.getBean(ParameterSettingsProperties.class);

            assertThat(properties.isReplicaEnabled()).isTrue();
            assertThat(properties.getDc()).isEqualTo("dc2");
        }
    }

    @Test
    @DisplayName("Измерения пути без настройки — из секции dimensions файла стенда")
    void dimensions_shouldComeFromStandFile_whenNotSet() {
        try (ConfigurableApplicationContext context = runWithVariables(Map.of("KNOBS_DC", "dc2"))) {
            assertThat(context.getBean(ParameterSettingsProperties.class).getDimensions()).isEmpty();
            assertThat(context.getBean(ParameterStore.class).parameters().dimensions().order())
                    .containsExactly("dc", "tenant", "segment", "channel");
        }
    }

    @Test
    @DisplayName("KNOBS_DIMENSIONS, расходящаяся с файлом стенда, валит старт и называет оба списка")
    void dimensions_shouldFailStartup_whenVariableDiffersFromStandFile() {
        Throwable thrown = catchThrowable(() ->
                runWithVariables(Map.of("KNOBS_DC", "dc2", "KNOBS_DIMENSIONS", "dc, segment")).close());

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[dc, segment]")
                .hasMessageContaining("[dc, tenant, segment, channel]");
    }

    @Test
    @DisplayName("Рубильник из свойства выключает реплику через группу свойств")
    void properties_shouldSwitchReplicaOff_whenPropertyIsFalse() {
        try (ConfigurableApplicationContext context =
                ParameterEndpointTest.run(Map.of("knobs.replica-enabled", "false"))) {
            assertThat(context.getBean(ParameterSettingsProperties.class).isReplicaEnabled()).isFalse();
            assertThat(context.getBean(InMemoryReplica.class).calls.get()).isZero();
        }
    }

    @Test
    @DisplayName("Метаданные описывают knobs.dc и knobs.replica-enabled с умолчанием")
    void metadata_shouldDescribeSettings_whenModuleIsBuilt() throws IOException {
        JsonNode metadata;
        try (InputStream stream = Objects.requireNonNull(ParameterSettingsProperties.class.getClassLoader()
                .getResourceAsStream("META-INF/spring-configuration-metadata.json"))) {
            metadata = MAPPER.readTree(stream);
        }

        assertThat(property(metadata, "knobs.dc").path("description").asString()).isNotBlank();
        assertThat(property(metadata, "knobs.replica-enabled").path("defaultValue").asBoolean()).isTrue();
        assertThat(property(metadata, "knobs.dimensions").path("description").asString()).isNotBlank();
    }

    @Test
    @DisplayName("knobs.dc из @PropertySource до снимка не доходит — отказ старта с подсказкой, а не молча другой ЦОД")
    void dc_shouldFailStartup_whenSetByLatePropertySource() {
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(ParameterEndpointTest.TestBeans.class,
                LateSource.class)
                .environment(environment(Map.of()))
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties("spring.application.name=orders")
                .run()
                .close());

        assertThat(thrown).hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("knobs.dc").hasMessageContaining("dc1");
    }

    @Configuration(proxyBeanMethods = false)
    @PropertySource("classpath:late-knobs.properties")
    static class LateSource {
    }

    private static StandardEnvironment environment(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        variables));
        return environment;
    }

    private static ConfigurableApplicationContext runWithVariables(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        variables));
        return new SpringApplicationBuilder(ParameterEndpointTest.TestBeans.class)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties("spring.application.name=orders")
                .run();
    }

    private static JsonNode property(JsonNode metadata, String name) {
        for (JsonNode property : metadata.path("properties")) {
            if (name.equals(property.path("name").asString())) {
                return property;
            }
        }
        throw new AssertionError("Нет свойства в метаданных: " + name);
    }
}
