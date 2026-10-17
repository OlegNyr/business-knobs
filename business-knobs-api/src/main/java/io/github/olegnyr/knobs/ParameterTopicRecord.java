package io.github.olegnyr.knobs;

import lombok.Value;
import tools.jackson.databind.JsonNode;

/**
 * Запись топика {@code knobs.parameters.v1} как её отдаёт подписчик сервиса: ключ и тело.
 */
@Value
public class ParameterTopicRecord {

    /**
     * Ключ записи — ключ параметра.
     */
    String key;

    /**
     * Тело записи — слепок параметра с версией.
     */
    JsonNode body;
}
