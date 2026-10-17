package io.github.olegnyr.knobs;

import lombok.Value;

import java.util.Optional;

/**
 * Путь контекста обращения на текущем потоке.
 *
 * <p>Заполняет приложение на входе — фильтр HTTP, перехватчик брокера: те измерения пути, что ему
 * известны (например, арендатор, сегмент клиента, канал). ЦОД и сервис берутся из пути пода и отсюда не
 * переопределяются. Контекст живёт на потоке: чтение в другом потоке видит только путь пода.
 * Между потоками путь переносит Micrometer context-propagation вместе с трассой
 * ({@link ParameterContextAccessor}), если сервис снимает снимок там, где путь уже открыт.
 *
 * <pre>{@code
 * try (ParameterContext.Scope scope = ParameterContext.open(path)) {
 *     ... // чтение параметров видит путь обращения
 * }
 * }</pre>
 */
public final class ParameterContext {

    private static final ThreadLocal<ContextPath> CURRENT = new ThreadLocal<>();

    private ParameterContext() {
    }

    /**
     * Открывает путь обращения на текущем потоке до закрытия области.
     *
     * @param path путь обращения
     * @return область; закрытие возвращает прежний путь потока
     */
    public static Scope open(ContextPath path) {
        ContextPath previous = CURRENT.get();
        CURRENT.set(path);
        return new Scope(previous);
    }

    /**
     * Путь обращения текущего потока.
     *
     * @return путь; пусто, если на потоке он не открыт
     */
    public static Optional<ContextPath> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Кладёт путь на поток без области — только для {@link ParameterContextAccessor}: восстановление
     * прежнего пути там делает сам context-propagation.
     *
     * @param path путь; {@code null} — снять
     */
    static void set(ContextPath path) {
        if (path == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(path);
        }
    }

    /**
     * Область пути обращения: закрытие возвращает прежний путь потока.
     */
    @Value
    public static class Scope implements AutoCloseable {

        ContextPath previous;

        private Scope(ContextPath previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
