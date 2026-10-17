package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.impl.evaluation.ContextDimensions;

/**
 * Измерения пути в тестах — в том порядке, в каком их задал бы сервис: ЦОД, арендатор, сегмент
 * клиента, канал.
 */
public final class TestDimensions {

    /**
     * Все четыре измерения тестов.
     */
    public static final ContextDimensions ALL = ContextDimensions.of("dc", "tenant", "segment", "channel");

    private TestDimensions() {
    }
}
