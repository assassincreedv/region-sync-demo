package com.example.regionsync.consumer;

import com.example.regionsync.metrics.SyncMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterConsumer {

    private final SyncMetrics syncMetrics;

    @KafkaListener(
            topics = "#{@syncTopicsProperties.deadLetterTopic}",
            groupId = "dlt-consumer-${sync.current-region}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(String message, Acknowledgment ack) {
        log.error("Dead letter message received: {}",
                message != null && message.length() > 500 ? message.substring(0, 500) + "..." : message);
        syncMetrics.incrementDeadLetter();
        ack.acknowledge();
    }
}
