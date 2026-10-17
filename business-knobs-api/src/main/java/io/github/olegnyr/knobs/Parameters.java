package io.github.olegnyr.knobs;

import dev.openfeature.sdk.Client;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Чтение параметров кодом сервиса: значение для пути пода
 * (сервис, ЦОД), дополненного путём обращения текущего потока ({@link ParameterContext}).
 *
 * <p>«Значения нет» отличимо от значения: пустой {@code Optional} здесь, {@code FLAG_NOT_FOUND} у
 * {@link #client()}. Длительность и дату OpenFeature не знает — их отдают {@link #duration(String)}
 * и {@link #date(String)}. Реализацию даёт библиотека: {@code Knobs#parameters()} без Spring
 * либо бин автоконфигурации.
 */
public interface Parameters {

    /**
     * Значение параметра в типе Java: скаляр либо неизменяемый список.
     *
     * @param key ключ параметра
     * @return значение; пусто, если его нет ни в одном источнике
     */
    Optional<Object> value(String key);

    /**
     * Значение параметра типа {@code DURATION}.
     *
     * @param key ключ параметра
     * @return длительность; пусто — значения нет либо тип другой
     */
    Optional<Duration> duration(String key);

    /**
     * Значение параметра типа {@code DATE}.
     *
     * @param key ключ параметра
     * @return дата; пусто — значения нет либо тип другой
     */
    Optional<LocalDate> date(String key);

    /**
     * Все параметры под префиксом ключа со значениями для текущего пути.
     *
     * @param prefix префикс ключа
     * @return ключ → значение; параметры без значения не входят
     */
    Map<String, Object> byPrefix(String prefix);

    /**
     * Стандартный {@code Client} OpenFeature над параметрами — в изолированном экземпляре API
     * библиотеки, а не в процессном синглтоне.
     *
     * @return клиент домена {@code business-knobs}
     */
    Client client();
}
