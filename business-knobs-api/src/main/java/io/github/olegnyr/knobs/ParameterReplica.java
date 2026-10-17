package io.github.olegnyr.knobs;

import java.util.List;
import java.util.Optional;

/**
 * Порт реплики параметров в базе сервиса: реализует сервис, библиотека от
 * его модулей не зависит. Реплика — копия того, что пришло из админки; пустая реплика законна.
 */
public interface ParameterReplica {

    /**
     * Все строки реплики, включая удалённые.
     *
     * @return строки реплики
     */
    List<ParameterRecord> findAll();

    /**
     * Вставляет строку либо заменяет её, если версия в реплике меньше пришедшей: одна
     * команда, без аренды — порядок прихода и число подов значения не имеют.
     *
     * @param record слепок из записи топика
     * @return {@code true} — строка записана; {@code false} — в реплике версия не меньше
     */
    boolean saveIfNewer(ParameterRecord record);

    /**
     * Строка реплики по ключу.
     *
     * @param key ключ параметра
     * @return строка; пусто, если её нет
     */
    Optional<ParameterRecord> find(String key);
}
