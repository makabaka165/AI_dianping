package com.hmdp.ai.domain.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

@Component
public class ConditionDslEvaluator {
    private final ObjectMapper mapper;
    public ConditionDslEvaluator(ObjectMapper mapper){this.mapper=mapper;}

    public boolean evaluate(String conditionJson, Map<String,Object> variables){
        if(conditionJson==null||conditionJson.trim().isEmpty()) return true;
        try{return truth(eval(mapper.readTree(conditionJson),variables));}
        catch(Exception e){throw new IllegalArgumentException("workflow condition is invalid",e);}
    }

    private Object eval(JsonNode n,Map<String,Object> v){
        if(n==null||n.isNull()) return null;
        if(n.isValueNode()) return n.isNumber()?n.decimalValue():n.isBoolean()?n.booleanValue():n.asText();
        if(n.isArray()){java.util.List<Object> a=new java.util.ArrayList<>();n.forEach(x->a.add(eval(x,v)));return a;}
        if(n.has("var")) return path(v,n.get("var").asText());
        Iterator<String> names=n.fieldNames(); if(!names.hasNext()) throw new IllegalArgumentException("empty condition");
        String op=names.next(); JsonNode arg=n.get(op);
        switch(op){
            case "eq": return cmpArg(arg,v)==0; case "ne": return cmpArg(arg,v)!=0;
            case "gt": return cmpArg(arg,v)>0; case "gte": return cmpArg(arg,v)>=0;
            case "lt": return cmpArg(arg,v)<0; case "lte": return cmpArg(arg,v)<=0;
            case "contains": {Object[] p=pair(arg,v);return p[0]!=null&&p[1]!=null&&String.valueOf(p[0]).contains(String.valueOf(p[1]));}
            case "in": {Object[] p=pair(arg,v);return p[1] instanceof java.util.Collection&&((java.util.Collection<?>)p[1]).contains(p[0]);}
            case "and": for(JsonNode x:arg)if(!truth(eval(x,v)))return false;return true;
            case "or": for(JsonNode x:arg)if(truth(eval(x,v)))return true;return false;
            case "not": return !truth(eval(arg,v)); case "exists": return eval(arg,v)!=null;
            case "empty": {Object x=eval(arg,v);return x==null||String.valueOf(x).isEmpty()||(x instanceof java.util.Collection&&((java.util.Collection<?>)x).isEmpty());}
            default: throw new IllegalArgumentException("operator is not allowed: "+op);
        }
    }
    private int cmpArg(JsonNode a,Map<String,Object> v){Object[]p=pair(a,v);if(p[0] instanceof Number&&p[1] instanceof Number)return new BigDecimal(p[0].toString()).compareTo(new BigDecimal(p[1].toString()));return String.valueOf(p[0]).compareTo(String.valueOf(p[1]));}
    private Object[] pair(JsonNode a,Map<String,Object> v){if(!a.isArray()||a.size()!=2)throw new IllegalArgumentException("binary operator requires two operands");return new Object[]{eval(a.get(0),v),eval(a.get(1),v)};}
    private boolean truth(Object x){return x instanceof Boolean?(Boolean)x:x!=null&&!"".equals(x);}
    private Object path(Map<String,Object> v,String p){Object cur=v;for(String s:p.split("\\.")){if(!(cur instanceof Map))return null;cur=((Map<?,?>)cur).get(s);}return cur;}
}
