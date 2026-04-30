package com.XI.xi_oj.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String JUDGE_EXCHANGE = "oj.judge.exchange";
    public static final String JUDGE_QUEUE = "oj.judge.queue";
    public static final String JUDGE_ROUTING_KEY = "oj.judge.submit";

    public static final String JUDGE_DLX = "oj.judge.dlx";
    public static final String JUDGE_DLQ = "oj.judge.dlq";
    public static final String JUDGE_DLQ_ROUTING_KEY = "oj.judge.dead";

    @Bean
    public DirectExchange judgeDlx() {
        return new DirectExchange(JUDGE_DLX, true, false);
    }

    @Bean
    public Queue judgeDlq() {
        return QueueBuilder.durable(JUDGE_DLQ).build();
    }

    @Bean
    public Binding judgeDlqBinding() {
        return BindingBuilder.bind(judgeDlq()).to(judgeDlx()).with(JUDGE_DLQ_ROUTING_KEY);
    }

    @Bean
    public DirectExchange judgeExchange() {
        return new DirectExchange(JUDGE_EXCHANGE, true, false);
    }

    @Bean
    public Queue judgeQueue() {
        return QueueBuilder.durable(JUDGE_QUEUE)
                .withArgument("x-dead-letter-exchange", JUDGE_DLX)
                .withArgument("x-dead-letter-routing-key", JUDGE_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding judgeBinding() {
        return BindingBuilder.bind(judgeQueue()).to(judgeExchange()).with(JUDGE_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
