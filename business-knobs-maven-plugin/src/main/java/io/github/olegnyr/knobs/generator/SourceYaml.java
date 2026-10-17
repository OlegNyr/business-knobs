package io.github.olegnyr.knobs.generator;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.InputStream;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Чтение исходного {@code knobs.yaml} по схеме YAML 1.2 core — так же, как аварийную заплатку на
 * поде: один и тот же текст значит одно и то же в файле и в правке. Ключ, повторённый в одном
 * отображении, — отказ, а не молча последний.
 */
final class SourceYaml {

    /** Признаки YAML 1.1, которых в 1.2 нет: без явного type такой текст — ловушка. */
    static final Pattern YAML11_BOOLEAN = Pattern.compile("(?i)y|yes|n|no|on|off");

    /** Числа, которые YAML 1.1 читает не десятичными: ведущий ноль, 0x, 0o, 0b. */
    static final Pattern NON_DECIMAL_NUMBER =
            Pattern.compile("[-+]?(?:0[0-9_]+(?:\\.[0-9]*)?|0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[bB][01_]+)");

    private SourceYaml() {
    }

    /**
     * Корень исходного файла.
     *
     * @param source исходный YAML
     * @return корневое отображение
     * @throws GeneratorException файл пуст, не отображение, не разбирается либо повторяет ключ
     */
    // PMD.PreserveStackTrace: причина SnakeYAML не передаётся — её текст цитирует строку файла
    // со значением; отказ называет номер строки и суть.
    @SuppressWarnings({"unchecked", "PMD.PreserveStackTrace"})
    static Map<String, Object> load(InputStream source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object document;
        try {
            document = new Yaml(new SafeConstructor(options), new Representer(new DumperOptions()),
                    new DumperOptions(), options, new CoreSchemaResolver()).load(source);
        } catch (DuplicateKeyException e) {
            throw new GeneratorException("исходный файл: " + e.getProblem() + " (строка "
                    + (e.getProblemMark().getLine() + 1) + ")");
        } catch (MarkedYAMLException e) {
            throw new GeneratorException("исходный файл не разбирается как YAML (строка "
                    + (e.getProblemMark() == null ? "?" : e.getProblemMark().getLine() + 1) + ")");
        } catch (YAMLException e) {
            throw new GeneratorException("исходный файл не разбирается как YAML: " + e.getClass().getName());
        }
        if (document == null) {
            throw new GeneratorException("исходный файл пуст");
        }
        if (!(document instanceof Map<?, ?> root)) {
            throw new GeneratorException("исходный файл — отображение service, stands, dimensions, parameters");
        }
        return (Map<String, Object>) root;
    }

    /**
     * Неявные типы скаляров по схеме YAML 1.2 core: признак — {@code true}/{@code false}, число —
     * десятичное без ведущего нуля, {@code null}/{@code ~}/пусто. Меток времени, восьмеричных,
     * шестидесятеричных и {@code yes/no/on/off} нет: дата и длительность остаются строками и
     * распознаются генератором по типу.
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
