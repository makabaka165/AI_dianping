package com.hmdp.ai.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
/**
 * 存储到redis中的轻量级消息对象
 * key是由唯一标识的ChatMemoryMemory生成
 * value存储的就是下面的对象
 * 但是针对langchain4j的chatmessage类型的话，需要在取记忆的时候进行转换
 * 比如：
 *  [
 *     {"type":"USER","text":"今天天气如何？"},
 *     {"type":"AI","text":"今天天气晴朗，温度25℃。"}
 *   ]
 */
public class SimpleChatMessage {
    private String type;
    private String text;
    private Long timestamp;

    public SimpleChatMessage(String type, String text) {
        this.type = type;
        this.text = text;
        this.timestamp = System.currentTimeMillis();
    }
}
