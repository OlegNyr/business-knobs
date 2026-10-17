package io.github.olegnyr.knobs.impl.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.List;

/**
 * Объявление измерения в файле стенда. Админке — откуда брать значения при вводе правила;
 * библиотеке — какие измерения сравниваются в пути и в каком порядке: порядок объявлений без
 * {@code service} и есть порядок точности.
 */
@Value
@Builder
@JsonDeserialize(builder = DimensionDeclaration.DimensionDeclarationBuilder.class)
public class DimensionDeclaration {

    /**
     * Ключ измерения: {@code service}, {@code dc}, {@code tenant}, {@code segment}, …
     */
    String key;

    /**
     * Откуда берутся значения измерения.
     */
    DimensionKind kind;

    /**
     * Значения измерения, заданного кодом; у остальных видов пусто.
     */
    @Singular
    List<String> values;

    /**
     * Билдер, который Jackson вызывает без префикса {@code with}.
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static class DimensionDeclarationBuilder {
    }
}
