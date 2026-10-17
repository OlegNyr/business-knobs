package io.github.olegnyr.knobs;


/**
 * Потребитель смены параметров без Spring. В Spring-сервисе то же
 * событие приходит в {@code @EventListener}; здесь — тому, кого передали сборщику.
 */
@FunctionalInterface
public interface ParameterListener {

    /**
     * Параметры сменились.
     *
     * @param event ключи одной применённой пачки
     */
    void parametersChanged(ParametersChanged event);
}
