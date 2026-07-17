package com.hmdp.ai.infrastructure.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hmdp.ai.redis")
public class AiRedisProperties {

    private Endpoint business = new Endpoint("127.0.0.1", 6379, 0);
    private Endpoint memory = new Endpoint("127.0.0.1", 6379, 1);
    private Endpoint vector = new Endpoint("127.0.0.1", 6380, 0);

    public Endpoint getBusiness() { return business; }
    public void setBusiness(Endpoint business) { this.business = business; }
    public Endpoint getMemory() { return memory; }
    public void setMemory(Endpoint memory) { this.memory = memory; }
    public Endpoint getVector() { return vector; }
    public void setVector(Endpoint vector) { this.vector = vector; }

    public static class Endpoint {
        private String host;
        private int port;
        private String password;
        private int database;
        private boolean ssl;
        private int timeoutMillis = 3000;
        private int connectTimeoutMillis = 3000;
        private int retryAttempts = 3;
        private int retryIntervalMillis = 1000;

        public Endpoint() { }
        public Endpoint(String host, int port, int database) {
            this.host = host;
            this.port = port;
            this.database = database;
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }
        public boolean isSsl() { return ssl; }
        public void setSsl(boolean ssl) { this.ssl = ssl; }
        public int getTimeoutMillis() { return timeoutMillis; }
        public void setTimeoutMillis(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }
        public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
        public void setConnectTimeoutMillis(int connectTimeoutMillis) { this.connectTimeoutMillis = connectTimeoutMillis; }
        public int getRetryAttempts() { return retryAttempts; }
        public void setRetryAttempts(int retryAttempts) { this.retryAttempts = retryAttempts; }
        public int getRetryIntervalMillis() { return retryIntervalMillis; }
        public void setRetryIntervalMillis(int retryIntervalMillis) { this.retryIntervalMillis = retryIntervalMillis; }

        public void validate(String name) {
            if (host == null || host.trim().isEmpty()) {
                throw new IllegalStateException(name + " Redis host must not be blank");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalStateException(name + " Redis port is invalid");
            }
            if (database < 0) {
                throw new IllegalStateException(name + " Redis database must be non-negative");
            }
        }
    }
}
