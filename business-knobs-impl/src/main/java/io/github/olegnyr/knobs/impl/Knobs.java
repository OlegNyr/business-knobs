package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterListener;
import io.github.olegnyr.knobs.ParameterRecordApplier;
import io.github.olegnyr.knobs.ParameterReplica;
import io.github.olegnyr.knobs.Parameters;
import io.github.olegnyr.knobs.impl.evaluation.ContextDimensions;
import io.github.olegnyr.knobs.impl.group.ParameterGroups;
import io.github.olegnyr.knobs.impl.group.ParametersReader;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Библиотека параметров без Spring: файл стенда, реплика, применение записей
 * топика, событие смены, аварийная правка и чтение с контекстом — собранные одним сборщиком.
 *
 * <pre>{@code
 * try (Knobs settings = Knobs.builder()
 *         .service("orders").stand("prod").dc(System.getenv("KNOBS_DC"))
 *         .replica(replica).listener(event -> reconfigure(event.getKeys()))
 *         .build()) {
 *     Optional<Duration> timeout = settings.parameters().duration("app.crm.read-timeout");
 *     consumer.onBatch(batch -> settings.records().applyAll(batch));
 * }
 * }</pre>
 *
 * <p>Сборка читает файл стенда и сразу реплику поверх него — до того, как сервис начнёт читать
 * топик. В Spring-сервисе то же собирает автоконфигурация из тех же классов, а снимок
 * виден ещё и как свойства {@code Environment}.
 */
@Slf4j
public final class Knobs implements AutoCloseable {

    private final ParameterStore store;

    private final ParameterEvents events;

    private final ParameterRecordApplier records;

    private final ParameterPatcher patches;

    private final ParameterClient parameters;

    private Knobs(ParameterStore store,
                       ParameterEvents events,
                       ParameterRecordApplier records,
                       ParameterPatcher patches,
                       ParameterClient parameters) {
        this.store = store;
        this.events = events;
        this.records = records;
        this.patches = patches;
        this.parameters = parameters;
    }

    /**
     * Сборщик.
     *
     * @return новый сборщик
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Чтение параметров: значение для пути пода и пути обращения потока, {@code Client} OpenFeature.
     *
     * @return клиент параметров
     */
    public Parameters parameters() {
        return parameters;
    }

    /**
     * Группа параметров — интерфейс с живыми геттерами над {@link #parameters()}.
     *
     * @param type интерфейс с {@code @Knob}
     * @param <T>  тип группы
     * @return прокси группы
     */
    public <T> T group(Class<T> type) {
        return ParameterGroups.create(type, new ParametersReader(parameters));
    }

    /**
     * Применение записей {@code knobs.parameters.v1}: сюда сервис отдаёт пачки своего потребителя.
     *
     * @return применитель записей
     */
    public ParameterRecordApplier records() {
        return records;
    }

    /**
     * Аварийная правка на этом поде.
     *
     * @return аварийная правка
     */
    public ParameterPatcher patches() {
        return patches;
    }

    /**
     * Снимок пода.
     *
     * @return хранилка; файла стенда нет — пустая
     */
    public ParameterStore store() {
        return store;
    }

    /**
     * Подписывает потребителя смены после сборки.
     *
     * @param listener потребитель смены
     */
    public void addListener(ParameterListener listener) {
        events.addListener(listener);
    }

    @Override
    public void close() {
        parameters.close();
    }

    /**
     * Сборщик библиотеки параметров.
     */
    public static final class Builder {

        private String service;

        private final List<String> stands = new ArrayList<>();

        private String dc;

        private Function<String, String> environment = System::getenv;

        private ClassLoader classLoader;

        private ParameterReplica replica;

        private boolean replicaEnabled = true;

        private Function<ParameterStore, ParameterObserver> observer = store -> ParameterObserver.NONE;

        private final List<ParameterListener> listeners = new ArrayList<>();

        private Clock clock = Clock.systemUTC();

        /**
         * Измерения, заданные сервисом; {@code null} — порядок из файла стенда.
         */
        private ContextDimensions dimensions;

        private Builder() {
        }

        /**
         * Сервис пода: правила своего сервиса сильнее общих, файл собран для него.
         *
         * @param name имя сервиса
         * @return этот сборщик
         */
        public Builder service(String name) {
            this.service = name;
            return this;
        }

        /**
         * Стенды-кандидаты; файл берётся у того, для которого он есть.
         *
         * @param names стенды — {@code dev}, {@code test}, {@code prod}
         * @return этот сборщик
         */
        public Builder stand(String... names) {
            this.stands.addAll(List.of(names));
            return this;
        }

        /**
         * ЦОД пода.
         *
         * @param name ЦОД; {@code null} — не задан, правила по ЦОД не подходят
         * @return этот сборщик
         */
        // PMD.ShortMethodName: dc — имя измерения пути (ContextPath.DC) и свойства knobs.dc;
        // другое имя здесь разошлось бы со словарём.
        @SuppressWarnings("PMD.ShortMethodName")
        public Builder dc(String name) {
            this.dc = name;
            return this;
        }

        /**
         * Измерения пути контекста в порядке точности: чем дальше последняя названная часть пути
         * правила, тем правило точнее. Обычно задавать не нужно: порядок берётся из секции
         * {@code dimensions} файла стенда. Нужно сервису, чей файл измерений не объявляет либо
         * которого файла нет; без файла и без настройки измерение одно — {@code dc}. Заданные
         * и расходящиеся с файлом измерения — отказ {@link #build()}.
         *
         * @param order ключи измерений, от наименее точного к самому точному, например
         *              {@code dc, tenant, segment}
         * @return этот сборщик
         * @throws IllegalArgumentException список пуст, ключ пуст либо повторяется, в списке {@code service}
         */
        public Builder dimensions(String... order) {
            this.dimensions = ContextDimensions.of(order);
            return this;
        }

