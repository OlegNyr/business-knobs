package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.Knob;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.beans.Introspector;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Скан пакетов {@link EnableKnobs}: каждый интерфейс или класс с {@link Knob} — бин через
 * {@link ParameterGroupFactoryBean}. Имя бина — имя интерфейса со строчной
 * первой буквой.
 */
class ParameterGroupRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware, ResourceLoaderAware {

    private Environment environment;

    private ResourceLoader resourceLoader;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(EnableKnobs.class.getName());
        if (attributes == null) {
            return;
        }
        ClassPathScanningCandidateComponentProvider scanner = new InterfaceScanner(environment);
        scanner.setResourceLoader(resourceLoader);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Knob.class));
        for (String basePackage : packages(metadata, attributes)) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                String className = candidate.getBeanClassName();
                if (className == null) {
                    continue;
                }
                BeanDefinition definition = BeanDefinitionBuilder.genericBeanDefinition(ParameterGroupFactoryBean.class)
                        .addConstructorArgValue(className)
                        .getBeanDefinition();
                // Тип фабрики — классом, а не строкой: Spring принимает в атрибуте только Class либо
                // ResolvableType, и поиск бинов по типу до создания фабрики (второй круг постпроцессоров, срезы)
                // иначе не находил группу либо ронял подъём контекста.
                definition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE,
                        ClassUtils.resolveClassName(className, resourceLoader.getClassLoader()));
                beanName(registry, className).ifPresent(name -> registry.registerBeanDefinition(name, definition));
            }
        }
    }

    /**
     * Имя бина группы. Сканы {@code @EnableKnobs} на приложении и на автоконфигурации фичи
     * пересекаются по вложенным пакетам — группа, уже зарегистрированная другим сканом, второй раз
     * не регистрируется. Группа с тем же простым именем из другого пакета получает имя по полному
     * имени класса, а не роняет подъём контекста.
     *
     * @return имя бина; пусто — группа уже зарегистрирована
     */
    private static Optional<String> beanName(BeanDefinitionRegistry registry, String className) {
        String shortName = Introspector.decapitalize(ClassUtils.getShortName(className));
        for (String name : List.of(shortName, className)) {
            if (!registry.containsBeanDefinition(name)) {
                return Optional.of(name);
            }
            if (isGroup(registry.getBeanDefinition(name), className)) {
                return Optional.empty();
            }
        }
        throw new IllegalStateException("Имя бина группы " + className + " занято другим бином");
    }

    private static boolean isGroup(BeanDefinition definition, String className) {
        return definition.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE) instanceof Class<?> type
                && type.getName().equals(className);
    }

    private static Set<String> packages(AnnotationMetadata metadata, Map<String, Object> attributes) {
        Set<String> packages = new LinkedHashSet<>();
        for (String name : (String[]) attributes.get("basePackages")) {
            if (!name.isBlank()) {
                packages.add(name);
            }
        }
        for (Class<?> anchor : (Class<?>[]) attributes.get("basePackageClasses")) {
            packages.add(ClassUtils.getPackageName(anchor));
        }
        if (packages.isEmpty()) {
            packages.add(ClassUtils.getPackageName(metadata.getClassName()));
        }
        return packages;
    }

    /**
     * Скан, принимающий интерфейсы и абстрактные классы: стандартный берёт только конкретные.
     * Класс, который группой быть не может (final, без конструктора, без ByteBuddy), не пропускается
     * молча — его отбивает {@code ParameterGroups.create} при подъёме контекста.
     */
    private static final class InterfaceScanner extends ClassPathScanningCandidateComponentProvider {

        InterfaceScanner(Environment environment) {
            super(false, environment);
        }

        @Override
        protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
            return definition.getMetadata().isIndependent();
        }
    }
}
