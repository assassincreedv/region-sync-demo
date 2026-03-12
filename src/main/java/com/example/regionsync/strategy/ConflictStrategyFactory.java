package com.example.regionsync.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ConflictStrategyFactory {

    private static final String DEFAULT_STRATEGY = "FIRST_WRITER_WINS";

    private final Map<String, ConflictStrategy> strategies;

    public ConflictStrategyFactory(List<ConflictStrategy> strategyList) {
        strategies = new HashMap<>();
        for (ConflictStrategy strategy : strategyList) {
            strategies.put(strategy.getStrategyName(), strategy);
            log.info("Registered ConflictStrategy: {}", strategy.getStrategyName());
        }
    }

    public ConflictStrategy getStrategy(String name) {
        ConflictStrategy strategy = strategies.get(name);
        if (strategy == null) {
            log.warn("Strategy '{}' not found, falling back to '{}'", name, DEFAULT_STRATEGY);
            return strategies.get(DEFAULT_STRATEGY);
        }
        return strategy;
    }
}
