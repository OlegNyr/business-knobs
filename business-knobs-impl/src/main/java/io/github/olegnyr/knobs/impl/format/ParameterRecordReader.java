package io.github.olegnyr.knobs.impl.format;

import io.github.olegnyr.knobs.ParameterRecord;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Чтение записи топика и файла стенда с проверкой формата.
 *
 * <p>Свой {@link JsonMapper}, а не общий {@code ObjectMapperBuilder} приложения: тот подключает
 * модуль JSON-P и требует его реализацию в рантайме, а библиотека готовится к выносу и не должна
 * навязывать сервису лишнюю зависимость. Незнакомые поля пропускаются — так контракт может расти;
 * {@code null} у признака читается как его отсутствие.
 */
public final class ParameterRecordReader {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private ParameterRecordReader() {
    }

    /**
     * Слепок параметра из тела записи топика. Описательные поля записи пропускаются.
     *
     * @param body тело записи
     * @return слепок
     * @throws ParameterFormatException запись нарушает формат
     */
    public static ParameterRecord read(JsonNode body) {
        return ParameterFormat.check(MAPPER.treeToValue(body, ParameterRecord.class));
    }
}
