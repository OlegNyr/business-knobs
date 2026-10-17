package io.github.olegnyr.knobs;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Тип значения параметра. Закрытый словарь: тип {@code OBJECT} отложен.
 *
 * <p>{@code INTEGER} — целое 64 бит ({@link Long}): генератор выводит тип из любого целого, и
 * размер в байтах больше {@code int} не должен ломать чтение.
 *
 * <p>Длительность и дата в файле стенда, в топике и в реплике записаны строкой ISO-8601
 * ({@code PT10S}, {@code 2026-11-01}); здесь они превращаются в {@link Duration} и
 * {@link LocalDate}.
 */
public enum ParameterType {

    BOOLEAN {
        @Override
        Object readScalar(JsonNode node) {
            return node.booleanValue();
        }
    },
    STRING {
        @Override
        Object readScalar(JsonNode node) {
            return node.stringValue();
        }
    },
    INTEGER {
        @Override
        Object readScalar(JsonNode node) {
            if (!node.isIntegralNumber()) {
                // 5.0 и 1e3 — не целое: в свойство ушло бы «5.0», и связывание с int упало бы.
                throw new IllegalArgumentException("значение не целое");
            }
            return node.longValue();
        }
    },
    DOUBLE {
        @Override
        Object readScalar(JsonNode node) {
            return node.doubleValue();
        }
    },
    DURATION {
        @Override
        Object readScalar(JsonNode node) {
            return Duration.parse(node.stringValue());
        }
    },
    DATE {
        @Override
        Object readScalar(JsonNode node) {
            return LocalDate.parse(node.stringValue());
        }
    };

    /**
     * Значение в типе Java: скаляр либо список скаляров.
     *
     * @param node значение из файла стенда, записи топика или правила
     * @return значение; {@code null}, если значения нет
     */
    public Object read(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(element -> values.add(readScalar(element)));
            return List.copyOf(values);
        }
        return readScalar(node);
    }

    abstract Object readScalar(JsonNode node);
}
