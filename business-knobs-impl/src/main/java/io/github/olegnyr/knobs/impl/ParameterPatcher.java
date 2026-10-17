package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.impl.model.ParameterPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Аварийная правка параметров пода — без актуатора и без Spring;
 * точка {@code /actuator/parameters} — её Spring-обёртка.
 *
 * <p>Правка ставится в память этого пода, заменяет строку ключа целиком и снимается рестартом
 * либо применённой записью топика с версией позже момента установки. Битая заплатка не
 * применяется вовсе: ключи и причины уходят в ответ. В поток аудита {@value #AUDIT_LOGGER} —
 * заявленный автор, субъект вызова, причина и ключи, значений нет; свободный текст — в
 * одну строку, чтобы перевод строки не подделал запись аудита.
 */
public final class ParameterPatcher {

    /**
     * Поток аудита сервиса — отдельный логгер, чтобы сервис мог направить его в свой журнал аудита.
     * Намеренно вне пакета библиотеки: уровень, которым сервис приглушает
     * {@code io.github.olegnyr.knobs}, аудит правок не глушит.
     */
    public static final String AUDIT_LOGGER = "knobs.audit";

    private static final Logger AUDIT = LoggerFactory.getLogger(AUDIT_LOGGER);

    private final ParameterStore store;

    private final ParameterRecordApplierImpl applier;

    private final Clock clock;

    /**
     * Аварийная правка.
     *
     * @param store   снимок пода; без файла стенда — пустой, и любой ключ правки вне каталога
     * @param applier применитель — единственный писатель снимка
     * @param clock   часы момента установки
     */
    public ParameterPatcher(ParameterStore store, ParameterRecordApplierImpl applier, Clock clock) {
        this.store = store;
        this.applier = applier;
        this.clock = clock;
    }

    /**
     * Ставит заплатку целиком либо не ставит вовсе.
     *
     * @param author  заявленный автор
     * @param subject кто вызвал: субъект токена, учётная запись процесса
     * @param reason  зачем
     * @param text    текст заплатки — YAML в формате параметра
     * @return ключи поставленных правок либо ошибки по ключам
     */
    public Result apply(String author, String subject, String reason, String text) {
        if (isBlank(author) || isBlank(reason) || isBlank(text)) {
            return Result.rejected(Map.of("request", "нужны author, reason и patch"));
        }
        ParameterPatchReader.Result read = ParameterPatchReader.read(text, store.parameters(), author, reason,
                Instant.now(clock));
        if (!read.errors().isEmpty()) {
            return Result.rejected(read.errors());
        }
        applier.applyPatches(read.patches());
        List<String> keys = read.patches().stream().map(ParameterPatch::getKey).toList();
        AUDIT.info("Аварийная правка параметров: author={}, subject={}, reason={}, keys={}", oneLine(author),
                oneLine(subject), oneLine(reason), keys);
        return new Result(keys, Map.of());
    }

    /**
     * Действующие правки пода.
     *
     * @return правки
     */
    public List<ParameterPatch> patches() {
        return List.copyOf(store.parameters().patches());
    }

    private static String oneLine(String text) {
        return text == null ? null : text.replaceAll("[\\r\\n\\u2028\\u2029]+", " ");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Итог правки.
     *
     * @param keys   ключи поставленных правок; при отказе пусто
     * @param errors ключ → причина; при успехе пусто
     */
    public record Result(List<String> keys, Map<String, String> errors) {

        static Result rejected(Map<String, String> errors) {
            return new Result(List.of(), errors);
        }
    }
}
