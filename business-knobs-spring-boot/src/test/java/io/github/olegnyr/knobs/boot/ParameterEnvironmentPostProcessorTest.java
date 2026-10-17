package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterContext;
import io.github.olegnyr.knobs.ParameterJson;
import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterRule;
import io.github.olegnyr.knobs.ParameterType;
import io.github.olegnyr.knobs.impl.ParameterStore;
import lombok.Value;
import org.apache.commons.logging.impl.NoOpLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Параметр из файла своего стенда виден как свойство Spring.
 *
 * <p>Окружение пода подменяется: переменные процесса в JVM не задать, а библиотека читает их
 * из источника {@code systemEnvironment}.
 */
@DisplayName("Параметр файла стенда — свойство Spring")
class ParameterEnvironmentPostProcessorTest {

    private static final Map<String, Object> POD_ENVIRONMENT = Map.of(
            "KNOBS_DC", "dc2", "KNOBS_DIMENSIONS", "dc,tenant,segment,channel",
            "SAMPLE_HOST", "crm.dc2.example",
            "SAMPLE_NESTED", "${SAMPLE_HOST}",
            "APP_SAMPLE_READ_TIMEOUT", "PT1S");

    @Test
    @DisplayName("Правило по ЦОД пода применено при связывании свойств")
    void postProcessEnvironment_shouldApplyDcRule_whenPodDcMatches() {
        try (ConfigurableApplicationContext context = run("prod")) {
            assertThat(properties(context).getReadTimeout()).isEqualTo(Duration.ofSeconds(15));
        }
    }

    @Test
    @DisplayName("Подстановку разрешает библиотека по окружению пода")
    void postProcessEnvironment_shouldResolvePlaceholder_whenVariableIsInEnvironment() {
        try (ConfigurableApplicationContext context = run("prod")) {
            assertThat(layerValue(context, "app.sample.base-url")).isEqualTo("https://crm.dc2.example/api");
        }
    }

    @Test
    @DisplayName("Правила по арендатору, сегменту и каналу при связывании не применяются")
    void postProcessEnvironment_shouldIgnoreRequestRules_whenBindingAtStartup() {
        try (ConfigurableApplicationContext context = run("prod")) {
            assertThat(properties(context).getWaitNotice()).isEqualTo("обычный");
        }
    }

    @Test
    @DisplayName("На потоке с открытым путём обращения Environment отвечает по контексту")
    void getProperty_shouldApplySegmentRule_whenContextIsOpenOnThread() {
        try (ConfigurableApplicationContext context = run("prod");
                ParameterContext.Scope ignored = ParameterContext.open(
                        ContextPath.builder().put("segment", "VIP").build())) {
            assertThat(context.getEnvironment().getProperty("app.sample.wait-notice")).isEqualTo("vip");
        }
    }

    @Test
    @DisplayName("Имена слоя — ключи снимка, а containsProperty — только ключ со значением для пути")
    void propertyNames_shouldListSnapshotKeys_whileContainsPropertyNeedsValue() {
        try (ConfigurableApplicationContext context = run("prod")) {
            EnumerablePropertySource<?> layer = (EnumerablePropertySource<?>) context.getEnvironment()
                    .getPropertySources().get(ParameterPropertySource.NAME);
            ParameterStore store = ParameterPropertySource.store(context.getEnvironment()).orElseThrow();
            store.replace(store.parameters().withRecord(ParameterRecord.builder()
                    .key("app.sample.channel").type(ParameterType.STRING).version(9L)
                    .rule(ParameterRule.builder().when(Map.of("segment", "VIP"))
                            .value(ParameterJson.readValue("\"vip\"")).build())
                    .build()));

            assertThat(layer.getPropertyNames()).contains("app.sample.read-timeout", "app.sample.channel");
            assertThat(layer.containsProperty("app.sample.read-timeout")).isTrue();
            assertThat(layer.containsProperty("app.sample.channel")).as("значение только для сегмента VIP").isFalse();
            assertThat(layer.containsProperty("app.sample.token-name")).as("подстановка не разрешилась").isFalse();
        }
    }

    @Test
    @DisplayName("Правило своего сервиса применено, правило чужого — нет")
    void postProcessEnvironment_shouldApplyOwnServiceRule_whenServiceMatchesApplicationName() {
        try (ConfigurableApplicationContext context = run("prod")) {
            assertThat(properties(context).getChannel()).isEqualTo("свой");
        }
    }

    @Test
    @DisplayName("Список отдаётся по элементам: элемент с запятой не режется")
    void postProcessEnvironment_shouldKeepElements_whenListElementHasComma() {
        try (ConfigurableApplicationContext context = run("prod")) {
            assertThat(properties(context).getExtensions()).containsExactly("jpg", "a,b");
        }
    }

