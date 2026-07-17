package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_CODE_COOLDOWN_KEY = "login:code:cooldown:";
    public static final String LOGIN_CODE_DAILY_KEY = "login:code:daily:";
    public static final String LOGIN_CODE_IP_MINUTE_KEY = "login:code:ip:minute:";
    public static final String LOGIN_FAIL_COUNT_KEY = "login:fail:count:";
    public static final String LOGIN_BLOCK_KEY = "login:block:";
    public static final Long LOGIN_CODE_COOLDOWN_SECONDS = 60L;
    public static final Long LOGIN_CODE_DAILY_LIMIT = 10L;
    public static final Long LOGIN_CODE_IP_MINUTE_LIMIT = 30L;
    public static final Long LOGIN_FAIL_LIMIT = 5L;
    public static final Long LOGIN_PHONE_FAIL_LIMIT = 20L;
    public static final Long LOGIN_FAIL_WINDOW_MINUTES = 15L;
    public static final Long LOGIN_BLOCK_MINUTES = 15L;
    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final Long CACHE_SHOP_TYPE_TTL = 1440L;
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop-type:list";
    public static final String CACHE_SHOP_TYPE_VERSION_KEY = "cache:shop-type:version";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String SECKILL_BEGIN_KEY = "seckill:begin:";
    public static final String SECKILL_END_KEY = "seckill:end:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String BLOG_HOT_KEY = "blog:hot";
    public static final String USER_BRIEF_KEY = "user:brief:";
    public static final Long USER_BRIEF_TTL = 30L;
    public static final String FEED_KEY = "feed:";
    public static final String FOLLOW_KEY = "follows:";
    public static final String FOLLOW_LOADED_KEY = "follows:loaded:";
    public static final Long FOLLOW_CACHE_TTL = 30L;
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
