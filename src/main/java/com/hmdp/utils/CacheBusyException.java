package com.hmdp.utils;

public class CacheBusyException extends RuntimeException {

    public CacheBusyException(String message) {
        super(message);
    }
}
