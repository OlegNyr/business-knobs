package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.ParameterListener;
import io.github.olegnyr.knobs.ParametersChanged;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Рассылка {@link ParametersChanged} так, что отказ одного потребителя не мешает остальным.
 *
 * <p>Отказ уходит в лог классом корневой причины — текст исключения может цитировать значения —
 * и в наблюдатель. Spring-адаптер подключается сюда же одним слушателем, который раздаёт событие
 * слушателям контекста.
 */
@Slf4j
public final class ParameterEvents {

    private final List<ParameterListener> listeners = new CopyOnWriteArrayList<>();

    private final ParameterObserver observer;

    /**
     * Рассылка.
     *
     * @param observer наблюдатель отказов потребителей
     */
    public ParameterEvents(ParameterObserver observer) {
        this.observer = observer;
    }

    /**
     * Подписывает потребителя.
     *
     * @param listener потребитель смены
     */
    public void addListener(ParameterListener listener) {
        listeners.add(listener);
    }

    /**
     * Рассылает смену; пустой набор ключей не рассылается.
     *
     * @param keys ключи одной применённой пачки
     */
    public void publish(Set<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        ParametersChanged event = new ParametersChanged(keys);
        for (ParameterListener listener : listeners) {
            try {
                listener.parametersChanged(event);
            } catch (RuntimeException failure) {
                observer.consumerFailed();
                log.warn("Потребитель ParametersChanged отказал: keys={}, причина {}", event.getKeys(),
                        rootCause(failure).getClass().getName());
            }
        }
    }

    static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        Throwable next = cause.getCause();
        while (next != null && !next.equals(cause)) {
            cause = next;
            next = cause.getCause();
        }
        return cause;
    }
}
