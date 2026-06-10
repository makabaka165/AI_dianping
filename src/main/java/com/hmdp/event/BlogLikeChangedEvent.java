package com.hmdp.event;

import lombok.Getter;

@Getter
public class BlogLikeChangedEvent {
    private final Long blogId;
    private final Long userId;
    private final boolean liked;
    private final long eventTimeMillis;

    public BlogLikeChangedEvent(Long blogId, Long userId, boolean liked, long eventTimeMillis) {
        this.blogId = blogId;
        this.userId = userId;
        this.liked = liked;
        this.eventTimeMillis = eventTimeMillis;
    }
}
