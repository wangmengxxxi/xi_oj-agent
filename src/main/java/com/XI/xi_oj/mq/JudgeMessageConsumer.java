package com.XI.xi_oj.mq;

import com.XI.xi_oj.config.RabbitMQConfig;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.judge.JudgeService;
import com.XI.xi_oj.model.dto.mq.JudgeMessage;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Slf4j
@Component
public class JudgeMessageConsumer {

    @Resource
    private JudgeService judgeService;

    @RabbitListener(
            queues = RabbitMQConfig.JUDGE_QUEUE,
            ackMode = "MANUAL"
    )
    public void onMessage(JudgeMessage message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        Long questionSubmitId = message.getQuestionSubmitId();
        log.info("[Judge Consumer] received questionSubmitId={}, source={}",
                questionSubmitId, message.getSource());

        if ("ai_tool".equals(message.getSource())) {
            log.warn("[Judge Consumer] skip ai_tool message, questionSubmitId={}",
                    questionSubmitId);
            safeAck(channel, deliveryTag);
            return;
        }

        try {
            judgeService.doJudge(questionSubmitId);
            safeAck(channel, deliveryTag);
            log.info("[Judge Consumer] success questionSubmitId={}", questionSubmitId);
        } catch (Exception e) {
            log.error("[Judge Consumer] failed questionSubmitId={}",
                    questionSubmitId, e);
            handleFailure(message, channel, deliveryTag, e);
        }
    }

    private void handleFailure(JudgeMessage message, Channel channel,
                               long deliveryTag, Exception e) {
        try {
            if (e instanceof BusinessException) {
                safeAck(channel, deliveryTag);
            } else {
                channel.basicNack(deliveryTag, false, false);
                log.error("[Judge Consumer] nack to DLQ, questionSubmitId={}",
                        message.getQuestionSubmitId());
            }
        } catch (Exception ackEx) {
            log.error("[Judge Consumer] ack/nack failed", ackEx);
        }
    }

    private void safeAck(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("[Judge Consumer] ack failed, deliveryTag={}", deliveryTag, e);
        }
    }
}
