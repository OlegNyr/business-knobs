package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.impl.evaluation.ContextDimensions;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Optional;

/**
 * Настройки библиотеки параметров в Spring-сервисе ({@code knobs.*}).
 *
 * <p>Ключи те же, что у сборщика {@code Knobs} без Spring: {@code dc} — его {@code dc(...)},
 * {@code dimensions} — {@code dimensions(...)}, {@code replica-enabled} —
 * {@code replicaEnabled(...)}. Сервис пода берётся из
 * {@code spring.application.name}, стенд — из активных профилей: своих ключей у них здесь нет.
 */
// final — как у прочих @ConfigurationProperties проекта (SpotBugs CT_CONSTRUCTOR_THROW).
@ConfigurationProperties(prefix = ParameterSettingsProperties.PREFIX)
@Getter
public final class ParameterSettingsProperties {

    /** Префикс группы. */
    public static final String PREFIX = "knobs";

    /**
     * ЦОД пода для правил параметров по измерению {@code dc} (переменная {@code KNOBS_DC}).
     * Не задан — правила по ЦОД не подходят, действуют правила без него.
     */
    private final String dc;

    /**
     * Рубильник реплики: {@code false} — реплика в базе не читается, записи топика не
     * применяются, под работает на файле стенда; аварийная правка при этом действует.
     */
    private final boolean replicaEnabled;

    /**
     * Измерения пути контекста в порядке точности (переменная {@code KNOBS_DIMENSIONS}, через
     * запятую). Обычно не задаётся: порядок берётся из секции dimensions файла стенда. Нужна
     * сервису, чей файл измерений не объявляет; заданная и расходящаяся с файлом — отказ на
     * старте. Без файла и без настройки измерение одно — dc.
     */
    private final List<String> dimensions;

    /**
     * Связывание группы.
     *
     * @param dc             ЦОД пода
     * @param replicaEnabled читать ли реплику
     * @param dimensions     измерения пути в порядке точности
     */
    public ParameterSettingsProperties(String dc, @DefaultValue("true") boolean replicaEnabled,
                                       List<String> dimensions) {
        this.dc = dc;
        this.replicaEnabled = replicaEnabled;
        this.dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }

    /**
     * Измерения пути, заданные сервисом и проверенные библиотекой.
     *
     * @return измерения в порядке точности; пусто — не заданы, порядок из файла стенда
     * @throws IllegalArgumentException ключ пуст либо повторяется, в списке {@code service}
     */
    public Optional<ContextDimensions> contextDimensions() {
        return dimensions.isEmpty() ? Optional.empty() : Optional.of(ContextDimensions.of(dimensions));
    }

    /**
     * Связывает группу прямо из окружения — там, где бинов ещё нет: постпроцессор окружения
     * работает до контекста.
     *
     * @param environment окружение
     * @return настройки; ключей нет — умолчания
     */
    public static ParameterSettingsProperties bind(Environment environment) {
        return Binder.get(environment).bindOrCreate(PREFIX, Bindable.of(ParameterSettingsProperties.class));
    }
}
