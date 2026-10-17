package io.github.olegnyr.knobs.generator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Собирает из исходного YAML разработчика JSON-файл на каждый стенд.
 *
 * <p>Правила исходного файла (пример — {@code doc/guide/knobs-example.yaml}): параметр — узел, где
 * есть хоть одно зарезервированное поле; тип выводится из значения, если не указан; {@code rules} —
 * списком (все стенды) либо по стендам с {@code all}; правило стенда без пути перекрывает общие;
 * места пути — измерения секции {@code dimensions} в объявленном порядке без {@code service}
 * ({@code /dc2/&#42;/VIP}), {@code *} — любое значение, {@code "*}{@code /dc2"} — правило для всех
 * сервисов; параметр без значения и правил для стенда в файл стенда не попадает. Длительность
 * пишется как в настройках Spring ({@code 10s}) и уходит в файл строкой ISO-8601.
 */
public final class StandFileGenerator {

    private static final Set<String> RESERVED = Set.of("name", "description", "type", "list", "allowed", "min",
            "max", "pattern", "format", "default", "rules", "deleted");

    private static final Set<String> TYPES = Set.of("BOOLEAN", "STRING", "INTEGER", "DOUBLE", "DURATION", "DATE");

    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h|d)");

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** Сегмент ключа: имя свойства Spring в каноничной форме, kebab-case. */
    private static final Pattern KEY_SEGMENT = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String RESERVED_PREFIX = "knobs.";

    private static final String SERVICE = "service";

    private StandFileGenerator() {
    }

    /**
     * Сборка без Maven, из командной строки: {@code <исходный YAML> <каталог вывода> <версия сборки>}.
     * В сборке Maven то же делает цель {@code generate} плагина ({@link GenerateMojo}).
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Использование: StandFileGenerator <knobs.yaml> <каталог> <версия>");
        }
        try {
            write(Path.of(args[0]), Path.of(args[1]), args[2]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Пишет файлы стендов {@code knobs-<стенд>.json} в каталог; прежние {@code knobs-*.json} каталога
     * удаляются — файл снятого стенда не остаётся.
     *
     * @param source    исходный YAML
     * @param directory каталог файлов стендов; создаётся, если его нет
     * @param version   версия сборки — поле {@code version} файлов
     * @return записанные файлы в порядке {@code stands}
     * @throws IOException        исходный файл не читается либо каталог не пишется
     * @throws GeneratorException исходный файл нарушает правила, сообщение называет ключ
     */
    public static List<Path> write(Path source, Path directory, String version) throws IOException {
        Map<String, JsonNode> files;
        try (InputStream in = Files.newInputStream(source)) {
            files = generate(in, version);
        }
        Files.createDirectories(directory);
        // Файл снятого стенда не должен остаться в каталоге и уехать в jar следующей сборки.
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(directory, "knobs-*.json")) {
            for (Path file : stale) {
                Files.delete(file);
            }
        }
        List<Path> written = new ArrayList<>();
        for (Map.Entry<String, JsonNode> file : files.entrySet()) {
            Path target = directory.resolve("knobs-" + file.getKey() + ".json");
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(file.getValue());
            Files.writeString(target, json + "\n");
            written.add(target);
        }
        return List.copyOf(written);
    }

    /**
     * Собирает файлы стендов.
     *
     * @param source  исходный YAML
     * @param version версия сборки — поле {@code version} файлов
     * @return стенд → файл стенда в порядке {@code stands}
     * @throws GeneratorException исходный файл нарушает правила, сообщение называет ключ
     */
    public static Map<String, JsonNode> generate(InputStream source, String version) {
        Map<String, Object> document = SourceYaml.load(source);
        if (!(document.get(SERVICE) instanceof String service) || service.isBlank()) {
            throw new GeneratorException("нет service строкой — имя сервиса, spring.application.name");
        }
        List<String> stands = strings(document.get("stands"), "stands");
        if (stands.isEmpty()) {
            throw new GeneratorException("stands пуст — нужен хотя бы один стенд");
        }
        SourceDimensions dimensions = SourceDimensions.read(document.get("dimensions"));
        Map<String, Map<String, Object>> parameters = new TreeMap<>();
        walk(map(document.get("parameters"), "parameters"), "", parameters);
        Map<String, JsonNode> files = new LinkedHashMap<>();
        for (String stand : stands) {
            ArrayNode items = NODES.arrayNode();
            parameters.forEach((key, parameter) -> parameterFor(key, parameter, service, stand, stands,
                    dimensions).ifPresent(items::add));
            ObjectNode file = NODES.objectNode();
            file.put(SERVICE, service);
            file.put("version", version);
            file.put("stand", stand);
            file.set("dimensions", dimensions.declarations());
            file.set("parameters", items);
            files.put(stand, file);
        }
        return files;
    }

    private static Optional<ObjectNode> parameterFor(String key, Map<String, Object> parameter,
                                                     String service, String stand, List<String> stands,
                                                     SourceDimensions dimensions) {
        if (Boolean.TRUE.equals(parameter.get("deleted"))) {
            ObjectNode deleted = NODES.objectNode();
            deleted.put("key", key);
            deleted.put("deleted", true);
            return Optional.of(deleted);
        }
        if (key.startsWith(RESERVED_PREFIX)) {
            // Ключи knobs.* — настройки самой библиотеки (рубильник, ЦОД пода): слой
            // параметров стоит выше окружения и перекрыл бы их.
            throw new GeneratorException(key + ": префикс " + RESERVED_PREFIX + " занят библиотекой параметров");
        }
        if (!(parameter.get("name") instanceof String name) || name.isBlank()) {
            throw new GeneratorException(key + ": нет name строкой");
        }
        Map<String, List<Map<String, Object>>> byStand = rulesByStand(key, parameter.get("rules"), stands);
        List<Object> values = new ArrayList<>();
        if (parameter.containsKey("default")) {
            if (parameter.get("default") == null) {
                throw new GeneratorException(key + ": default пуст — уберите его либо задайте значение");
            }
            values.add(parameter.get("default"));
        }
        byStand.values().forEach(group -> group.forEach(rule -> values.add(rule.get("value"))));
        Typed typed = typeOf(key, parameter, values);
        checkValues(key, parameter, values, typed);
        boolean shared = byStand.values().stream().flatMap(List::stream)
                .flatMap(rule -> when(rule).stream())
                .anyMatch(path -> dimensions.parse(path, key).allServices());
        List<List<Map<String, Object>>> groups = new ArrayList<>(List.of(
                byStand.getOrDefault(stand, List.of()), byStand.getOrDefault("all", List.of())));
        if (groups.getFirst().stream().anyMatch(rule -> when(rule).isEmpty())) {
            groups.set(1, List.of());
        }
        boolean hasValue = parameter.containsKey("default");
        JsonNode value = canon(key, typed.type(), parameter.get("default"));
        Integer best = null;
        Map<RulePath, ObjectNode> seen = new LinkedHashMap<>();
        for (int group = 0; group < groups.size(); group++) {
            for (Map<String, Object> rule : groups.get(group)) {
                JsonNode ruleValue = canon(key, typed.type(), rule.get("value"));
                if (when(rule).isEmpty()) {
                    if (best == null || group < best) {
                        best = group;
                        value = ruleValue;
                        hasValue = true;
                    }
                    continue;
                }
                for (Object element : when(rule)) {
                    RulePath path = dimensions.parse(element, key);
                    if (seen.containsKey(path)) {
                        continue;
                    }
                    ObjectNode when = NODES.objectNode();
                    if (shared && !path.allServices()) {
                        when.put(SERVICE, service);
                    }
                    path.when().forEach(when::put);
                    ObjectNode node = NODES.objectNode();
                    node.set("when", when);
                    node.set("value", ruleValue);
                    seen.put(path, node);
                }
            }
        }
        List<Map.Entry<RulePath, ObjectNode>> ordered = new ArrayList<>(seen.entrySet());
        ordered.sort(Map.Entry.comparingByKey(RulePath.MOST_PRECISE_FIRST));
        if (!hasValue && ordered.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode item = NODES.objectNode();
        item.put("key", key);
        item.put("name", (String) parameter.get("name"));
        if (parameter.containsKey("description")) {
            item.put("description", String.valueOf(parameter.get("description")));
        }
        item.put("type", typed.type());
        if (typed.list()) {
            item.put("list", true);
        }
        for (String field : List.of("allowed", "min", "max", "pattern", "format")) {
            if (parameter.containsKey(field)) {
                item.set(field, List.of("allowed", "min", "max").contains(field)
                        ? canon(key, typed.type(), parameter.get(field))
                        : NODES.stringNode(String.valueOf(parameter.get(field))));
            }
        }
        item.set("value", value);
        ArrayNode rules = NODES.arrayNode();
        ordered.forEach(entry -> rules.add(entry.getValue()));
        item.set("rules", rules);
        return Optional.of(item);
    }

    /**
     * Тип параметра и признак списка.
     *
     * @param type тип файла стенда: {@code BOOLEAN}, {@code STRING}, ...
     * @param list значение — список значений типа
     */
    private record Typed(String type, boolean list) {
    }

    private static Typed typeOf(String key, Map<String, Object> parameter, List<Object> values) {
        boolean list = Boolean.TRUE.equals(parameter.get("list"))
                || !values.isEmpty() && values.getFirst() instanceof List<?>;
        if (parameter.containsKey("type")) {
            String type = String.valueOf(parameter.get("type")).toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                throw new GeneratorException(key + ": type — boolean, string, integer, double, duration либо date");
            }
            return new Typed(type, list);
        }
        if (values.isEmpty()) {
            throw new GeneratorException(key + ": нет ни типа, ни значения");
        }
        Object first = values.getFirst();
        if (!(first instanceof List<?> elements)) {
            return new Typed(inferred(first, key), list);
        }
        if (elements.isEmpty()) {
            throw new GeneratorException(key + ": тип не указан, а список пуст");
        }
        Set<String> kinds = new LinkedHashSet<>();
        elements.forEach(element -> kinds.add(inferred(element, key)));
        if (kinds.size() != 1) {
            throw new GeneratorException(key + ": в списке значения разных типов");
        }
        return new Typed(kinds.iterator().next(), true);
    }

    private static void checkValues(String key, Map<String, Object> parameter, List<Object> values, Typed typed) {
        for (Object value : values) {
            if (value instanceof List<?> != typed.list()) {
                throw new GeneratorException(key + (typed.list() ? ": скаляр у списка" : ": список у скалярного"));
            }
            for (Object element : value instanceof List<?> elements ? elements : List.of(value)) {
                if (typed.list() && isPlaceholder(element)) {
                    // Читатель файла стенда разрешает подстановку только в скалярном значении.
                    throw new GeneratorException(key + ": подстановка ${} в элементе списка не поддерживается");
                }
                if (!fits(typed.type(), element, key)) {
                    throw new GeneratorException(key + ": значение не подходит типу " + typed.type());
                }
            }
        }
        if (parameter.get("allowed") != null && !(parameter.get("allowed") instanceof List<?>)) {
            throw new GeneratorException(key + ": allowed — список допустимых значений");
        }
        for (String field : List.of("min", "max")) {
            if (parameter.get(field) instanceof List<?>) {
                throw new GeneratorException(key + ": " + field + " — одно значение, не список");
            }
        }
        for (String field : List.of("min", "max", "allowed")) {
            Object bound = parameter.get(field);
            for (Object element : bound instanceof List<?> elements ? elements
                    : bound == null ? List.of() : List.of(bound)) {
                if (isPlaceholder(element) || !fits(typed.type(), element, key)) {
                    throw new GeneratorException(key + ": " + field + " не подходит типу " + typed.type());
                }
            }
        }
    }

    private static Map<String, List<Map<String, Object>>> rulesByStand(String key, Object rules, List<String> stands) {
        Map<String, List<Map<String, Object>>> byStand = new LinkedHashMap<>();
        if (rules == null) {
            return byStand;
        }
        if (rules instanceof List<?>) {
            byStand.put("all", ruleList(key, rules));
            return byStand;
        }
        map(rules, key).forEach((stand, group) -> {
            if (!"all".equals(stand) && !stands.contains(stand)) {
                throw new GeneratorException(key + ": в rules неизвестный стенд " + stand);
            }
            byStand.put(stand, ruleList(key, group));
        });
        return byStand;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> ruleList(String key, Object rules) {
        if (!(rules instanceof List<?> list)) {
            throw new GeneratorException(key + ": правила — список");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object rule : list) {
            if (!(rule instanceof Map<?, ?> map) || !map.containsKey("value")) {
                throw new GeneratorException(key + ": у правила нет value");
            }
            if (map.get("value") == null) {
                throw new GeneratorException(key + ": у правила пустое value");
            }
            if (map.get("when") != null && !(map.get("when") instanceof List<?>)) {
                throw new GeneratorException(key + ": when — список путей");
            }
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    private static List<?> when(Map<String, Object> rule) {
        return rule.get("when") instanceof List<?> when ? when : List.of();
    }

    private static void walk(Map<?, ?> node, String prefix, Map<String, Map<String, Object>> out) {
        node.forEach((rawSegment, child) -> {
            String segment = String.valueOf(rawSegment);
            String key = prefix.isEmpty() ? segment : prefix + "." + segment;
            if (!KEY_SEGMENT.matcher(segment).matches()) {
                throw new GeneratorException(key + ": сегмент ключа «" + segment + "» — строчные латинские буквы, "
                        + "цифры и дефис (kebab-case)");
            }
            if (!(child instanceof Map<?, ?> map)) {
                throw new GeneratorException(key + ": ни параметр, ни путь");
            }
            Map<String, Object> fields = map(map, key);
            boolean reserved = fields.keySet().stream().anyMatch(RESERVED::contains);
            if (reserved && !RESERVED.containsAll(fields.keySet())) {
                throw new GeneratorException(key + ": в узле смешаны поля параметра и сегменты ключа");
            }
            if (reserved) {
                out.put(key, fields);
            } else {
                walk(fields, key, out);
            }
        });
    }

    /**
     * Тип, выведенный из значения, когда {@code type} не указан. Текст, который YAML 1.1 прочёл бы
     * числом или признаком ({@code 010}, {@code no}), — отказ: без явного типа неясно, что имелось
     * в виду.
     */
    private static String inferred(Object value, String key) {
        if (isPlaceholder(value)) {
            throw new GeneratorException(key + ": тип не указан, а значение — подстановка");
        }
        if (value instanceof String text && (SourceYaml.NON_DECIMAL_NUMBER.matcher(text).matches()
                || SourceYaml.YAML11_BOOLEAN.matcher(text).matches())) {
            throw new GeneratorException(key + ": значение похоже на число с ведущим нулём, 0x/0o/0b либо на "
                    + "on/off/yes/no — укажите type");
        }
        return kindOf(value, key);
    }

    private static String kindOf(Object value, String key) {
        return switch (value) {
            case Boolean ignored -> "BOOLEAN";
            case Integer ignored -> "INTEGER";
            case Long ignored -> "INTEGER";
            case BigInteger ignored -> throw new GeneratorException(key + ": целое вне диапазона 64 бит");
            case Double ignored -> "DOUBLE";
            case String text when DURATION.matcher(text).matches() -> "DURATION";
            case String text when isDate(text) -> "DATE";
            case String ignored -> "STRING";
            case null, default -> throw new GeneratorException(key + ": тип не выводится из значения");
        };
    }

    private static boolean fits(String type, Object value, String key) {
        if (isPlaceholder(value)) {
            return true;
        }
        return switch (type) {
            case "STRING" -> value instanceof String;
            case "DURATION" -> value instanceof String text && DURATION.matcher(text).matches();
            case "DATE" -> value instanceof String text && isDate(text);
            case "DOUBLE" -> value instanceof Double || "INTEGER".equals(kindOf(value, key));
            default -> type.equals(kindOf(value, key));
        };
    }

    private static boolean isPlaceholder(Object value) {
        return value instanceof String text && text.contains("${");
    }

    private static boolean isDate(String text) {
        if (!DATE.matcher(text).matches()) {
            return false;
        }
        try {
            return LocalDate.parse(text) != null;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static JsonNode canon(String key, String type, Object value) {
        if (value == null) {
            return NODES.nullNode();
        }
        if (value instanceof List<?> elements) {
            ArrayNode array = NODES.arrayNode();
            elements.forEach(element -> array.add(canon(key, type, element)));
            return array;
        }
        if (isPlaceholder(value)) {
            return NODES.stringNode((String) value);
        }
        if ("DURATION".equals(type)) {
            return NODES.stringNode(isoDuration(key, String.valueOf(value)));
        }
        if ("DATE".equals(type) || "STRING".equals(type)) {
            return NODES.stringNode(String.valueOf(value));
        }
        return MAPPER.valueToTree(value);
    }

    // PMD.PreserveStackTrace: причина не передаётся намеренно — NumberFormatException цитирует
    // значение, а отказ генератора называет ключ без значения.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static String isoDuration(String key, String text) {
        Matcher matcher = DURATION.matcher(text);
        if (!matcher.matches()) {
            throw new GeneratorException(key + ": длительность не разобрана — пишется как 500ms, 10s, 5m, 2h, 1d");
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            Duration duration = switch (matcher.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
            return duration.toString();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new GeneratorException(key + ": длительность за пределами допустимого");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String where) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new GeneratorException(where + ": ожидается отображение");
        }
        return (Map<String, Object>) map;
    }

    private static List<String> strings(Object value, String where) {
        if (!(value instanceof List<?> list)) {
            throw new GeneratorException(where + ": ожидается список");
        }
        return list.stream().map(String::valueOf).toList();
    }
}
