package com.hmdp.ai.runtime.knowledge;

import com.hmdp.ai.application.knowledge.KnowledgeIngestionApplicationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "rag.data", name = "auto-import", havingValue = "true")
public class KnowledgeBootstrapRunner implements ApplicationRunner {
    private final KnowledgeIngestionApplicationService ingestion;

    public KnowledgeBootstrapRunner(KnowledgeIngestionApplicationService ingestion) {
        this.ingestion = ingestion;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> patterns = new LinkedHashMap<>();
        patterns.put("classpath*:content/**/*.txt", "text/plain");
        patterns.put("classpath*:content/**/*.md", "text/markdown");
        patterns.put("classpath*:content/**/*.pdf", "application/pdf");
        patterns.put("classpath*:content/**/*.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        patterns.put("classpath*:content/**/*.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        patterns.forEach((pattern, mime) -> importPattern(resolver, pattern, mime));
    }

    private void importPattern(PathMatchingResourcePatternResolver resolver, String pattern, String mime) {
        try {
            for (Resource resource : resolver.getResources(pattern)) {
                try (InputStream input = resource.getInputStream()) {
                    String fileName = resource.getFilename() == null ? "bootstrap-document" : resource.getFilename();
                    ingestion.uploadSystem("default", "default", "kb-shop-enterprise", 1, fileName,
                            fileName, mime, input.readAllBytes());
                } catch (Exception e) {
                    log.warn("Knowledge bootstrap resource failed, resource={}, errorCode={}",
                            resource.getDescription(), safeCode(e));
                }
            }
        } catch (Exception e) {
            log.warn("Knowledge bootstrap scan failed, pattern={}, errorCode={}", pattern, safeCode(e));
        }
    }

    private String safeCode(Exception e) {
        String message = String.valueOf(e.getMessage());
        return message.matches("[A-Z0-9_:.-]+") ? message : e.getClass().getSimpleName();
    }
}
