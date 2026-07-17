package com.hmdp.ai.infrastructure.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

public interface DocumentParser {
    Set<String> supportedMimeTypes();
    ParsedDocument parse(InputStream input, ParseContext context) throws IOException;
}
