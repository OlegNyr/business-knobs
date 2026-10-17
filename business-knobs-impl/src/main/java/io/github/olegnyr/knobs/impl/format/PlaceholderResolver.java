package io.github.olegnyr.knobs.impl.format;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Подстановка {@code ${ИМЯ}} в значение из файла стенда.
 *
 * <p>Подстановки бывают только в файле: джоба загрузки разрешает их по файлу значений, сервис —
 * по переменным окружения. Значение из админки и из топика всегда буквальное, сюда оно не
 * попадает. Не разрешилась хоть одна подстановка — значения нет целиком: половина адреса хуже
 * отсутствующего.
 */
public final class PlaceholderResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private PlaceholderResolver() {
    }

    /**
     * Разрешает подстановки в строках значения; у списка — в каждом элементе.
     *
     * @param value  значение из файла стенда
     * @param lookup имя переменной → её значение либо {@code null}
     * @return значение без подстановок; пусто, если хоть одна не разрешилась либо после
     * разрешения в тексте остался {@code ${...}}
     */
    public static Optional<JsonNode> resolve(JsonNode value, Function<String, String> lookup) {
        if (value.isArray()) {
            var resolved = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : value) {
                Optional<JsonNode> item = resolve(element, lookup);
                if (item.isEmpty()) {
                    return Optional.empty();
                }
                resolved.add(item.get());
            }
            return Optional.of(resolved);
        }
        if (!value.isString() || !value.stringValue().contains("${")) {
            return Optional.of(value);
        }
        Matcher matcher = PLACEHOLDER.matcher(value.stringValue());
        StringBuilder text = new StringBuilder();
        while (matcher.find()) {
            String replacement = lookup.apply(matcher.group(1));
            if (replacement == null) {
                return Optional.empty();
            }
            matcher.appendReplacement(text, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(text);
        if (text.indexOf("${") >= 0) {
            // Остаток подстановки — незакрытая «${X», пустая «${}» либо «${» в значении переменной.
            // В свойство Spring такой текст не отдаётся: Spring разрешил бы его сам, по любому
            // источнику, а не только по окружению.
            return Optional.empty();
        }
        return Optional.of(JsonNodeFactory.instance.stringNode(text.toString()));
    }
}
