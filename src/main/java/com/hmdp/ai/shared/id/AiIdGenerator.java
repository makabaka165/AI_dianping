package com.hmdp.ai.shared.id;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AiIdGenerator {

    public String nextId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
