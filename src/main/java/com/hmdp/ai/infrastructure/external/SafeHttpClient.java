package com.hmdp.ai.infrastructure.external;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@Component
public class SafeHttpClient {
    private final HttpClient client;
    public SafeHttpClient(){this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());}
    SafeHttpClient(HttpClient client){this.client=client;}

    public OutboundHttpResponse execute(OutboundHttpRequest request){
        try{
            validateUri(request.getUri(),request.isAllowPrivateNetwork());
            HttpRequest.Builder builder=HttpRequest.newBuilder(request.getUri()).timeout(request.getTimeout());
            request.getHeaders().forEach(builder::header);
            String method=request.getMethod().toUpperCase(Locale.ROOT);
            HttpRequest.BodyPublisher body=request.getBody().length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(request.getBody());
            builder.method(method,body);
            HttpResponse<InputStream> response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            if(response.statusCode()>=300&&response.statusCode()<400)throw new IllegalArgumentException("HTTP_REDIRECT_NOT_ALLOWED");
            String contentType=response.headers().firstValue("content-type").orElse("application/octet-stream").split(";",2)[0].trim().toLowerCase(Locale.ROOT);
            if(!request.getAllowedContentTypes().isEmpty()&&request.getAllowedContentTypes().stream().map(v->v.toLowerCase(Locale.ROOT)).noneMatch(contentType::equals))throw new IllegalArgumentException("HTTP_CONTENT_TYPE_NOT_ALLOWED");
            byte[] bytes=readLimited(response.body(),request.getMaxResponseBytes());
            return new OutboundHttpResponse(response.statusCode(),contentType,bytes);
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalStateException("HTTP_REQUEST_FAILED",e);}
    }

    void validateUri(URI uri,boolean allowPrivateNetwork){
        if(uri==null||uri.getHost()==null)throw new IllegalArgumentException("HTTP_URL_INVALID");
        String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
        if(!scheme.equals("http")&&!scheme.equals("https"))throw new IllegalArgumentException("HTTP_SCHEME_NOT_ALLOWED");
        if(uri.getUserInfo()!=null)throw new IllegalArgumentException("HTTP_USERINFO_NOT_ALLOWED");
        try{for(InetAddress address:InetAddress.getAllByName(uri.getHost())){
            if(!allowPrivateNetwork&&(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()))throw new IllegalArgumentException("HTTP_PRIVATE_ADDRESS_NOT_ALLOWED");
        }}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("HTTP_HOST_RESOLUTION_FAILED",e);}
    }

    private byte[] readLimited(InputStream input,int max)throws Exception{int limit=Math.max(1,max);try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream(Math.min(limit,8192))){byte[]buffer=new byte[8192];int total=0,read;while((read=in.read(buffer))>=0){total+=read;if(total>limit)throw new IllegalArgumentException("HTTP_RESPONSE_TOO_LARGE");out.write(buffer,0,read);}return out.toByteArray();}}
}
