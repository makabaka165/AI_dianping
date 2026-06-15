package com.hmdp.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

@Data
public class BlogCreateRequest {

    @NotNull(message = "shopId is required")
    @Positive(message = "shopId must be greater than 0")
    private Long shopId;

    @NotBlank(message = "title is required")
    @Size(max = 255, message = "title length must be less than or equal to 255")
    private String title;

    @NotBlank(message = "images is required")
    @Size(max = 2048, message = "images length must be less than or equal to 2048")
    private String images;

    @NotBlank(message = "content is required")
    @Size(max = 2048, message = "content length must be less than or equal to 2048")
    private String content;
}
