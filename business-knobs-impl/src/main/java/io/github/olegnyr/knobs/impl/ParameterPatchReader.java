package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterRule;
import io.github.olegnyr.knobs.ParameterType;
import io.github.olegnyr.knobs.impl.evaluation.ContextDimensions;
import io.github.olegnyr.knobs.impl.format.Durations;
import io.github.olegnyr.knobs.impl.format.ParameterConstraints;
import io.github.olegnyr.knobs.impl.model.ParameterDefinition;
import io.github.olegnyr.knobs.impl.model.ParameterPatch;
import io.github.olegnyr.knobs.impl.snapshot.ParameterRejection;
import io.github.olegnyr.knobs.impl.snapshot.ParameterSet;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Разбор текста аварийной заплатки: YAML в формате параметра —
 * {@code ключ: {value: 5s, rules: [{when: {dc: dc2}, value: 7s}]}}. Длительность — как в
 * настройках Spring либо ISO-8601. Ключ вне каталога, значение не по типу, вне {@code allowed},
 * {@code min}, {@code max} каталога и текст с {@code ${...}} отбиваются с ключом и причиной; значение
 * в причину не попадает.
 *
 * <p>Скаляры разбираются по схеме YAML 1.2 core, а не 1.1, которую SnakeYAML держит по умолчанию:
 * там {@code off} — признак, {@code 010} — восьмеричное 8, {@code 2026-11-01} — метка
 * времени, и правка молча ставила бы другое значение. Здесь признак — только {@code true} и
 * {@code false}, число — десятичное без ведущего нуля, остальное — текст. Текст у строкового
 * параметра остаётся тем, что написано; у признака и числа такая запись отбивается с причиной.
 */
final class ParameterPatchReader {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Set<String> PATCH_FIELDS = Set.of("value", "rules");

    private static final Set<String> RULE_FIELDS = Set.of("when", "value");

    /** Признаки YAML 1.1, которых в 1.2 нет. */
    private static final Pattern YAML11_BOOLEAN = Pattern.compile("(?i)y|yes|n|no|on|off");

    /** Числа, которые YAML 1.1 читает не десятичными: ведущий ноль, 0x, 0o, 0b. */
    private static final Pattern NON_DECIMAL_NUMBER =
            Pattern.compile("[-+]?(?:0[0-9_]+(?:\\.[0-9]*)?|0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[bB][01_]+)");

    private ParameterPatchReader() {
    }

    /**
     * Результат разбора: правки либо ошибки по ключам.
     *
     * @param patches правки, если ошибок нет
     * @param errors  ключ → причина
     */
    record Result(List<ParameterPatch> patches, Map<String, String> errors) {
    }