    @Test
    @DisplayName("Пустой список — пустая коллекция, а не «значения нет»")
    void postProcessEnvironment_shouldBindEmptyCollection_whenListIsEmpty() {
        try (ConfigurableApplicationContext context = run("prod")) {
            assertThat(properties(context).getBlocked()).isNotNull().isEmpty();
        }
    }

    @Test
    @DisplayName("Подстановка в значении не разрешилась — значения из файла нет")
    void postProcessEnvironment_shouldSkipParameter_whenValuePlaceholderUnresolved() {
        try (ConfigurableApplicationContext context = run("prod")) {
            assertThat(context.getEnvironment().containsProperty("app.sample.token-name")).isFalse();
        }
    }

    @Test
    @DisplayName("Подстановка в правиле чужого ЦОД не разрешилась — значение параметра остаётся")
    void postProcessEnvironment_shouldKeepValue_whenRulePlaceholderUnresolved() {
        try (ConfigurableApplicationContext context = run("prod")) {
            assertThat(layerValue(context, "app.sample.mirror-url")).isEqualTo("https://mirror");
        }
    }

    @Test
    @DisplayName("Подстановка берётся только из окружения процесса, не из свойств приложения")
    void postProcessEnvironment_shouldSkipParameter_whenVariableIsOnlyApplicationProperty() {
        try (ConfigurableApplicationContext context = run("prod")) {
            assertThat(context.getEnvironment().containsProperty("app.sample.property-host")).isFalse();
        }
    }

    @Test
    @DisplayName("После разрешения остался «${» — значения нет: Spring не разрешает его повторно")
    void postProcessEnvironment_shouldSkipParameter_whenVariableValueHasPlaceholder() {
        try (ConfigurableApplicationContext context = run("prod")) {
            assertThat(context.getEnvironment().containsProperty("app.sample.nested")).isFalse();
        }
    }

    @Test
    @DisplayName("Удалённый параметр значения из файла не даёт")
    void postProcessEnvironment_shouldSkipParameter_whenDeleted() {
        try (ConfigurableApplicationContext context = run("prod")) {
            assertThat(context.getEnvironment().containsProperty("app.sample.retired")).isFalse();
        }
    }

    @Test
    @DisplayName("Переменная окружения стенда параметр не перекрывает")
    void postProcessEnvironment_shouldWinOverEnvironment_whenCatalogKeyIsInEnvironment() {
        try (ConfigurableApplicationContext context = run("prod")) {
            assertThat(context.getEnvironment().getProperty("app.sample.read-timeout")).isEqualTo("PT15S");
        }
    }

    @Test
    @DisplayName("Слой стоит сразу перед переменными окружения")
    void postProcessEnvironment_shouldPlaceLayerBeforeSystemEnvironment_whenFileExists() {
        try (ConfigurableApplicationContext context = run("prod")) {
            List<String> names = context.getEnvironment().getPropertySources().stream()
                    .map(source -> source.getName())
                    .toList();

            assertThat(names.indexOf(ParameterPropertySource.NAME))
                    .isEqualTo(names.indexOf(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME) - 1);
        }
    }

