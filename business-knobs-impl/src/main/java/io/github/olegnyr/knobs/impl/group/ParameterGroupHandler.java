package io.github.olegnyr.knobs.impl.group;

import io.github.olegnyr.knobs.ParameterMissingException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Вызов геттера группы: ключ и тип разобраны заранее, значение читается на
 * каждом вызове. Один обработчик на обе формы группы: у интерфейса ({@code Proxy}) методы
 * {@code Object} он отвечает сам, а {@code default}-методы исполняет как объявлены; у подкласса
 * абстрактного класса сюда приходят только абстрактные методы.
 */
final class ParameterGroupHandler implements InvocationHandler {

    private final Class<?> type;

    private final String prefix;

    private final Map<Method, GroupMethod> methods;

    private final ParameterReader reader;

    /**
     * {@code default}-методы непубличных интерфейсов: {@code invokeDefault} проверяет доступ из
     * пакета библиотеки и их не пускает, поэтому вызов идёт через поиск в пакете самого интерфейса.
     */
    private final Map<Method, MethodHandle> privateDefaults = new ConcurrentHashMap<>();

    ParameterGroupHandler(Class<?> type, String prefix, Map<Method, GroupMethod> methods, ParameterReader reader) {
        this.type = type;
        this.prefix = prefix;
        // Копия в ConcurrentHashMap: подкласс ByteBuddy может передать Method, равный разобранному,
        // но полученный другим путём; разбор тогда докладывается на вызове, один раз на метод.
        this.methods = new ConcurrentHashMap<>(methods);
        this.reader = reader;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return objectMethod(proxy, method, args);
        }
        if (method.isDefault()) {
            return invokeDefault(proxy, method, args);
        }
        GroupMethod resolved = methods.computeIfAbsent(method, declared -> GroupMethod.of(prefix, declared, type));
        Optional<Object> value = reader.read(resolved.key(), resolved.type());
        if (resolved.optional()) {
            return value;
        }
        return value.orElseThrow(() -> new ParameterMissingException(resolved.key()));
    }

    private Object invokeDefault(Object proxy, Method method, Object... args) throws Throwable {
        MethodHandle handle = privateDefaults.get(method);
        if (handle == null) {
            try {
                return InvocationHandler.invokeDefault(proxy, method, args);
            } catch (IllegalAccessException e) {
                handle = privateDefaults.computeIfAbsent(method, ParameterGroupHandler::privateDefault);
            }
        }
        return handle.bindTo(proxy).invokeWithArguments(args == null ? new Object[0] : args);
    }

    private static MethodHandle privateDefault(Method method) {
        Class<?> owner = method.getDeclaringClass();
        try {
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).unreflectSpecial(method, owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("default-метод " + method.getName() + " группы " + owner.getName()
                    + " недоступен библиотеке: откройте пакет " + owner.getPackageName()
                    + " модулю io.github.olegnyr.knobs.impl либо сделайте интерфейс public", e);
        }
    }

    // PMD.CompareObjectsWithEquals: equals прокси — тождество намеренно: значений у группы нет,
    // сравнивать нечего, кроме самого объекта.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private Object objectMethod(Object proxy, Method method, Object... args) {
        return switch (method.getName()) {
            case "toString" -> "Knob[" + type.getSimpleName() + " prefix=" + prefix + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }
}
