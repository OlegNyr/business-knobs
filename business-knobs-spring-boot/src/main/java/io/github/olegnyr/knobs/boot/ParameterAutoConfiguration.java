package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterReplica;
import io.github.olegnyr.knobs.impl.MicrometerParameterObserver;
import io.github.olegnyr.knobs.impl.ParameterClient;
import io.github.olegnyr.knobs.impl.ParameterEvents;
import io.github.olegnyr.knobs.impl.ParameterObserver;
import io.github.olegnyr.knobs.impl.ParameterPatcher;
import io.github.olegnyr.knobs.impl.ParameterRecordApplierImpl;
import io.github.olegnyr.knobs.impl.ParameterReplicaLoader;
import io.github.olegnyr.knobs.impl.ParameterStore;
import io.github.olegnyr.knobs.impl.evaluation.ContextDimensions;
import io.github.olegnyr.knobs.impl.group.ParameterReader;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.yaml.snakeyaml.Yaml;

import java.time.Clock;
import java.util.Objects;

/**
 * Бины библиотеки параметров. Слой файла ставит
 * {@link ParameterEnvironmentPostProcessor} ещё до контекста; здесь из тех же классов
 * {@code runtime}, что собирает {@code Knobs} без Spring, собирается то, что
 * работает с бинами сервиса: реплика из базы, применение записей топика, чтение с контекстом,
 * метрики и аварийная точка. Событие смены уходит слушателям контекста. Настройки самой
 * библиотеки — группа {@link ParameterSettingsProperties} ({@code knobs.*}).
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration"
})
@EnableConfigurationProperties(ParameterSettingsProperties.class)
public class ParameterAutoConfiguration {

    /**
     * Снимок пода — один на контекст: слой, который постпроцессор поставил в {@code Environment},
     * а без файла стенда — пустой снимок с путём пода. Остальные бины работают с ним, а не ищут
     * слой в окружении.
     *
     * @param environment окружение со слоем параметров
     * @param settings    настройки библиотеки — ЦОД пода и измерения пути
     * @return хранилка снимка
     */
    @Bean
    @ConditionalOnMissingBean
    ParameterStore parameterStore(ConfigurableEnvironment environment, ParameterSettingsProperties settings) {
        ParameterStore store = ParameterPropertySource.store(environment).orElseGet(() -> ParameterStore.empty(
                ContextPath.builder()
                        .put(ContextPath.SERVICE, environment.getProperty("spring.application.name"))
                        .put(ContextPath.DC, settings.getDc())
                        .build(),
                settings.contextDimensions().orElse(ContextDimensions.DEFAULT)));
        requireSnapshotSettings(store, settings);
        return store;
    }

    /**
     * Снимок собран постпроцессором окружения до контекста — из тех источников свойств, что были
     * тогда. Источник, подключённый позже ({@code @PropertySource}, {@code @DynamicPropertySource}),
     * виден группе настроек, но не снимку: правила по ЦОД и измерениям молча действовали бы не те.
     * Расхождение — отказ старта с подсказкой.
     */
    private static void requireSnapshotSettings(ParameterStore store, ParameterSettingsProperties settings) {
        String snapshotDc = store.podPath().get(ContextPath.DC).orElse(null);
        String settingsDc = settings.getDc() == null || settings.getDc().isBlank() ? null : settings.getDc();
        if (!Objects.equals(snapshotDc, settingsDc)) {
            throw new IllegalStateException("knobs.dc=" + settingsDc + " пришёл из источника свойств, подключённого "
                    + "после подготовки окружения (@PropertySource и т. п.), а снимок параметров собран с dc="
                    + snapshotDc + ": задайте knobs.dc в application.yaml, переменной окружения KNOBS_DC либо "
                    + "аргументе запуска");
        }
        settings.contextDimensions().ifPresent(dimensions -> {
            if (!dimensions.equals(store.parameters().dimensions())) {
                throw new IllegalStateException("knobs.dimensions=" + dimensions + " расходится с измерениями "
                        + "снимка " + store.parameters().dimensions() + ": порядок задаёт файл стенда, а настройка "
                        + "из позднего источника свойств до снимка не доходит — уберите её либо задайте в "
                        + "application.yaml или переменной KNOBS_DIMENSIONS");
            }
        });
    }

    /**
     * Рассылка смены: первый её потребитель — слушатели контекста Spring.
     *
     * @param context  контекст приложения
     * @param observer наблюдатель метрик, если в сервисе есть Micrometer
     * @return рассылка
     */
    @Bean
    ParameterEvents parameterEvents(ConfigurableApplicationContext context,
                                    ObjectProvider<ParameterObserver> observer) {
        ParameterObserver resolved = observer.getIfAvailable(() -> ParameterObserver.NONE);
        ParameterEvents events = new ParameterEvents(resolved);
        events.addListener(new ParametersChangedPublisher(context, resolved));
        return events;
    }

    /**
     * Применение записей топика; запись в него передаёт подписчик сервиса.
     *
     * @param store    снимок пода
     * @param settings настройки библиотеки — рубильник реплики
     * @param replica  реплика сервиса, если он её объявил
     * @param events   рассылка смены
     * @param observer наблюдатель метрик, если в сервисе есть Micrometer
     * @return применитель записей
     */
    @Bean
    @ConditionalOnMissingBean
    ParameterRecordApplierImpl parameterRecordApplier(ParameterStore store, ParameterSettingsProperties settings,
            ObjectProvider<ParameterReplica> replica, ParameterEvents events,
            ObjectProvider<ParameterObserver> observer) {
        return new ParameterRecordApplierImpl(store, replica::getIfAvailable,
                settings::isReplicaEnabled, events, observer.getIfAvailable(() -> ParameterObserver.NONE));
    }

    /**
     * Загрузчик реплики на подъёме.
     *
     * @param store    снимок пода
     * @param settings настройки библиотеки — рубильник реплики
     * @param replica  реплика сервиса, если он её объявил
     * @param events   рассылка смены
     * @return загрузчик, срабатывающий до старта слушателей
     */
    @Bean
    ParameterReplicaStartup parameterReplicaStartup(ParameterStore store, ParameterSettingsProperties settings,
            ObjectProvider<ParameterReplica> replica, ParameterEvents events) {
        return new ParameterReplicaStartup(new ParameterReplicaLoader(store, replica::getIfAvailable,
                settings::isReplicaEnabled, events));
    }

    /**
     * Чтение параметра с контекстом обращения и {@code Client} OpenFeature в домене
     * {@link ParameterClient#DOMAIN}.
     *
     * <p>Экземпляр API изолированный, а не процессный синглтон: регистрация провайдера в синглтоне
     * перетирает предыдущую, и второй контекст Spring в той же JVM (тесты с подменой бинов)
     * подменил бы провайдер первого — тот отвечал бы чужим снимком. Клиента сервис берёт из
     * {@link ParameterClient#client()}, а не из синглтона.
     *
     * @param store снимок пода
     * @return клиент параметров
     */
    @Bean
    @ConditionalOnMissingBean
    public ParameterClient parameterClient(ParameterStore store) {
        return ParameterClient.create(store);
    }

    /**
     * Читатель групп параметров ({@code @EnableKnobs}): {@code Binder} Spring
     * над слоем параметров — и только над ним.
     *
     * @param store снимок пода
     * @return читатель групп
     */
    @Bean
    @ConditionalOnMissingBean
    ParameterReader parameterReader(ParameterStore store) {
        return new BinderParameterReader(store);
    }

    /**
     * Метрики — когда в сервисе есть Micrometer и его регистр, а своего наблюдателя у сервиса нет:
     * свой наблюдатель сервиса заменяет метрики библиотеки, а не становится вторым кандидатом.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnBean(MeterRegistry.class)
        @ConditionalOnMissingBean(ParameterObserver.class)
        ParameterObserver parameterObserver(MeterRegistry registry, ParameterStore store) {
            return new MicrometerParameterObserver(registry, store);
        }
    }

    /**
     * Аварийная точка — когда в сервисе есть актуатор.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Endpoint.class, Yaml.class})
    static class EndpointConfiguration {

        /**
         * Аварийная точка параметров.
         *
         * @param store   снимок пода
         * @param applier применитель — единственный писатель снимка
         * @param clock   часы сервиса; нет бина — системные UTC
         * @return точка {@code parameters}
         */
        @Bean
        ParameterEndpoint parameterEndpoint(ParameterStore store, ParameterRecordApplierImpl applier,
                ObjectProvider<Clock> clock) {
            return new ParameterEndpoint(new ParameterPatcher(store, applier, clock.getIfAvailable(Clock::systemUTC)));
        }
    }
}
