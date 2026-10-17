package io.github.olegnyr.knobs;

import lombok.Value;

import java.util.Set;

/**
 * Действующие значения параметров сменились.
 *
 * <p>Несёт ключи одной применённой пачки. Как применить смену — перечитать свойство,
 * пересоздать клиента, ничего не делать до рестарта — решает потребитель: параметр сам не
 * знает, требует ли он рестарта.
 */
@Value
public class ParametersChanged {

    /**
     * Ключи параметров, чьё действующее значение для пода сменилось.
     */
    Set<String> keys;

    /**
     * Событие о смене.
     *
     * @param keys ключи пачки
     */
    public ParametersChanged(Set<String> keys) {
        this.keys = Set.copyOf(keys);
    }
}
