package com.hmdp.ai.infrastructure.external;

import org.springframework.stereotype.Component;

@Component public class WebContentExtractor {public String extract(String html,int maxChars){if(html==null)return "";String text=html.replaceAll("(?is)<script[^>]*>.*?</script>"," ").replaceAll("(?is)<style[^>]*>.*?</style>"," ").replaceAll("(?s)<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replaceAll("\\s+"," ").trim();return text.length()<=maxChars?text:text.substring(0,maxChars);}}
