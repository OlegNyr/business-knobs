package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.impl.group.ParameterGroups;
import io.github.olegnyr.knobs.impl.group.ParameterReader;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;

/**
 * Бин группы параметров: прокси интерфейса над читателем {@link ParameterReader}
 * контекста — в Spring это {@link BinderParameterReader} автоконфигурации, если сервис не подменил.
 *
 * @param <T> интерфейс группы
 */
class ParameterGroupFactoryBean<T> implements FactoryBean<T>, BeanFactoryAware {

    private final Class<T> type;

    private BeanFactory beanFactory;

    /**
     * Фабрика группы.
     *
     * @param type интерфейс с {@code @Knob}
     */
    ParameterGroupFactoryBean(Class<T> type) {
        this.type = type;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public T getObject() {
        return ParameterGroups.create(type, beanFactory.getBean(ParameterReader.class));
    }

    @Override
    public Class<?> getObjectType() {
        return type;
    }
}