    static Result read(String text, ParameterSet parameters, String author, String reason, Instant setAt) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<ParameterPatch> patches = new ArrayList<>();
        Object document;
        try {
            LoaderOptions options = new LoaderOptions();
            document = new Yaml(new SafeConstructor(options), new Representer(new DumperOptions()),
                    new DumperOptions(), options, new CoreSchemaResolver()).load(text);
        } catch (RuntimeException e) {
            return new Result(List.of(), Map.of("patch", "текст заплатки не разбирается как YAML"));
        }
        if (!(document instanceof Map<?, ?> keys) || keys.isEmpty()) {
            return new Result(List.of(), Map.of("patch", "заплатка — отображение «ключ → {value, rules}»"));
        }
        keys.forEach((rawKey, rawPatch) -> {
            String key = String.valueOf(rawKey);
            Optional<ParameterDefinition> declared = parameters.declared(key);
            if (declared.isEmpty()) {
                errors.put(key, "ключа нет в каталоге сервиса");
                return;
            }
            if (!(rawPatch instanceof Map<?, ?> fields)) {
                errors.put(key, "правка ключа — {value, rules}");
                return;
            }
            try {
                ParameterPatch patch = patch(key, fields, declared.get(), parameters.dimensions(), author, reason,
                        setAt);
                Optional<ParameterRejection> rejection = parameters.rejection(ParameterRecord.builder()
                        .key(key).type(declared.get().getType()).list(declared.get().isList())
                        .value(patch.getValue()).rules(patch.getRules()).version(1L).build());
                if (rejection.isPresent()) {
                    errors.put(key, rejection.get() == ParameterRejection.PLACEHOLDER
                            ? "подстановка ${} в правке запрещена"
                            : "значение не читается как " + declared.get().getType());
                    return;
                }
                Optional<String> violation = violation(patch, declared.get());
                if (violation.isPresent()) {
                    errors.put(key, violation.get());
                    return;
                }
                patches.add(patch);
            } catch (Invalid e) {
                errors.put(key, e.getMessage());
            } catch (RuntimeException e) {
                errors.put(key, "значение не читается как " + declared.get().getType());
            }
        });
        return errors.isEmpty() ? new Result(List.copyOf(patches), Map.of()) : new Result(List.of(), errors);
    }

    private static Optional<String> violation(ParameterPatch patch, ParameterDefinition declared) {
        Optional<String> violation = ParameterConstraints.violation(patch.getValue(), declared);
        for (ParameterRule rule : patch.getRules()) {
            if (violation.isPresent()) {
                return violation;
            }
            violation = ParameterConstraints.violation(rule.getValue(), declared);
        }
        return violation;
    }

    private static ParameterPatch patch(String key, Map<?, ?> fields, ParameterDefinition declared,
                                        ContextDimensions dimensions, String author, String reason, Instant setAt) {
        for (Object field : fields.keySet()) {
            if (!PATCH_FIELDS.contains(String.valueOf(field))) {
                throw new Invalid("незнакомое поле " + field + ": правка ключа — {value, rules}");
            }
        }
        if (fields.get("value") == null && !(fields.get("rules") instanceof List<?> listed && !listed.isEmpty())) {
            // Пустая правка сняла бы параметр целиком и молча — Spring ушёл бы к application.yaml.
            throw new Invalid("нужны value либо rules");
        }
        ParameterPatch.ParameterPatchBuilder patch = ParameterPatch.builder()
                .key(key).author(author).reason(reason).setAt(setAt)
                .value(node(fields.get("value"), declared.getType()));
        Object rules = fields.get("rules");
        if (rules != null && !(rules instanceof List<?>)) {
            throw new Invalid("rules — список правил");
        }
        Set<Map<String, String>> paths = new HashSet<>();
        for (Object rawRule : rules == null ? List.of() : (List<?>) rules) {
            ParameterRule rule = rule(rawRule, declared.getType(), dimensions);
            if (!paths.add(rule.getWhen())) {
                throw new Invalid("путь правила повторяется");
            }
            patch.rule(rule);
        }
        return patch.build();
    }

    private static ParameterRule rule(Object rawRule, ParameterType type, ContextDimensions dimensions) {
        if (!(rawRule instanceof Map<?, ?> rule) || rule.get("value") == null
                || !RULE_FIELDS.containsAll(rule.keySet().stream().map(String::valueOf).toList())) {
            throw new Invalid("правило — {when, value}");
        }
        ParameterRule.ParameterRuleBuilder built = ParameterRule.builder().value(node(rule.get("value"), type));
        Object when = rule.get("when");
        if (when == null) {
            // Правило без when подошло бы к любому пути и перекрыло бы value самой правки.
            throw new Invalid("у правила нет when: значение для всех путей пишется в value");
        }
        if (!(when instanceof Map<?, ?> parts) || parts.isEmpty()) {
            // when: /dc2 из синтаксиса исходного файла молча стал бы «везде».
            throw new Invalid("when — отображение «измерение → значение»");
        }
        for (Map.Entry<?, ?> part : parts.entrySet()) {
            String dimension = String.valueOf(part.getKey());
            if (!dimensions.isKnown(dimension)) {
                throw new Invalid("незнакомое измерение " + dimension);
            }
            if (!(part.getValue() instanceof String || part.getValue() instanceof Number)) {
                throw new Invalid("значение измерения " + dimension + " — строка");
            }
            if ("*".equals(String.valueOf(part.getValue()))) {
                // Звёздочка исходного файла здесь сравнивалась бы буквально и не подошла бы никогда.
                throw new Invalid("«*» в when не значит «любое»: измерение, которое не важно, не называйте");
            }
            built.whenPart(dimension, String.valueOf(part.getValue()));
        }
        return built.build();
    }

    /**
     * Нарушение формы заплатки; сообщение — причина без значения.
     */
    private static final class Invalid extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    private static JsonNode node(Object value, ParameterType type) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> elements) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            elements.forEach(element -> array.add(node(element, type)));
            return array;
        }
        if (value instanceof String text && text.contains("${")) {
            return JsonNodeFactory.instance.stringNode(text);
        }
        if (value instanceof String text) {
            rejectYaml11(text.strip(), type);
        }
        if (type == ParameterType.DURATION) {
            return JsonNodeFactory.instance.stringNode(Durations.parse(String.valueOf(value)).toString());
        }
        return MAPPER.valueToTree(value);
    }

    /** Текст, который в YAML 1.1 был бы признаком или числом, у признака и числа — с причиной. */
    private static void rejectYaml11(String text, ParameterType type) {
        if (type == ParameterType.BOOLEAN && YAML11_BOOLEAN.matcher(text).matches()) {
            throw new Invalid("on/off/yes/no — не признак: пишите true или false");
        }
        if ((type == ParameterType.INTEGER || type == ParameterType.DOUBLE)
                && NON_DECIMAL_NUMBER.matcher(text).matches()) {
            throw new Invalid("число с ведущим нулём либо 0x/0o/0b: пишите десятичное без ведущего нуля");
        }
    }

    /**
     * Неявные типы скаляров по схеме YAML 1.2 core: признак — {@code true}/{@code false}, число —
     * десятичное без ведущего нуля, {@code null}/{@code ~}/пусто; меток времени, восьмеричных,
     * шестидесятеричных и признаков {@code on}/{@code yes} нет — это текст.
     */
    private static final class CoreSchemaResolver extends Resolver {

        private static final Pattern BOOLEAN = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");

        private static final Pattern INTEGER = Pattern.compile("^[-+]?(?:0|[1-9][0-9]*)$");

        private static final Pattern DECIMAL =
                Pattern.compile("^[-+]?(?:\\.[0-9]+|(?:0|[1-9][0-9]*)(?:\\.[0-9]*)?)(?:[eE][-+]?[0-9]+)?$");

        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOLEAN, "tTfF");
            addImplicitResolver(Tag.INT, INTEGER, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, DECIMAL, "-+0123456789.");
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
        }
    }
}
