package io.github.olegnyr.knobs.boot.group.other;

import io.github.olegnyr.knobs.Knob;

import java.time.Duration;

/**
 * Группа с тем же простым именем, что и {@code group.SampleGroup}, во вложенном пакете: скан
 * пакета {@code group} находит обе.
 */
@Knob("app.sample")
public interface SampleGroup {

    Duration readTimeout();
}
