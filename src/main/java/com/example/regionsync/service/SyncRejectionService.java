package com.example.regionsync.service;

import com.example.regionsync.config.SyncTopicsProperties;
import com.example.regionsync.model.event.SyncRejection;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncRejectionService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SyncTopicsProperties syncTopicsProperties;
    private final ObjectMapper objectMapper;

    public void sendRejection(SyncRejection rejection) {
        try {
            String json = objectMapper.writeValueAsString(rejection);
            String topic = syncTopicsProperties.getRejectionOutbox();
            kafkaTemplate.send(topic, rejection.getBusinessKey(), json);
            log.warn("Sent rejection to topic={} rejectionId={} reason={} businessKey={}",
                    topic, rejection.getRejectionId(), rejection.getRejectionReason(), rejection.getBusinessKey());
        } catch (Exception e) {
            log.error("Failed to send rejection for eventId={}: {}", rejection.getOriginalEventId(), e.getMessage(), e);
        }
    }
}
