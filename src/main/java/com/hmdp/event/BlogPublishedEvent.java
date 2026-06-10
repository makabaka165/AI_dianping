package com.hmdp.event;

import lombok.Getter;

@Getter
public class BlogPublishedEvent {
    private final Long blogId;
    private final Long authorId;
    private final Long publishTimeMillis;

    public BlogPublishedEvent(Long blogId, Long authorId, Long publishTimeMillis) {
        this.blogId = blogId;
        this.authorId = authorId;
        this.publishTimeMillis = publishTimeMillis;
    }
}
