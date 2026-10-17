package io.github.olegnyr.knobs.boot.group;

import io.github.olegnyr.knobs.Knob;
import io.github.olegnyr.knobs.KnobKey;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Группа параметров тестового пакета — её находит скан {@code @EnableKnobs}.
 */
@Knob("app.sample")
public interface SampleGroup {

    Duration readTimeout();

    String waitNotice();

    Optional<String> tokenName();

    List<String> extensions();

    List<String> blocked();

    @KnobKey("big-number")
    long bigNumber();

    @KnobKey("read-timeout")
    int readTimeoutAsInt();

    default boolean slow() {
        return readTimeout().compareTo(Duration.ofSeconds(10)) > 0;
    }
}
