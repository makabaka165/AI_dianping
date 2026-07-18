package com.hmdp.ai.infrastructure.external;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeHttpClientTest {
    private final SafeHttpClient client=new SafeHttpClient();
    @Test void rejectsLoopbackAndPrivateAddresses(){
        assertThatThrownBy(()->client.validateUri(URI.create("http://127.0.0.1/admin"),false)).hasMessage("HTTP_PRIVATE_ADDRESS_NOT_ALLOWED");
        assertThatThrownBy(()->client.validateUri(URI.create("http://10.0.0.1/admin"),false)).hasMessage("HTTP_PRIVATE_ADDRESS_NOT_ALLOWED");
        assertThatThrownBy(()->client.validateUri(URI.create("file:///etc/passwd"),false)).hasMessage("HTTP_URL_INVALID");
    }
}
