package io.github.olegnyr.knobs.impl.snapshot;

import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterRule;
import io.github.olegnyr.knobs.impl.evaluation.ContextDimensions;
import io.github.olegnyr.knobs.impl.evaluation.RuleEvaluator;
import io.github.olegnyr.knobs.impl.format.ParameterFormat;
import io.github.olegnyr.knobs.impl.format.PlaceholderResolver;
import io.github.olegnyr.knobs.impl.model.ParameterDefinition;
import io.github.olegnyr.knobs.impl.model.ParameterPatch;
import io.github.olegnyr.knobs.impl.model.StandFile;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Действующие параметры пода: неизменяемый снимок, ключ → параметр.
 *
 * <p>Слои по ключу целиком, без смешения правил: аварийная правка, иначе строка реплики, иначе
 * строка файла. Каталог — ключи файла стенда: строка реплики с ключом вне
 * каталога либо с типом, расходящимся с каталогом, не действует. Удалённый в файле
 * параметр и параметр, у которого не разрешилась подстановка в
 * значении, в снимок не попадают — у них нет значения из этого источника. Правило с
 * неразрешённой подстановкой остаётся без значения и в снимок не попадает: правило чужого ЦОД
 * не должно снимать параметр со всех подов.
 *
 * <p>Источник выбирается при сборке снимка, а не при чтении: на каждую смену — запись топика,
 * реплика, правка — снимок собирается заново, и выбор по каждому ключу пишется в лог на уровне
 * TRACE ({@link #choose}). Правила каждого ключа там же готовятся к чтению ({@link #prepared}):
 * правило, которое не подошло бы ни к одному пути, отбрасывается, остальные встают по точности.
 * Чтение берёт готовое определение и его источник ({@link #source}).
 */
@Slf4j
public final class ParameterSet {

    private final ContextDimensions dimensions;

    private final Map<String, ParameterDefinition> catalog;

    private final Map<String, ParameterDefinition> file;

    private final Map<String, ParameterRecord> replica;

    private final Map<String, ParameterPatch> patches;

    private final Map<String, ParameterDefinition> effective;

    private final Map<String, Source> sources;

    private ParameterSet(ContextDimensions dimensions, Map<String, ParameterDefinition> catalog,
                         Map<String, ParameterDefinition> file, Map<String, ParameterRecord> replica,
                         Map<String, ParameterPatch> patches) {
        this.dimensions = dimensions;
        this.catalog = catalog;
        this.file = file;
        this.replica = replica;
        this.patches = patches;
        Map<String, ParameterDefinition> definitions = new LinkedHashMap<>();
        Map<String, Source> chosen = new LinkedHashMap<>();
        for (ParameterDefinition declared : catalog.values()) {
            choose(declared, file, replica, patches, dimensions).ifPresent(choice -> {
                definitions.put(declared.getKey(), choice.definition());
                chosen.put(declared.getKey(), choice.source());
            });
        }
        this.effective = Collections.unmodifiableMap(definitions);
        this.sources = Collections.unmodifiableMap(chosen);
    }

    /**
     * Слой, из которого взято действующее определение ключа.
     */
    public enum Source {

        /**
         * Аварийная правка на поде: значение и правила — из заплатки.
         */
        PATCH,

        /**
         * Строка реплики — запись из админки, прошедшая проверку по каталогу.
         */
        REPLICA,

        /**
         * Строка файла стенда из сборки.
         */
        FILE
    }

    /**
     * Пустой снимок: у сервиса нет файла стенда — параметров нет, любой ключ вне каталога.
     *
     * @param dimensions измерения пути в порядке точности
     * @return снимок без параметров
     */
    public static ParameterSet empty(ContextDimensions dimensions) {
        return new ParameterSet(dimensions, Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * Снимок из файла стенда.
     *
     * @param file        прочитанный файл стенда
     * @param dimensions  измерения пути в порядке точности
     * @param environment имя переменной окружения → её значение либо {@code null}
     * @param unresolved  ключ параметра и имена переменных, которые не разрешились; значения
     *                    переменных сюда не передаются
     * @return снимок с параметрами файла
     */
    public static ParameterSet fromStandFile(StandFile file, ContextDimensions dimensions,
                                             Function<String, String> environment,
                                             BiConsumer<String, Set<String>> unresolved) {
        Map<String, ParameterDefinition> catalog = new LinkedHashMap<>();
        Map<String, ParameterDefinition> parameters = new LinkedHashMap<>();
        for (ParameterDefinition parameter : file.getParameters()) {
            if (parameter.isDeleted()) {
                continue;
            }
            catalog.put(parameter.getKey(), parameter);
            Set<String> missing = new LinkedHashSet<>();
            Function<String, String> lookup = name -> {
                String value = environment.apply(name);
                if (value == null) {
                    missing.add(name);
                }
                return value;
            };
            resolve(parameter, lookup).ifPresent(resolved -> parameters.put(resolved.getKey(), resolved));
            if (!missing.isEmpty() || !parameters.containsKey(parameter.getKey())) {
                unresolved.accept(parameter.getKey(), Set.copyOf(missing));
            }
        }
        return new ParameterSet(dimensions, Collections.unmodifiableMap(catalog),
                Collections.unmodifiableMap(parameters), Map.of(), Map.of());
    }

    /**
     * Снимок с новым слоем реплики; прежний слой реплики заменяется целиком.
     *
     * @param records строки реплики
     * @return новый снимок
     */
    public ParameterSet withReplica(Collection<ParameterRecord> records) {
        Map<String, ParameterRecord> layer = new LinkedHashMap<>();
        for (ParameterRecord record : records) {
            if (catalog.containsKey(record.getKey())) {
                layer.put(record.getKey(), record);
            }
        }
        return new ParameterSet(dimensions, catalog, file, Collections.unmodifiableMap(layer), patches);
    }

    /**
     * Снимок, где строка реплики одного ключа заменена записью.
     *
     * @param record слепок из топика либо строка реплики
     * @return новый снимок; ключ вне каталога — тот же снимок
     */
    public ParameterSet withRecord(ParameterRecord record) {
        if (!catalog.containsKey(record.getKey())) {
            return this;
        }
        Map<String, ParameterRecord> layer = new LinkedHashMap<>(replica);
        layer.put(record.getKey(), record);
        Map<String, ParameterPatch> remaining = new LinkedHashMap<>(patches);
        ParameterPatch patch = remaining.get(record.getKey());
        Optional<ParameterRejection> rejection = rejection(record);
        if (patch != null && record.getVersion() > patch.getSetAt().toEpochMilli()
                && (rejection.isEmpty() || rejection.get() == ParameterRejection.DELETED)) {
            // Запись из админки новее правки снимает её, но только применённая либо удаление:
            // отбракованная запись правку не заменила бы, и в инциденте действовал бы файл
            //.
            remaining.remove(record.getKey());
        }
        return new ParameterSet(dimensions, catalog, file, Collections.unmodifiableMap(layer),
                Collections.unmodifiableMap(remaining));
    }

    /**
     * Снимок с аварийной правкой ключа; прежняя правка ключа заменяется.
     *
     * @param patch правка; ключ обязан быть в каталоге
     * @return новый снимок
     */
    public ParameterSet withPatch(ParameterPatch patch) {
        Map<String, ParameterPatch> layer = new LinkedHashMap<>(patches);
        layer.put(patch.getKey(), patch);
        return new ParameterSet(dimensions, catalog, file, replica, Collections.unmodifiableMap(layer));
    }

    /**
     * Измерения пути, по которым готовятся правила снимка.
     *
     * @return измерения в порядке точности
     */
    public ContextDimensions dimensions() {
        return dimensions;
    }

    /**
     * Действующие аварийные правки.
     *
     * @return правки в порядке установки
     */
    public Collection<ParameterPatch> patches() {
        return patches.values();
    }

    /**
     * Описание параметра из каталога — файла стенда.
     *
     * @param key ключ параметра
     * @return описание; пусто, если ключа нет в каталоге
     */
    public Optional<ParameterDefinition> declared(String key) {
        return Optional.ofNullable(catalog.get(key));
    }

    /**
     * Есть ли ключ в каталоге сервиса — ключах файла стенда.
     *
     * @param key ключ параметра
     * @return признак каталога
     */
    public boolean inCatalog(String key) {
        return catalog.containsKey(key);
    }

    /**
     * Почему строка из админки не действует для каталога сервиса.
     *
     * @param record слепок из топика либо строка реплики
     * @return причина; пусто — строка действует (либо ключа нет в каталоге)
     */
    public Optional<ParameterRejection> rejection(ParameterRecord record) {
        ParameterDefinition declared = catalog.get(record.getKey());
        return declared == null ? Optional.empty() : rejection(record, declared);
    }

    /**
     * Действующие параметры снимка.
     *
     * @return параметры в порядке каталога
     */
    public Collection<ParameterDefinition> parameters() {
        return effective.values();
    }

    /**
     * Действующий параметр по ключу.
     *
     * @param key ключ параметра
     * @return параметр; пусто, если значения нет ни в одном слое
     */
    public Optional<ParameterDefinition> find(String key) {
        return Optional.ofNullable(effective.get(key));
    }

    /**
     * Источник действующего определения ключа.
     *
     * @param key ключ параметра
     * @return источник; пусто, если ключа в снимке нет
     */
    public Optional<Source> source(String key) {
        return Optional.ofNullable(sources.get(key));
    }

    /**
     * Версия ключа в слое реплики.
     *
     * @param key ключ параметра
     * @return версия строки реплики, {@code 0} — строки нет
     */
    public long version(String key) {
        ParameterRecord record = replica.get(key);
        return record == null ? 0L : record.getVersion();
    }

    /**
     * Какой слой действует для ключа — по ключу целиком, без смешения правил: аварийная правка,
     * иначе строка реплики, если она проходит проверку по каталогу, иначе строка файла.
     *
     * @return определение и его источник; пусто — значения нет ни в одном слое
     */
    private static Optional<Choice> choose(ParameterDefinition declared, Map<String, ParameterDefinition> file,
            Map<String, ParameterRecord> replica, Map<String, ParameterPatch> patches,
            ContextDimensions dimensions) {
        String key = declared.getKey();
        ParameterPatch patch = patches.get(key);
        if (patch != null) {
            log.trace("Ключ {}: действует аварийная правка, поставлена {}", key, patch.getSetAt());
            return Optional.of(new Choice(prepared(replaced(declared, patch.getValue(), patch.getRules()),
                    dimensions), Source.PATCH));
        }
        ParameterRecord record = replica.get(key);
        if (record != null) {
            Optional<ParameterRejection> rejection = rejection(record, declared);
            if (rejection.isEmpty()) {
                log.trace("Ключ {}: действует строка реплики версии {}", key, record.getVersion());
                return Optional.of(new Choice(prepared(replaced(declared, record.getValue(), record.getRules()),
                        dimensions), Source.REPLICA));
            }
            log.trace("Ключ {}: строка реплики версии {} не действует ({}) — дальше файл", key,
                    record.getVersion(), rejection.get());
        }
        ParameterDefinition fromFile = file.get(key);
        if (fromFile != null) {
            log.trace("Ключ {}: действует файл стенда", key);
            return Optional.of(new Choice(prepared(fromFile, dimensions), Source.FILE));
        }
        log.trace("Ключ {}: в файле стенда значения нет (параметр удалён либо подстановка не разрешилась) — "
                + "ключа в снимке нет", key);
        return Optional.empty();
    }

    /**
     * Определение с правилами, готовыми к чтению ({@link RuleEvaluator#prepared}): без тех, что не
     * подошли бы ни к одному пути, по точности — самое точное первым, чтобы чтение брало первое
     * подошедшее ({@link RuleEvaluator#evaluate}). Готовятся один раз, при сборке снимка, а не при
     * каждом чтении. Порядок пишется в лог на уровне TRACE.
     */
    private static ParameterDefinition prepared(ParameterDefinition definition, ContextDimensions dimensions) {
        if (definition.getRules().isEmpty()) {
            return definition;
        }
        List<ParameterRule> rules = RuleEvaluator.prepared(definition.getKey(), definition.getRules(), dimensions);
        if (rules.size() > 1 && log.isTraceEnabled()) {
            log.trace("Ключ {}: правила по точности — {}", definition.getKey(),
                    rules.stream().map(ParameterRule::getWhen).toList());
        }
        return definition.toBuilder()
                .clearRules()
                .rules(rules)
                .build();
    }

    /**
     * Определение каталога со значением и правилами слоя: строка ключа заменяется целиком.
     */
    private static ParameterDefinition replaced(ParameterDefinition declared, JsonNode value,
            List<ParameterRule> rules) {
        return declared.toBuilder()
                .value(value)
                .clearRules()
                .rules(rules)
                .build();
    }

    /**
     * Выбранное определение ключа и его источник.
     *
     * @param definition действующее определение
     * @param source     слой, из которого оно взято
     */
    private record Choice(ParameterDefinition definition, Source source) {
    }

    private static Optional<ParameterRejection> rejection(ParameterRecord record, ParameterDefinition declared) {
        if (record.isDeleted()) {
            return Optional.of(ParameterRejection.DELETED);
        }
        List<JsonNode> values = new ArrayList<>();
        values.add(record.getValue());
        record.getRules().forEach(rule -> values.add(rule.getValue()));
        if (values.stream().anyMatch(ParameterSet::hasPlaceholder)) {
            return Optional.of(ParameterRejection.PLACEHOLDER);
        }
        boolean readable = record.getType() == declared.getType() && record.isList() == declared.isList()
                && values.stream().allMatch(value -> readable(value, declared));
        return readable ? Optional.empty() : Optional.of(ParameterRejection.TYPE);
    }

    private static boolean hasPlaceholder(JsonNode value) {
        if (value == null) {
            return false;
        }
        if (value.isArray()) {
            for (JsonNode element : value) {
                if (hasPlaceholder(element)) {
                    return true;
                }
            }
            return false;
        }
        return value.isString() && value.stringValue().contains("${");
    }

    private static boolean readable(JsonNode value, ParameterDefinition declared) {
        return ParameterFormat.readable(value, declared.getType(), declared.isList());
    }

    private static Optional<ParameterDefinition> resolve(ParameterDefinition parameter,
                                                         Function<String, String> lookup) {
        JsonNode value = parameter.getValue();
        if (value != null) {
            Optional<JsonNode> resolved = PlaceholderResolver.resolve(value, lookup)
                    .flatMap(node -> ParameterFormat.coerce(node, parameter.getType(), parameter.isList()));
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            value = resolved.get();
        }
        List<ParameterRule> rules = new ArrayList<>();
        for (ParameterRule rule : parameter.getRules()) {
            // Подстановка, не давшая значения типа, — правило без значения: в выборе не участвует.
            JsonNode ruleValue = rule.getValue() == null
                    ? null
                    : PlaceholderResolver.resolve(rule.getValue(), lookup)
                    .flatMap(node -> ParameterFormat.coerce(node, parameter.getType(), parameter.isList()))
                    .orElse(null);
            rules.add(rule.toBuilder().value(ruleValue).build());
        }
        return Optional.of(parameter.toBuilder()
                .value(value)
                .clearRules()
                .rules(rules)
                .build());
    }
}
