package com.hmdp.ai.runtime.prompt;

import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.guard.PiiRedactionService;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PromptRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_.$-]{0,127})\\s*}}|\\$\\{\\s*([A-Za-z][A-Za-z0-9_.$-]{0,127})\\s*}");
    private final PromptVariableValidationService validation;
    private final PromptVariableResolver resolver;
    private final PiiRedactionService redactor;

    public PromptRenderer(PromptVariableValidationService validation, PromptVariableResolver resolver,
                          PiiRedactionService redactor) {
        this.validation = validation;
        this.resolver = resolver;
        this.redactor = redactor;
    }

    public RenderedPrompt render(PromptVersion prompt, PromptRenderContext context, String extraInstruction) {
        validation.require(prompt, context);
        String system = renderText(prompt.getSystemPrompt(), context);
        StringBuilder user = new StringBuilder();
        append(user, prompt.getTaskPrompt(), context);
        append(user, prompt.getToolInstruction(), context);
        append(user, prompt.getRetrievalInstruction(), context);
        append(user, prompt.getOutputInstruction(), context);
        append(user, extraInstruction, context);
        untrusted(user, "TOOL_RESULTS", context.getVariables().get("toolResults"));
        untrusted(user, "RETRIEVAL_RESULTS", context.getVariables().get("retrievalResults"));
        untrusted(user, "MEMORY_RECALL", context.getVariables().get("memoryRecall"));
        String summary = redactor.redact(limit(system + "\n" + user, 1000));
        return new RenderedPrompt(system, user.toString().trim(), summary);
    }

    private void append(StringBuilder target, String value, PromptRenderContext context) {
        if (value == null || value.trim().isEmpty()) return;
        String rendered = renderText(value, context);
        target.append('\n').append(rendered).append('\n');
    }

    private void untrusted(StringBuilder target, String name, Object value) {
        if (value == null) return;
        target.append("\n<UNTRUSTED_DATA type=\"").append(name).append("\">\n")
                .append(String.valueOf(value)).append("\n</UNTRUSTED_DATA>\n");
    }

    private String renderText(String text, PromptRenderContext context) {
        if (text == null) return "";
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            Object value = resolver.resolve(name, context);
            if (value == null) throw new com.hmdp.ai.shared.exception.AiPlatformException(
                    com.hmdp.common.ErrorCode.PROMPT_VARIABLE_MISSING, "PROMPT_VARIABLE_MISSING: " + name);
            String replacement = String.valueOf(value).replace("env:", "[REDACTED_REF]:");
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
