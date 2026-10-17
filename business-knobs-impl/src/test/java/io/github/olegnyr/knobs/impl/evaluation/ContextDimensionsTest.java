package io.github.olegnyr.knobs.impl.evaluation;

import io.github.olegnyr.knobs.ContextPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Порядок измерений пути задаёт сервис: библиотека своих измерений, кроме {@code dc}, не знает.
 */
@DisplayName("Измерения пути: порядок задаёт сервис")
class ContextDimensionsTest {

    @Test
    @DisplayName("По умолчанию измерение одно — ЦОД пода")
    void defaults_shouldContainOnlyDc() {
        ContextDimensions dimensions = ContextDimensions.DEFAULT;

        assertThat(dimensions.order()).containsExactly(ContextPath.DC);
    }

    @Test
    @DisplayName("Порядок сохраняется как задан")
    void of_shouldKeepOrder() {
        ContextDimensions dimensions = ContextDimensions.of("dc", "tenant", "segment");

        assertThat(dimensions.order()).containsExactly("dc", "tenant", "segment");
    }

    @Test
    @DisplayName("Известны заданные измерения и service; прочие — нет")
    void isKnown_shouldAcceptServiceAndDeclaredDimensions() {
        ContextDimensions dimensions = ContextDimensions.of("dc", "segment");

        assertThat(List.of("service", "dc", "segment", "region"))
                .map(dimensions::isKnown)
                .containsExactly(true, true, true, false);
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of("пустой список", new String[0], "пуст"),
                Arguments.of("пустое имя", new String[] {"dc", " "}, "пустое"),
                Arguments.of("повтор", new String[] {"dc", "segment", "dc"}, "повторяется: dc"),
                Arguments.of("service", new String[] {"dc", "service"}, "service"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    @DisplayName("Негодный порядок отбивается при сборке и называет причину")
    void of_shouldReject_whenOrderIsInvalid(String caseName, String[] order, String reason) {
        Throwable thrown = catchThrowable(() -> ContextDimensions.of(Arrays.asList(order)));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(reason);
    }

    @Test
    @DisplayName("Список измерений неизменяем снаружи")
    void of_shouldCopyInput() {
        List<String> source = new ArrayList<>(List.of("dc", "segment"));
        ContextDimensions dimensions = ContextDimensions.of(source);

        source.add("tenant");

        assertThat(dimensions.order()).containsExactly("dc", "segment");
    }
}
