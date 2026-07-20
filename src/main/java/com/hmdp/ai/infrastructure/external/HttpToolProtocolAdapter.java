package com.hmdp.ai.infrastructure.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.tool.*;
import com.hmdp.ai.infrastructure.model.SecretResolutionService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
public class HttpToolProtocolAdapter implements ToolProtocolAdapter {
    private final SafeHttpClient http;private final SecretResolutionService secrets;private final ObjectMapper mapper;
    public HttpToolProtocolAdapter(SafeHttpClient http,SecretResolutionService secrets,ObjectMapper mapper){this.http=http;this.secrets=secrets;this.mapper=mapper;}
    @Override public ToolProtocol protocol(){return ToolProtocol.HTTP;}
    @Override public ToolResult execute(ToolDefinition definition,ToolInvocation invocation,JsonNode config){long started=System.currentTimeMillis();try{
        String host=required(config,"allowedHost");String scheme=config.path("scheme").asText("https");int port=config.path("port").asInt(-1);
        String path=template(required(config,"pathTemplate"),invocation.getInput());String query=query(config.path("queryTemplate"),invocation.getInput());
        URI uri=new URI(scheme,null,host,port,path,query,null);Map<String,String>headers=headers(config.path("headerTemplate"),invocation.getInput());
        if(config.hasNonNull("secretRef")){String header=config.path("secretHeader").asText("Authorization");String prefix=config.path("secretPrefix").asText("Bearer ");headers.put(header,prefix+secrets.resolve(config.path("secretRef").asText()));}
        String method=config.path("method").asText("POST").toUpperCase(Locale.ROOT);byte[]body=body(method,config,invocation.getInput());
        OutboundHttpResponse response=http.execute(new OutboundHttpRequest(uri,method,headers,body,Duration.ofMillis(definition.getTimeoutMs()),Math.max(1024,config.path("maxResponseBytes").asInt(1024*1024)),contentTypes(config),config.path("allowPrivateNetwork").asBoolean(false)),invocation.getContext().getRunId());
        if(response.getStatusCode()<200||response.getStatusCode()>=300)return ToolResult.failure(ToolCallStatus.FAILED,"HTTP_TOOL_STATUS_"+response.getStatusCode(),"HTTP tool returned a non-success status",response.getStatusCode()>=500);
        JsonNode data=response.getContentType().contains("json")?mapper.readTree(response.getBody()):mapper.createObjectNode().put("body",response.bodyAsUtf8()).put("contentType",response.getContentType());
        return ToolResult.success(data,System.currentTimeMillis()-started);
    }catch(java.util.concurrent.CancellationException e){return ToolResult.failure(ToolCallStatus.CANCELLED,"RUN_CANCELLED","run cancelled",false);}catch(Exception e){String code=code(e,"HTTP_TOOL_FAILED");return ToolResult.failure(ToolCallStatus.FAILED,code,"HTTP tool execution failed",retryable(code));}}
    private String required(JsonNode c,String name){String v=c.path(name).asText();if(v.trim().isEmpty())throw new IllegalArgumentException("HTTP_TOOL_CONFIG_INVALID");return v;}
    private String template(String value,JsonNode input){String result=value;java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\{([A-Za-z0-9_]+)}").matcher(value);StringBuffer b=new StringBuffer();while(m.find()){JsonNode v=input.get(m.group(1));if(v==null||v.isContainerNode())throw new IllegalArgumentException("HTTP_TOOL_TEMPLATE_VALUE_MISSING");m.appendReplacement(b,java.util.regex.Matcher.quoteReplacement(URLEncoder.encode(v.asText(),StandardCharsets.UTF_8)));}m.appendTail(b);return b.toString();}
    private String query(JsonNode node,JsonNode input){if(!node.isObject())return null;List<String>values=new ArrayList<>();node.fields().forEachRemaining(e->values.add(encode(e.getKey())+"="+encode(template(e.getValue().asText(),input))));return String.join("&",values);}
    private Map<String,String>headers(JsonNode node,JsonNode input){Map<String,String>result=new LinkedHashMap<>();result.put("Accept","application/json");if(node.isObject())node.fields().forEachRemaining(e->result.put(e.getKey(),template(e.getValue().asText(),input)));return result;}
    private byte[]body(String method,JsonNode config,JsonNode input)throws Exception{if(method.equals("GET")||method.equals("DELETE"))return new byte[0];String value=config.hasNonNull("bodyTemplate")?template(config.path("bodyTemplate").asText(),input):mapper.writeValueAsString(input);return value.getBytes(StandardCharsets.UTF_8);}
    private Set<String>contentTypes(JsonNode config){Set<String>types=new LinkedHashSet<>();JsonNode n=config.path("allowedContentTypes");if(n.isArray())n.forEach(v->types.add(v.asText()));if(types.isEmpty()){types.add("application/json");types.add("text/plain");}return types;}
    private String encode(String v){return URLEncoder.encode(v,StandardCharsets.UTF_8);}
    private String code(Exception e,String fallback){Throwable c=e;while(c!=null){String m=c.getMessage();if(m!=null&&m.matches("[A-Z0-9_]+"))return m;c=c.getCause();}return fallback;}
    private boolean retryable(String code){return code.equals("HTTP_REQUEST_FAILED")||code.startsWith("HTTP_TOOL_STATUS_5");}
}
