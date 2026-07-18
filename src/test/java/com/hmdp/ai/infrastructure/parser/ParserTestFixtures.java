package com.hmdp.ai.infrastructure.parser;

import com.hmdp.ai.domain.knowledge.parsing.ParseContext;

final class ParserTestFixtures {
    private ParserTestFixtures() { }

    static ParseContext context(String name, String mime, long size) {
        return new ParseContext(name, mime, size, 10, 1000);
    }
}
