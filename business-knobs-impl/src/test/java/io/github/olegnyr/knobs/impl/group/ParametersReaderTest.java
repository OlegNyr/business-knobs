package io.github.olegnyr.knobs.impl.group;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.Knob;
import io.github.olegnyr.knobs.KnobKey;
import io.github.olegnyr.knobs.ParameterContext;
import io.github.olegnyr.knobs.ParameterTypeMismatchException;
import io.github.olegnyr.knobs.impl.Knobs;
import io.github.olegnyr.knobs.impl.TestDimensions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Группа параметров без Spring: {@code Knobs#group} читает через {@code Parameters} и приводит
 * значение к типу метода сам.
 */
@DisplayName("Группа параметров без Spring — над Parameters")
class ParametersReaderTest {

    private final Knobs settings = Knobs.builder()
            .service("orders")
            .stand("prod")
            .dc("dc2")
            .dimensions(TestDimensions.ALL)
            .environment(name -> "SAMPLE_HOST".equals(name) ? "crm.dc2.example" : null)
            .build();

    @Knob("app.sample")
    interface Sample {

        Duration readTimeout();

        String waitNotice();

        Optional<String> tokenName();

        List<String> extensions();

        List<String> blocked();

        @KnobKey("big-number")
        long bigNumber();

        @KnobKey("read-timeout")
        int readTimeoutAsInt();
    }

    @Knob("app.capabilities")
    interface Capabilities {

        int maxFiles();

        boolean stickers();
    }

    @AfterEach
    void tearDown() {
        settings.close();
    }

    @Test
    @DisplayName("Длительность по правилу ЦОД пода — Duration без приведения")
    void readTimeout_shouldReturnDuration_byPodDcRule() {
        assertThat(settings.group(Sample.class).readTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("Целое INTEGER читается в int и long")
    void integers_shouldNarrowAndWiden() {
        assertThat(settings.group(Capabilities.class).maxFiles()).isEqualTo(5);
        assertThat(settings.group(Sample.class).bigNumber()).isEqualTo(5_000_000_000L);
    }

    @Test
    @DisplayName("Список — неизменяемый List с элементами как есть")
    void extensions_shouldReturnList() {
        assertThat(settings.group(Sample.class).extensions()).containsExactly("jpg", "a,b");
        assertThat(settings.group(Sample.class).blocked()).isEmpty();
    }

    @Test
    @DisplayName("Неразрешённая подстановка — значения нет — Optional пуст")
    void tokenName_shouldBeEmpty_whenPlaceholderUnresolved() {
        assertThat(settings.group(Sample.class).tokenName()).isEmpty();
    }

    @Test
    @DisplayName("Контекст обращения на потоке меняет ответ геттера")
    void waitNotice_shouldFollowThreadContext() {
        Sample sample = settings.group(Sample.class);

        String plain = sample.waitNotice();
        String vip;
        try (ParameterContext.Scope ignored = ParameterContext.open(
                ContextPath.builder().put("segment", "VIP").build())) {
            vip = sample.waitNotice();
        }

        assertThat(plain).isEqualTo("обычный");
        assertThat(vip).isEqualTo("vip");
    }

    @Test
    @DisplayName("Тип метода не совпал с типом параметра — ParameterTypeMismatchException с ключом и типами")
    void readTimeoutAsInt_shouldThrowTypeMismatch() {
        Throwable thrown = catchThrowable(() -> settings.group(Sample.class).readTimeoutAsInt());

        assertThat(thrown).isInstanceOf(ParameterTypeMismatchException.class)
                .hasMessageContaining("app.sample.read-timeout")
                .hasMessageContaining("Integer")
                .hasMessageContaining("Duration");
    }

    @Test
    @DisplayName("Boolean с правилом сегмента — без контекста значение параметра")
    void stickers_shouldReturnParameterValue_withoutContext() {
        assertThat(settings.group(Capabilities.class).stickers()).isFalse();
    }
}
