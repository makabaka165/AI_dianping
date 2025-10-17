package com.hmdp;

import dev.langchain4j.data.document.Document;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RAGTTest {
    @Test
    public void testLoadDocuments() {
        try {
            ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
            List<Document> documents = loadTextDocuments(resourceResolver);

            System.out.println("加载的文档数量: " + documents.size());

            for (Document doc : documents) {
                System.out.println("文档内容预览: " + doc.text().substring(0, Math.min(100, doc.text().length())) + "...");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<Document> loadTextDocuments(ResourcePatternResolver resourceResolver) throws IOException {
        List<Document> documents = new ArrayList<>();
        
        // 使用classpath模式匹配加载所有txt和md文件
        Resource[] resources = resourceResolver.getResources("classpath*:content/**/*.txt");
        Resource[] mdResources = resourceResolver.getResources("classpath*:content/**/*.md");
        
        // 合并所有资源
        List<Resource> allResources = new ArrayList<>();
        allResources.addAll(Arrays.asList(resources));
        allResources.addAll(Arrays.asList(mdResources));

        for (Resource resource : allResources) {
            try {
                String content = org.springframework.util.StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                Document document = Document.from(content);
                documents.add(document);
                System.out.println("加载文本文档: " + resource.getFilename());
            } catch (IOException e) {
                System.err.println("无法读取文本文档: " + resource.getFilename());
                e.printStackTrace();
            }
        }

        return documents;
    }
}