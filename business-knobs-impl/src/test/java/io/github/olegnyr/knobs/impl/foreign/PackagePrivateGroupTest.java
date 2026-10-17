package io.github.olegnyr.knobs.impl.foreign;

import io.github.olegnyr.knobs.Knob;
import io.github.olegnyr.knobs.impl.Knobs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Группа объявлена непубличным интерфейсом в пакете сервиса, а не в пакете библиотеки: так
 * пишут группу, которая нужна только своей фиче.
 */
@DisplayName("Непубличная группа в чужом пакете: default-методы исполняются")
class PackagePrivateGroupTest {

    @Knob("app.sample")
    interface Hidden {

        Duration readTimeout();

        default long doubledSeconds() {
            return readTimeout().getSeconds() * 2;
        }
    }

    @Test
    @DisplayName("default-метод непубличного интерфейса зовёт живой геттер")
    void defaultMethod_shouldRun_whenGroupIsPackagePrivate() {
        try (Knobs knobs = Knobs.builder().service("orders").stand("test").dc("dc2").build()) {
            Hidden hidden = knobs.group(Hidden.class);

            assertThat(hidden.doubledSeconds()).isEqualTo(30L);
        }
    }
}
