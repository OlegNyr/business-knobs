package io.github.olegnyr.knobs.example;

import io.github.olegnyr.knobs.Knob;

import java.time.Duration;
import java.util.Optional;

/**
 * Параметры оформления заказа. Геттеры живые: каждый вызов читает действующее значение — по пути
 * пода и пути обращения текущего потока, с учётом записей топика и аварийной правки.
 */
@Knob("app.checkout")
public interface CheckoutKnobs {

    /**
     * Сколько позиций можно положить в корзину.
     *
     * @return {@code app.checkout.max-items}
     */
    int maxItems();

    /**
     * Сколько ждать ответа платёжного шлюза.
     *
     * @return {@code app.checkout.payment-timeout}
     */
    Duration paymentTimeout();

    /**
     * Приветствие на странице оформления.
     *
     * @return {@code app.checkout.greeting}
     */
    String greeting();

    /**
     * Включена ли экспресс-доставка.
     *
     * @return {@code app.checkout.express-delivery}
     */
    boolean expressDelivery();

    /**
     * Адрес платёжного шлюза; пусто, если переменная {@code PAYMENT_URL} не задана.
     *
     * @return {@code app.checkout.payment-url}
     */
    Optional<String> paymentUrl();
}
