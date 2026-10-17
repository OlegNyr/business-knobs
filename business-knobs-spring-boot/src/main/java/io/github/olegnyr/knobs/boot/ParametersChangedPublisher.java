package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.ParameterListener;
import io.github.olegnyr.knobs.ParametersChanged;
import io.github.olegnyr.knobs.impl.ParameterObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;

/**
 * Отдаёт {@link ParametersChanged} слушателям контекста Spring так, что отказ одного не мешает
 * остальным. Это слушатель рассылки библиотеки ({@code ParameterEvents}): та
 * изолирует своих потребителей, а здесь — слушателей контекста внутри одного.
 *
 * <p>Общий рассыльщик контекста останавливается на первом исключении и выпускает его
 * публикатору — тогда запись топика считалась бы неприменённой из-за чужого кода. Здесь
 * рассыльщик свой, с обработчиком ошибок, над теми же слушателями контекста: и
 * {@code @EventListener}-методами, и бинами {@code ApplicationListener}. Отказ уходит в лог
 * классом корневой причины — текст исключения может цитировать значения.
 */
@Slf4j
class ParametersChangedPublisher implements ParameterListener {

    private final ConfigurableApplicationContext context;

    private final ParameterObserver observer;

    ParametersChangedPublisher(ConfigurableApplicationContext context, ParameterObserver observer) {
        this.context = context;
        this.observer = observer;
    }

    // PMD.CloseResource: контекст приложения — не наш ресурс; закрывает его тот, кто поднял.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public void parametersChanged(ParametersChanged event) {
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster(context.getBeanFactory());
        multicaster.setErrorHandler(failure -> {
            observer.consumerFailed();
            log.warn("Потребитель ParametersChanged отказал: keys={}, причина {}", event.getKeys(),
                    rootCause(failure).getClass().getName());
        });
        if (context instanceof AbstractApplicationContext application) {
            application.getApplicationListeners().forEach(multicaster::addApplicationListener);
        }
        for (String name : context.getBeanNamesForType(ApplicationListener.class, false, false)) {
            multicaster.addApplicationListenerBean(name);
        }
        multicaster.multicastEvent(new PayloadApplicationEvent<>(context, event));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        Throwable next = cause.getCause();
        while (next != null && !next.equals(cause)) {
            cause = next;
            next = cause.getCause();
        }
        return cause;
    }
}
