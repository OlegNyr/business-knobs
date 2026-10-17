package io.github.olegnyr.knobs.impl.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.List;

/**
 * Файл параметров, сгенерированный сборкой для одного стенда: ключ записан целиком,
 * значение и правила уже выбраны для стенда. Формат — контракт
 * {@code doc/contract/stand-file.adoc}.
 */
@Value
@Builder
@JsonDeserialize(builder = StandFile.StandFileBuilder.class)
public class StandFile {

    /**
     * Сервис, для которого собран файл; его ключи начинаются с этого префикса.
     */
    String service;

    /**
     * Версия сборки, из исходного файла которой получен этот файл.
     */
    String version;

    /**
     * Стенд: {@code dev}, {@code test}, {@code prod}.
     */
    String stand;

    /**
     * Измерения, по которым админка строит правила; их порядок без {@code service} — порядок
     * точности пути при выборе значения.
     */
    @Singular
    List<DimensionDeclaration> dimensions;

    /**
     * Параметры сервиса со значениями этого стенда.
     */
    @Singular
    List<ParameterDefinition> parameters;

    /**
     * Билдер, который Jackson вызывает без префикса {@code with}.
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static class StandFileBuilder {
    }
}
