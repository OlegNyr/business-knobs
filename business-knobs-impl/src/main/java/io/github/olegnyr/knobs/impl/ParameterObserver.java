package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.ParameterRecordApplier;

/**
 * Наблюдатель библиотеки параметров: метрики без привязки к реестру.
 * Пустой — когда метрик в сервисе нет.
 */
public interface ParameterObserver {

    /**
     * Наблюдатель, который ничего не считает.
     */
    ParameterObserver NONE = new ParameterObserver() {
    };

    /**
     * Запись топика применена с исходом.
     *
     * @param outcome исход применения
     */
    default void recordOutcome(ParameterRecordApplier.Outcome outcome) {
    }

    /**
     * Потребитель события смены отказал.
     */
    default void consumerFailed() {
    }
}
