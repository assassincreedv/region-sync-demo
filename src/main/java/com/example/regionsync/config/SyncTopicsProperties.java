package com.example.regionsync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sync-topics")
public class SyncTopicsProperties {

    private String remoteCdcTopics;
    private String rejectionInbox;
    private String rejectionOutbox;
    private String deadLetterTopic;
}
