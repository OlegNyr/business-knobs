package io.github.olegnyr.knobs.impl;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterType;
import io.github.olegnyr.knobs.impl.evaluation.ContextDimensions;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Провайдер OpenFeature над параметрами: значение для пути пода, пути
 * обращения текущего потока и измерений явного контекста вызова (атрибуты с именами измерений
 * пути — строками; явный сильнее потока). Нет значения —
 * {@code FLAG_NOT_FOUND}, не тот тип — {@code TYPE_MISMATCH}; в обоих случаях клиент отдаёт
 * значение по умолчанию вызывающего.
 */
class ParameterFeatureProvider implements FeatureProvider {

    private final ParameterClient parameters;

    ParameterFeatureProvider(ParameterClient parameters) {
        this.parameters = parameters;
    }

    @Override
    public Metadata getMetadata() {
        return () -> ParameterClient.DOMAIN;
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue,
                                                            EvaluationContext context) {
        return evaluate(key, defaultValue, context, typed -> scalar(typed, ParameterType.BOOLEAN)
                ? Optional.of((Boolean) typed.value()) : Optional.empty());
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue,
                                                          EvaluationContext context) {
        return evaluate(key, defaultValue, context, typed -> typed.parameter().isList()
                || typed.parameter().getType() == ParameterType.BOOLEAN
                ? Optional.empty() : Optional.of(String.valueOf(typed.value())));
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue,
                                                            EvaluationContext context) {
        return evaluate(key, defaultValue, context, typed -> {
            if (!scalar(typed, ParameterType.INTEGER)) {
                return Optional.empty();
            }
            long value = (Long) typed.value();
            // Вне диапазона int — не тот тип для вызывающего, а не исключение.
            return value == (int) value ? Optional.of((int) value) : Optional.empty();
        });
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue,
                                                          EvaluationContext context) {
        return evaluate(key, defaultValue, context, typed -> {
            if (scalar(typed, ParameterType.DOUBLE)) {
                return Optional.of((Double) typed.value());
            }
            return scalar(typed, ParameterType.INTEGER)
                    ? Optional.of(((Long) typed.value()).doubleValue()) : Optional.empty();
        });
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue,
                                                         EvaluationContext context) {
        return evaluate(key, defaultValue, context, typed -> typed.parameter().isList()
                ? Optional.of(new Value(((List<?>) typed.value()).stream()
                .map(element -> new Value(String.valueOf(element))).toList()))
                : Optional.empty());
    }

    /**
     * Измерения пути обращения из явного контекста вызова: он сильнее контекста потока.
     */
    private static ContextPath path(EvaluationContext context, ContextDimensions dimensions) {
        ContextPath.Builder path = ContextPath.builder();
        if (context != null) {
            for (String dimension : dimensions.order()) {
                Value value = context.getValue(dimension);
                if (value != null && value.isString()) {
                    path.put(dimension, value.asString());
                }
            }
        }
        return path.build();
    }

    private static boolean scalar(ParameterLookup.Typed typed, ParameterType type) {
        return !typed.parameter().isList() && typed.parameter().getType() == type;
    }

    private <T> ProviderEvaluation<T> evaluate(String key, T defaultValue, EvaluationContext context,
                                               Function<ParameterLookup.Typed, Optional<T>> convert) {
        Optional<ParameterLookup.Typed> typed = parameters.lookup(key, path(context, parameters.dimensions()));
        if (typed.isEmpty()) {
            return error(defaultValue, ErrorCode.FLAG_NOT_FOUND);
        }
        Optional<T> value = convert.apply(typed.get());
        if (value.isEmpty()) {
            return error(defaultValue, ErrorCode.TYPE_MISMATCH);
        }
        return ProviderEvaluation.<T>builder()
                .value(value.get())
                .reason(Reason.TARGETING_MATCH.toString())
                .build();
    }

    private static <T> ProviderEvaluation<T> error(T defaultValue, ErrorCode code) {
        return ProviderEvaluation.<T>builder()
                .value(defaultValue)
                .errorCode(code)
                .reason(Reason.ERROR.toString())
                .build();
    }
}
