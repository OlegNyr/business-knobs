package io.github.olegnyr.knobs.boot.group;

import io.github.olegnyr.knobs.Knob;

/**
 * Вторая группа того же пакета: регистрируются все найденные интерфейсы.
 */
@Knob("app.capabilities")
public interface CapabilitiesGroup {

    int maxFiles();

    boolean stickers();
}
