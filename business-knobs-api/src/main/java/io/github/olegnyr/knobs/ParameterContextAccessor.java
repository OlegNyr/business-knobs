package io.github.olegnyr.knobs;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Путь обращения {@link ParameterContext} для Micrometer context-propagation.
 *
 * <p>Context-propagation переносит между потоками только те {@code ThreadLocal}, о которых ему
 * сказали; этот accessor говорит о пути обращения. Регистрируется сам — файлом
 * {@code META-INF/services/io.micrometer.context.ThreadLocalAccessor}, как accessor трассы и
 * MDC, — и путь, открытый до снимка ({@code ContextSnapshotFactory#captureAll}), едет туда же,
 * куда трасса: на рабочий поток очереди обращения, в Reactor с автоматическим переносом, в
 * исполнитель с {@code ContextPropagatingTaskDecorator}. Зависимость необязательная: без неё
 * файл ServiceLoader'а никто не читает.
 */
public final class ParameterContextAccessor implements ThreadLocalAccessor<ContextPath> {

    /**
     * Ключ в {@code ContextRegistry} и в контексте Reactor.
     */
    public static final String KEY = "knobs.parameters.context";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public ContextPath getValue() {
        return ParameterContext.current().orElse(null);
    }

    @Override
    public void setValue(ContextPath path) {
        ParameterContext.set(path);
    }

    @Override
    public void setValue() {
        ParameterContext.set(null);
    }
}
