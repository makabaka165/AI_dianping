package com.hmdp.ai.domain.knowledge.parsing;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

public interface DocumentParser {
    Set<String> supportedMimeTypes();
    ParsedDocument parse(InputStream input, ParseContext context) throws IOException;
}
