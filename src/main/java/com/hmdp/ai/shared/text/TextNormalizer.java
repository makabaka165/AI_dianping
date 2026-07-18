package com.hmdp.ai.shared.text;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class TextNormalizer {
    public String normalize(String value){if(value==null)return "";return Normalizer.normalize(value,Normalizer.Form.NFKC).replace('\u00A0',' ').replaceAll("[\\t\\x0B\\f\\r]+"," ").replaceAll("[ ]{2,}"," ").replaceAll("\\n{3,}","\n\n").trim();}
    public String searchText(String value){String normalized=normalize(value).toLowerCase(Locale.ROOT);List<String> tokens=new ArrayList<>();StringBuilder chinese=new StringBuilder(),word=new StringBuilder();for(int i=0;i<normalized.length();i++){char ch=normalized.charAt(i);if(isChinese(ch)){flushWord(word,tokens);chinese.append(ch);continue;}flushChinese(chinese,tokens);if(Character.isLetterOrDigit(ch))word.append(ch);else flushWord(word,tokens);}flushChinese(chinese,tokens);flushWord(word,tokens);return String.join(" ",tokens).replaceAll("\\s+"," ").trim();}
    private void flushChinese(StringBuilder value,List<String> tokens){if(value.length()==0)return;if(value.length()==1)tokens.add(value.toString());else for(int i=0;i<value.length()-1;i++)tokens.add(value.substring(i,i+2));value.setLength(0);}
    private void flushWord(StringBuilder value,List<String> tokens){if(value.length()>0){tokens.add(value.toString());value.setLength(0);}}
    private boolean isChinese(char ch){return Character.UnicodeScript.of(ch)==Character.UnicodeScript.HAN;}
}