        /**
         * Измерения пути контекста, уже собранные в порядок точности.
         *
         * @param order измерения пути
         * @return этот сборщик
         */
        public Builder dimensions(ContextDimensions order) {
            this.dimensions = Objects.requireNonNull(order);
            return this;
        }

        /**
         * Источник подстановок {@code ${ИМЯ}}; по умолчанию — переменные окружения процесса.
         *
         * @param variables имя → значение
         * @return этот сборщик
         */
        public Builder environment(Function<String, String> variables) {
            this.environment = Objects.requireNonNull(variables);
            return this;
        }

        /**
         * Загрузчик, в чьём classpath лежит файл стенда; по умолчанию — загрузчик потока сборки,
         * а без него — загрузчик библиотеки.
         *
         * @param loader загрузчик сборки сервиса
         * @return этот сборщик
         */
        public Builder classLoader(ClassLoader loader) {
            this.classLoader = Objects.requireNonNull(loader);
            return this;
        }

        /**
         * Реплика в базе сервиса; без неё под работает на файле и записях топика.
         *
         * @param source реплика
         * @return этот сборщик
         */
        public Builder replica(ParameterReplica source) {
            this.replica = source;
            return this;
        }

        /**
         * Рубильник: {@code false} — реплика не читается, записи топика не применяются.
         *
         * @param enabled читать ли реплику
         * @return этот сборщик
         */
        public Builder replicaEnabled(boolean enabled) {
            this.replicaEnabled = enabled;
            return this;
        }

        /**
         * Наблюдатель: метрики исходов и отказов потребителей.
         *
         * @param parameterObserver наблюдатель, например {@link MicrometerParameterObserver}
         * @return этот сборщик
         */
        public Builder observer(ParameterObserver parameterObserver) {
            Objects.requireNonNull(parameterObserver);
            this.observer = store -> parameterObserver;
            return this;
        }

        /**
         * Наблюдатель, которому нужен снимок пода — например, метрики Micrometer:
         * {@code observer(store -> new MicrometerParameterObserver(registry, store))}. Снимок
         * создаёт {@link #build()}, поэтому наблюдатель собирается фабрикой уже над ним.
         *
         * @param factory снимок пода → наблюдатель
         * @return этот сборщик
         */
        public Builder observer(Function<ParameterStore, ParameterObserver> factory) {
            this.observer = Objects.requireNonNull(factory);
            return this;
        }

        /**
         * Потребитель смены; подписан до чтения реплики и узнает и о ней.
         *
         * @param listener потребитель
         * @return этот сборщик
         */
        public Builder listener(ParameterListener listener) {
            this.listeners.add(Objects.requireNonNull(listener));
            return this;
        }

        /**
         * Часы момента установки аварийной правки.
         *
         * @param source часы
         * @return этот сборщик
         */
        public Builder clock(Clock source) {
            this.clock = Objects.requireNonNull(source);
            return this;
        }

        /**
         * Собирает библиотеку: файл стенда, затем реплика поверх него.
         *
         * @return библиотека параметров
         * @throws IllegalStateException файл стенда неоднозначен либо не читается; заданные
         *                               измерения расходятся с файлом
         */
        public Knobs build() {
            ContextPath podPath = ContextPath.builder()
                    .put(ContextPath.SERVICE, service)
                    .put(ContextPath.DC, dc)
                    .build();
            ParameterStore store = StandFiles.locate(resolveClassLoader(), List.copyOf(stands))
                    .map(located -> layer(located, podPath))
                    .orElseGet(() -> {
                        log.info("Файла параметров для стендов {} нет: снимок пустой", stands);
                        return ParameterStore.empty(podPath,
                                dimensions != null ? dimensions : ContextDimensions.DEFAULT);
                    });
            ParameterObserver resolvedObserver = Objects.requireNonNull(observer.apply(store));
            ParameterEvents events = new ParameterEvents(resolvedObserver);
            listeners.forEach(events::addListener);
            // Значения сборщика фиксируются здесь: сборщик, изменённый после build(), собранную
            // библиотеку не меняет.
            ParameterReplica replicaSource = replica;
            boolean enabled = replicaEnabled;
            ParameterRecordApplierImpl records = new ParameterRecordApplierImpl(store, () -> replicaSource,
                    () -> enabled, events, resolvedObserver);
            new ParameterReplicaLoader(store, () -> replicaSource, () -> enabled, events).load();
            return new Knobs(store, events, records, new ParameterPatcher(store, records, clock),
                    ParameterClient.create(store));
        }

        private ParameterStore layer(StandFiles.Located located, ContextPath podPath) {
            if (!Objects.equals(service, located.file().getService())) {
                log.warn("Файл параметров собран для сервиса {}, а сервис пода — {}: правила по сервису не сработают",
                        located.file().getService(), service);
            }
            ParameterStore layer = StandFiles.store(located, podPath, dimensions, environment,
                    (key, variables) -> log.warn(StandFiles.unresolvedMessage(key, variables)));
            log.info("Слой параметров из {}: service={}, dc={}, параметров {}", located.description(), service, dc,
                    layer.parameters().parameters().size());
            return layer;
        }

        // PMD.UseProperClassLoader: загрузчик потока берётся первым; свой — только когда у потока его
        // нет (поток без контекста), иначе файл стенда искать негде.
        @SuppressWarnings("PMD.UseProperClassLoader")
        private ClassLoader resolveClassLoader() {
            if (classLoader != null) {
                return classLoader;
            }
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            return context != null ? context : Knobs.class.getClassLoader();
        }
    }
}
