package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {

    private List<T> list;

    private Integer current;

    private Integer size;

    private Long total;

    private Boolean hasMore;

    private Object cursor;

    public static <T> PageResult<T> of(List<T> list, Integer current, Integer size,
                                       Long total, Boolean hasMore, Object cursor) {
        PageResult<T> result = new PageResult<>();
        result.setList(list);
        result.setCurrent(current);
        result.setSize(size);
        result.setTotal(total);
        result.setHasMore(hasMore);
        result.setCursor(cursor);
        return result;
    }
}
