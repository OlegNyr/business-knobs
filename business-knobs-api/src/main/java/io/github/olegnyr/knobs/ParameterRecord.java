package io.github.olegnyr.knobs;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.List;

/**
 * Слепок параметра из админки с версией: тело записи топика {@code knobs.parameters.v1}
 * и строка реплики сервиса. Описательные поля записи (название, ограничения ввода) сервису не
 * нужны — их несёт каталог файла, и при чтении записи они пропускаются.
 */
@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = ParameterRecord.ParameterRecordBuilder.class)
public class ParameterRecord {

    /**
     * Ключ параметра — он же ключ записи топика.
     */
    String key;

    /**
     * Тип значения; у записи удаления может отсутствовать.
     */
    ParameterType type;

    /**
     * Значение — список значений типа {@link #type}.
     */
    boolean list;

    /**
     * Значение параметра; {@code null} — значения из админки нет.
     */
    JsonNode value;

    /**
     * Правила из админки; пусто — значение одно на все пути.
     */
    @Singular
    List<ParameterRule> rules;

    /**
     * Версия — время правки в админке, мс; растёт при любой правке.
     */
    long version;

    /**
     * Параметр удалён в админке: действует значение из файла.
     */
    boolean deleted;

    /**
     * Значение из админки — копия: снимок общий для всех потоков пода, и правка снаружи его бы испортила.
     *
     * @return копия значения либо {@code null}
     */
    public JsonNode getValue() {
        return value == null ? null : value.deepCopy();
    }

    /**
     * Билдер, который Jackson вызывает без префикса {@code with}.
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static class ParameterRecordBuilder {
    }
}
