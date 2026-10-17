package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.ParameterTypeMismatchException;
import io.github.olegnyr.knobs.impl.ParameterStore;
import io.github.olegnyr.knobs.impl.group.ParameterReader;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionException;

import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Читатель группы параметров через {@code Binder} Spring: конвертация
 * типов, списки с generics и relaxed-имена — от Spring; источник — <em>только</em> слой параметров.
 * Переменные окружения и {@code application.yaml} сюда не попадают намеренно: значение по
 * умолчанию живёт в {@code knobs.yaml}, и ключ каталога без
 * значения — пустой ответ, а не значение стенда.
 */
final class BinderParameterReader implements ParameterReader {

    private final Binder binder;

    /**
     * Читатель над снимком пода.
     *
     * @param store снимок пода
     */
    BinderParameterReader(ParameterStore store) {
        this.binder = new Binder(
                ConfigurationPropertySources.from(new ParameterPropertySource(store)),
                null,
                ApplicationConversionService.getSharedInstance());
    }

    @Override
    public Optional<Object> read(String key, Type type) {
        try {
            Bindable<Object> target = Bindable.of(ResolvableType.forType(type));
            Object value = binder.bind(key, target)
                    .orElse(null);
            return Optional.ofNullable(value);
        } catch (BindException | ConversionException e) {
            throw new ParameterTypeMismatchException(key, type.getTypeName(),
                    NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName());
        }
    }
}
