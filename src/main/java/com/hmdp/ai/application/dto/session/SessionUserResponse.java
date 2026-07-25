package com.hmdp.ai.application.dto.session;

public final class SessionUserResponse {
    private final String id;
    private final String nickName;
    private final String icon;

    public SessionUserResponse(String id, String nickName, String icon) {
        this.id = id;
        this.nickName = nickName;
        this.icon = icon;
    }

    public String getId() {
        return id;
    }

    public String getNickName() {
        return nickName;
    }

    public String getIcon() {
        return icon;
    }
}