    @Test
    @DisplayName("Файла для активного профиля нет — слоя параметров нет")
    void postProcessEnvironment_shouldAddNothing_whenNoFileForProfile() {
        try (ConfigurableApplicationContext context = run("dev")) {
            assertThat(context.getEnvironment().getPropertySources().contains(ParameterPropertySource.NAME))
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Активных профилей нет — файл ищется по профилю по умолчанию")
    void postProcessEnvironment_shouldUseDefaultProfile_whenNoActiveProfile() {
        try (ConfigurableApplicationContext context = run(Map.of("spring.profiles.default", "prod"))) {
            assertThat(context.getEnvironment().getPropertySources().contains(ParameterPropertySource.NAME))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Файлы есть у двух активных профилей — подъём падает: стенд неоднозначен")
    void postProcessEnvironment_shouldFail_whenSeveralProfilesHaveFiles() {
        Throwable thrown = catchThrowable(() -> run("prod", "test").close());

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("стенд неоднозначен");
    }

    @Test
    @DisplayName("Битый файл — подъём падает с именем файла, без цитаты содержимого")
    void postProcessEnvironment_shouldFailWithFileName_whenFileIsBroken() {
        Throwable thrown = catchThrowable(() -> run("broken").close());

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("knobs-broken.json")
                .hasMessageNotContaining("PT10S");
    }

    @Test
    @DisplayName("Файл нарушает формат — подъём падает с ключом и причиной, без значения")
    void postProcessEnvironment_shouldFailWithKey_whenFileViolatesFormat() {
        Throwable thrown = catchThrowable(() -> run("invalid").close());

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sample.x")
                .hasMessageNotContaining("секрет-1");
    }

    @Test
    @DisplayName("Файл стенда есть в двух местах classpath — подъём падает, а не берёт первый")
    void postProcessEnvironment_shouldFail_whenStandFileIsDuplicatedOnClasspath(@TempDir Path first,
            @TempDir Path second) throws IOException {
        for (Path root : List.of(first, second)) {
            Path file = root.resolve("knobs/knobs-dup.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"service\": \"orders\", \"version\": \"1\", \"stand\": \"dup\"}");
        }
        StandardEnvironment environment = podEnvironment();
        environment.setActiveProfiles("dup");
        SpringApplication application = new SpringApplication();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {first.toUri().toURL(), second.toUri().toURL()}, getClass().getClassLoader())) {
            application.setResourceLoader(new DefaultResourceLoader(loader));

            ParameterEnvironmentPostProcessor postProcessor =
                    new ParameterEnvironmentPostProcessor(destination -> new RecordingLog());

            Throwable thrown = catchThrowable(() -> postProcessor.postProcessEnvironment(environment, application));

            assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("knobs-dup.json");
        }
    }

    @Test
    @DisplayName("Неразрешённая подстановка — в логе ключ и имя переменной, без значений окружения")
    void postProcessEnvironment_shouldLogKeyAndVariable_whenPlaceholderUnresolved() {
        RecordingLog log = postProcess("orders");

        assertThat(log.warnings).anySatisfy(line -> assertThat(line)
                .contains("app.sample.token-name", "SAMPLE_MISSING"));
        assertThat(String.join("\n", log.warnings)).doesNotContain("crm.dc2.example");
    }

    @Test
    @DisplayName("Файл собран для другого сервиса — предупреждение с обоими именами")
    void postProcessEnvironment_shouldWarn_whenFileServiceDiffersFromApplicationName() {
        RecordingLog log = postProcess("billing");

        assertThat(log.warnings).anySatisfy(line -> assertThat(line).contains("orders", "billing"));
    }

    @Test
    @DisplayName("Выбранный файл назван в логе")
    void postProcessEnvironment_shouldLogChosenFile_whenLayerAdded() {
        RecordingLog log = postProcess("orders");

        assertThat(log.infos).anySatisfy(line -> assertThat(line).contains("knobs-prod.json"));
    }

    @Test
    @DisplayName("Лог старта называет сервис и ЦОД пода")
    void postProcessEnvironment_shouldLogServiceAndDc_whenLayerAdded() {
        RecordingLog log = postProcess("orders");

        assertThat(log.infos).anySatisfy(line -> assertThat(line).contains("service=orders", "dc=dc2"));
    }

    private static RecordingLog postProcess(String applicationName) {
        RecordingLog log = new RecordingLog();
        StandardEnvironment environment = podEnvironment();
        environment.setActiveProfiles("prod");
        environment.getPropertySources().addLast(new MapPropertySource("application",
                Map.of("spring.application.name", applicationName)));
        new ParameterEnvironmentPostProcessor(destination -> log)
                .postProcessEnvironment(environment, new SpringApplication());
        return log;
    }

    private static ConfigurableApplicationContext run(String... profiles) {
        return run(Map.of(), profiles);
    }

    private static ConfigurableApplicationContext run(Map<String, Object> properties, String... profiles) {
        Map<String, Object> applicationProperties = new HashMap<>(properties);
        applicationProperties.put("spring.application.name", "orders");
        applicationProperties.put("SAMPLE_PROPERTY_ONLY", "from-property");
        return new SpringApplicationBuilder(SampleConfiguration.class)
                .environment(podEnvironment())
                .web(WebApplicationType.NONE)
                .profiles(profiles)
                .properties(applicationProperties)
                .run();
    }

    private static StandardEnvironment podEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        POD_ENVIRONMENT));
        return environment;
    }

    private static SampleProperties properties(ConfigurableApplicationContext context) {
        return context.getBean(SampleProperties.class);
    }

    private static Object layerValue(ConfigurableApplicationContext context, String key) {
        return context.getEnvironment().getPropertySources().get(ParameterPropertySource.NAME).getProperty(key);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SampleProperties.class)
    static class SampleConfiguration {
    }

    @Value
    @ConfigurationProperties("app.sample")
    static class SampleProperties {

        Duration readTimeout;

        String waitNotice;

        String channel;

        List<String> extensions;

        List<String> blocked;
    }

    /** Лог, который запоминает строки предупреждений и сведений. */
    static final class RecordingLog extends NoOpLog {

        private final List<String> warnings = new ArrayList<>();

        private final List<String> infos = new ArrayList<>();

        @Override
        public void warn(Object message) {
            warnings.add(String.valueOf(message));
        }

        @Override
        public void info(Object message) {
            infos.add(String.valueOf(message));
        }
    }
}
