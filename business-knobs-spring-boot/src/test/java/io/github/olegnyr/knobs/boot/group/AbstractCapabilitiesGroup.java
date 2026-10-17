package io.github.olegnyr.knobs.boot.group;

import io.github.olegnyr.knobs.Knob;

/**
 * Группа на абстрактном классе: скан регистрирует и её, подкласс строит ByteBuddy.
 */
@Knob("app.capabilities")
public abstract class AbstractCapabilitiesGroup {

    public abstract int maxFiles();

    public abstract boolean stickers();

    public int maxFilesWithCover() {
        return maxFiles() + 1;
    }
}
