package com.hmdp.ai.guard;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactionServiceTest {
    @Test void redactsPhoneEmailAndIdentityNumber() {
        PiiRedactionService service=new PiiRedactionService(new PiiDetectionService());
        String value=service.redact("mail a@example.com phone 13812345678 id 11010119900101123X");
        assertThat(value).doesNotContain("a@example.com","13812345678","11010119900101123X").contains("[REDACTED_EMAIL]");
    }
}
