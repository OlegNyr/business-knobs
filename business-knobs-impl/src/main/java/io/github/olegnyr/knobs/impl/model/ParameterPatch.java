package io.github.olegnyr.knobs.impl.model;

import io.github.olegnyr.knobs.ParameterRule;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Аварийная правка параметра. Живёт в памяти одного пода и заменяет
 * строку ключа целиком: значение и правила — из правки. Снимается рестартом либо записью топика
 * с версией позже момента установки.
 */
@Value
@Builder
public class ParameterPatch {

    /**
     * Ключ параметра, который правка перекрывает.
     */
    String key;

    /**
     * Значение правки; {@code null} — значение даёт только подошедшее правило, иначе его нет.
     */
    JsonNode value;

    /**
     * Правила правки; вместе с значением перекрывают параметр целиком, а не дополняют его.
     */
    @Singular
    List<ParameterRule> rules;

    /**
     * Кто поставил правку — в лог аудита.
     */
    String author;

    /**
     * Зачем — в лог аудита.
     */
    String reason;

    /**
     * Момент установки: запись топика с версией позже снимает правку.
     */
    Instant setAt;

    /**
     * Значение правки — копия: снимок общий для всех потоков пода.
     *
     * @return копия значения либо {@code null}
     */
    public JsonNode getValue() {
        return value == null ? null : value.deepCopy();
    }
}
