package com.hmdp.ai.runtime.retrieval;
import org.springframework.stereotype.Component;
@Component public class ContextCompressor {public String compress(String text,String query,int maximum){if(text==null)return "";if(text.length()<=maximum)return text;int index=query==null?-1:text.toLowerCase(java.util.Locale.ROOT).indexOf(query.toLowerCase(java.util.Locale.ROOT));int start=index<0?0:Math.max(0,index-maximum/3);int end=Math.min(text.length(),start+maximum);return (start>0?"...":"")+text.substring(start,end)+(end<text.length()?"...":"");}}
