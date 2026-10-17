package io.github.olegnyr.knobs.impl.group;

import dev.openfeature.sdk.Client;
import io.github.olegnyr.knobs.Knob;
import io.github.olegnyr.knobs.ParameterTypeMismatchException;
import io.github.olegnyr.knobs.Parameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Группа без Spring приводит значения к типу метода так же, как {@code Binder} Spring: по элементам
 * списка, в {@code Set}, в строку. Несовместимое — {@link ParameterTypeMismatchException}, а не
 * {@code ClassCastException} у вызывающего.
 */
@DisplayName("Группа без Spring: значения приводятся к типу метода, как в Spring")
class ParametersReaderCoercionTest {

    @Knob("app")
    interface Network {

        List<Integer> ports();

        Set<String> hosts();

        String port();

        List<Long> sizes();

        List<Integer> big();
    }

    private final Network network = ParameterGroups.create(Network.class, new ParametersReader(new FixedParameters(
            Map.of("app.ports", List.of(80L, 443L),
                    "app.hosts", List.of("a", "b", "a"),
                    "app.port", 8080L,
                    "app.sizes", List.of(1L, 2L),
                    "app.big", List.of(1L, 1L << 40)))));

    @Test
    @DisplayName("List<Integer> над списком целых — элементы Integer")
    void list_shouldNarrowElements() {
        int first = network.ports().get(0);

        assertThat(first).isEqualTo(80);
    }

    @Test
    @DisplayName("Set<String> над списком — множество без повторов")
    void set_shouldCollectDistinct() {
        assertThat(network.hosts()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("String над целым — запись числа, как отдаёт Spring")
    void string_shouldTakeScalarText() {
        assertThat(network.port()).isEqualTo("8080");
    }

    @Test
    @DisplayName("List<Long> над списком целых — как есть")
    void list_shouldKeepLong() {
        assertThat(network.sizes()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("Элемент не влезает в int — ParameterTypeMismatchException с ключом")
    void list_shouldFail_whenElementDoesNotFit() {
        Throwable thrown = catchThrowable(network::big);

        assertThat(thrown).isInstanceOf(ParameterTypeMismatchException.class).hasMessageContaining("app.big");
    }

    /** Чтение параметров над картой ключ → значение в типе Java. */
    private record FixedParameters(Map<String, Object> values) implements Parameters {

        @Override
        public Optional<Object> value(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Optional<Duration> duration(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<LocalDate> date(String key) {
            return Optional.empty();
        }

        @Override
        public Map<String, Object> byPrefix(String prefix) {
            return Map.of();
        }

        @Override
        public Client client() {
            throw new UnsupportedOperationException();
        }
    }
}
