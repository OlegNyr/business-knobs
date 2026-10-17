package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.impl.ParameterStore;
import io.github.olegnyr.knobs.impl.StandFiles;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ставит слой параметров файла своего стенда в {@code Environment} до создания бинов.
 *
 * <p>Стенд — тот из активных профилей (без активных — профилей по умолчанию), для которого в
 * сборке есть файл {@code knobs/knobs-<стенд>.json}. Нет такого — слоя нет; таких
 * несколько либо файл стенда лежит в двух местах classpath — подъём падает: стенд неоднозначен,
 * а молча выбрать один значит отдать чужие значения. ЦОД пода — {@code knobs.dc} группы
 * {@link ParameterSettingsProperties} (переменная {@code KNOBS_DC}), связанной прямо из
 * окружения: бинов ещё нет; сервис — {@code spring.application.name}. Подстановки
 * {@code ${ИМЯ}} разрешаются только по переменным окружения процесса.
 *
 * <p>Слой встаёт <em>перед</em> переменными окружения: для ключа каталога параметр главнее
 * окружения стенда, а ключ, забытый в {@code application.yaml}, его тем более не
 * перекрывает. Системные свойства JVM и аргументы командной строки остаются выше — это ручное
 * вмешательство при запуске, не настройка стенда.
 *
 * <p>Лог отложенный: постпроцессор работает до настройки логирования, и прямая запись была бы
 * подавлена. В лог идут ключи параметров и имена переменных, значения — никогда.
 *
 * <p>Поиск и чтение файла — {@link StandFiles} без Spring; здесь только выбор стенда
 * по профилям, путь пода из свойств и место слоя в {@code Environment}.
 */
public class ParameterEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    // PMD.ProperLogger: лог отложенный — его выдаёт Spring Boot конструктору, статическим он быть
    // не может: постпроцессор работает до настройки логирования.
    @SuppressWarnings("PMD.ProperLogger")
    private final Log log;

    /**
     * Постпроцессор с отложенным логом; аргумент передаёт Spring Boot.
     *
     * @param logFactory фабрика отложенных логов
     */
    public ParameterEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(ParameterEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String[] profiles = environment.getActiveProfiles().length > 0
                ? environment.getActiveProfiles()
                : environment.getDefaultProfiles();
        Optional<StandFiles.Located> located = StandFiles.locate(classLoader(application), List.of(profiles));
        if (located.isEmpty()) {
            log.info("Файла параметров для профилей " + Arrays.toString(profiles) + " нет: слоя параметров нет");
            return;
        }
        String service = environment.getProperty("spring.application.name");
        if (!Objects.equals(service, located.get().file().getService())) {
            log.warn("Файл параметров собран для сервиса " + located.get().file().getService() + ", а сервис пода — "
                    + service + ": правила по сервису не сработают");
        }
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> processEnvironment =
                sources.get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        ParameterSettingsProperties settings = ParameterSettingsProperties.bind(environment);
        String dc = settings.getDc();
        ContextPath podPath = ContextPath.builder()
                .put(ContextPath.SERVICE, service)
                .put(ContextPath.DC, dc)
                .build();
        ParameterStore store = StandFiles.store(located.get(), podPath, settings.contextDimensions().orElse(null),
                name -> processEnvironment == null ? null : stringOrNull(processEnvironment.getProperty(name)),
                (key, variables) -> log.warn(StandFiles.unresolvedMessage(key, variables)));
        place(sources, new ParameterPropertySource(store));
        log.info("Слой параметров из " + located.get().description() + ": service=" + service + ", dc=" + dc
                + ", параметров " + store.parameters().parameters().size());
    }

    private static ClassLoader classLoader(SpringApplication application) {
        ClassLoader loader = application.getResourceLoader() != null
                ? application.getResourceLoader().getClassLoader()
                : application.getClassLoader();
        return loader != null ? loader : ClassUtils.getDefaultClassLoader();
    }

    private static void place(MutablePropertySources sources, ParameterPropertySource layer) {
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addBefore(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, layer);
        } else if (sources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, layer);
        } else {
            sources.addLast(layer);
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
