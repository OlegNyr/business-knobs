package io.github.olegnyr.knobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Путь контекста — значение: равные части дают равные пути. */
@DisplayName("Путь контекста — значение")
class ContextPathTest {

    @Test
    @DisplayName("Пути с теми же частями равны и дают тот же hashCode, порядок put не важен")
    void equals_shouldCompareParts() {
        ContextPath first = ContextPath.builder().put("dc", "dc2").put("segment", "VIP").build();
        ContextPath second = ContextPath.builder().put("segment", "VIP").put("dc", "dc2").build();

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(ContextPath.builder().put("dc", "dc2").build());
    }
}
