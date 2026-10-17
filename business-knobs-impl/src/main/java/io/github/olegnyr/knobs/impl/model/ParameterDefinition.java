package io.github.olegnyr.knobs.impl.model;

import io.github.olegnyr.knobs.ParameterRule;
import io.github.olegnyr.knobs.ParameterType;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.List;

/**
 * Параметр в файле стенда и в записи топика: описание из каталога и состояние —
 * значение, правила, признак удаления.
 *
 * <p>Поля файла, нужные только админке, — {@code pattern} и {@code format}, — в модели пода нет:
 * под их не проверяет, и файл читается без них (незнакомое поле пропускается). Ограничения
 * {@code allowed}, {@code min}, {@code max} здесь есть: по ним под проверяет аварийную заплатку.
 *
 * <p>Билдер связан с Jackson явными аннотациями, а не интроспектором приложения: библиотека
 * обходится стандартным {@code JsonMapper}. Коллекции собирает
 * {@link Singular} — они неизменяемы, а явный {@code null} вместо списка отбраковывает чтение.
 */
@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = ParameterDefinition.ParameterDefinitionBuilder.class)
public class ParameterDefinition {

    /**
     * Ключ — имя свойства Spring через точки, с префиксом сервиса.
     */
    String key;

    /**
     * Название для человека — в админке и в выдаче точки параметров.
     */
    String name;

    /**
     * Что параметр меняет и чем грозит правка; необязательно.
     */
    String description;

    /**
     * Тип значения; от него зависит разбор и сравнение значений.
     */
    ParameterType type;

    /**
     * Значение — список значений типа {@link #type}.
     */
    boolean list;

    /**
     * Выбор из списка: допустимые значения; пусто — ограничения нет. Проверяет ручной ввод: админка
     * и аварийная заплатка на поде.
     */
    @Singular("allowedValue")
    List<JsonNode> allowed;

    /**
     * Нижняя граница в записи своего типа. Проверяет ручной ввод: админка и аварийная заплатка.
     */
    JsonNode min;

    /**
     * Верхняя граница в записи своего типа. Проверяет ручной ввод: админка и аварийная заплатка.
     */
    JsonNode max;

    /**
     * Значение параметра — действует, когда не подошло ни одно правило.
     */
    JsonNode value;

    /**
     * Правила стенда; выбирается самое точное подошедшее, иначе действует {@link #value}.
     */
    @Singular
    List<ParameterRule> rules;

    /**
     * Параметр логически удалён: значения из этого источника нет.
     */
    boolean deleted;

    /**
     * Значение параметра — копия: снимок общий для всех потоков пода, и правка снаружи его бы испортила.
     *
     * @return копия значения либо {@code null}
     */
    public JsonNode getValue() {
        return value == null ? null : value.deepCopy();
    }

    /**
     * Нижняя граница — копия: снимок общий для всех потоков пода, и правка снаружи его бы испортила.
     *
     * @return копия значения либо {@code null}
     */
    public JsonNode getMin() {
        return min == null ? null : min.deepCopy();
    }

    /**
     * Верхняя граница — копия: снимок общий для всех потоков пода, и правка снаружи его бы испортила.
     *
     * @return копия значения либо {@code null}
     */
    public JsonNode getMax() {
        return max == null ? null : max.deepCopy();
    }

    /**
     * Допустимые значения — копии: снимок общий для всех потоков пода.
     *
     * @return неизменяемый список копий
     */
    public List<JsonNode> getAllowed() {
        return allowed.stream().map(JsonNode::deepCopy).toList();
    }

    /**
     * Билдер, который Jackson вызывает без префикса {@code with}.
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static class ParameterDefinitionBuilder {
    }
}
