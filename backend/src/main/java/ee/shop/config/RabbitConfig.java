package ee.shop.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Payment result -> queue -> order consumer. One exchange/queue pair for the
 * payment result flow, plus a dead-letter exchange/queue for poison messages —
 * a listener that keeps throwing (a bug, a bad message) must not spin forever
 * or block every other message behind it; PaymentResultConsumer's retry/DLQ
 * wiring (see RabbitListenerConfig) is what actually routes failures here.
 */
@Configuration
public class RabbitConfig {

    public static final String PAYMENT_EXCHANGE = "payment.exchange";
    public static final String PAYMENT_RESULT_QUEUE = "payment.result.queue";
    public static final String PAYMENT_RESULT_ROUTING_KEY = "payment.result";

    public static final String PAYMENT_DLX = "payment.exchange.dlx";
    public static final String PAYMENT_RESULT_DLQ = "payment.result.dlq";

    @Bean
    public DirectExchange paymentExchange() {
        return new DirectExchange(PAYMENT_EXCHANGE);
    }

    @Bean
    public DirectExchange paymentDeadLetterExchange() {
        return new DirectExchange(PAYMENT_DLX);
    }

    @Bean
    public Queue paymentResultDeadLetterQueue() {
        return QueueBuilder.durable(PAYMENT_RESULT_DLQ).build();
    }

    @Bean
    public Binding paymentResultDeadLetterBinding() {
        return BindingBuilder.bind(paymentResultDeadLetterQueue())
                .to(paymentDeadLetterExchange())
                .with(PAYMENT_RESULT_ROUTING_KEY);
    }

    /**
     * A message that exhausts its retries (see RabbitListenerConfig) gets
     * nacked without requeue, which RabbitMQ routes to the dead-letter
     * exchange below because of these queue arguments — not because the
     * consumer does anything special. This is the actual DLQ mechanism, the
     * consumer's retry policy just decides WHEN a message counts as poison.
     */
    @Bean
    public Queue paymentResultQueue() {
        return QueueBuilder.durable(PAYMENT_RESULT_QUEUE)
                .withArgument("x-dead-letter-exchange", PAYMENT_DLX)
                .withArgument("x-dead-letter-routing-key", PAYMENT_RESULT_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding paymentResultBinding() {
        return BindingBuilder.bind(paymentResultQueue())
                .to(paymentExchange())
                .with(PAYMENT_RESULT_ROUTING_KEY);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(rabbitMessageConverter());
        return template;
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    /**
     * Overrides Boot's autoconfigured factory (same bean name,
     * "rabbitListenerContainerFactory", so every @RabbitListener picks this
     * up without naming it explicitly) to add the retry policy that makes
     * the DLQ wiring above actually mean something: a message that keeps
     * throwing gets retried 3 times with backoff, then
     * RejectAndDontRequeueRecoverer nacks it WITHOUT requeueing — which is
     * what RabbitMQ's dead-letter-exchange queue arguments are watching
     * for. Without this, Spring AMQP's default behavior is to requeue a
     * failed message forever, which for a genuinely poison message (a bug,
     * not a transient DB blip) means it never leaves the queue and blocks
     * everything behind it.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(rabbitMessageConverter());
        factory.setAdviceChain(retryInterceptor());
        return factory;
    }

    private StatelessRetryOperationsInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxRetries(3) // first attempt + 3 retries = 4 tries total
                .backOffOptions(1000, 2.0, 10000) // 1s, 2s, 4s... capped at 10s
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }
}
