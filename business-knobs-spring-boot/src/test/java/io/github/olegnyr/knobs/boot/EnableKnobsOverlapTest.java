package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.boot.group.SampleGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @EnableKnobs} на приложении и на автоконфигурации фичи сканируют вложенные пакеты —
 * группа находится дважды; группы с одинаковым простым именем лежат в разных пакетах.
 */
@DisplayName("@EnableKnobs: пересекающиеся сканы и одинаковые простые имена групп")
class EnableKnobsOverlapTest {

    @Test
    @DisplayName("Контекст поднимается: каждая группа — один бин, тёзки из разных пакетов не конфликтуют")
    void context_shouldStart_whenScansOverlap() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(context.getBeansOfType(SampleGroup.class)).hasSize(1);
            assertThat(context.getBeansOfType(io.github.olegnyr.knobs.boot.group.other.SampleGroup.class))
                    .hasSize(1);
            assertThat(context.getBean(io.github.olegnyr.knobs.boot.group.other.SampleGroup.class).readTimeout())
                    .isEqualTo(Duration.ofSeconds(15));
        }
    }

    private static ConfigurableApplicationContext run() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("KNOBS_DC", "dc2", "SAMPLE_HOST", "crm.dc2.example")));
        return new SpringApplicationBuilder(ApplicationConfiguration.class)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties(Map.of("spring.application.name", "orders"))
                .run();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(ParameterAutoConfiguration.class)
    @EnableKnobs(basePackageClasses = SampleGroup.class)
    @Import(FeatureConfiguration.class)
    static class ApplicationConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKnobs(basePackageClasses = io.github.olegnyr.knobs.boot.group.other.SampleGroup.class)
    static class FeatureConfiguration {
    }
}
