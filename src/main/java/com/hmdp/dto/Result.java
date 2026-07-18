package com.hmdp.dto;

import com.hmdp.common.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private Boolean success;
    private Integer code;
    private String errorMsg;
    private Object data;
    private Long total;

    public static Result ok(){
        return new Result(true, ErrorCode.SUCCESS.getCode(), null, null, null);
    }
    public static Result ok(Object data){
        return new Result(true, ErrorCode.SUCCESS.getCode(), null, data, null);
    }
    public static Result ok(List<?> data, Long total){
        return new Result(true, ErrorCode.SUCCESS.getCode(), null, data, total);
    }
    public static Result fail(String errorMsg){
        return fail(ErrorCode.BUSINESS_ERROR, errorMsg);
    }

    public static Result fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    public static Result fail(ErrorCode errorCode, String errorMsg){
        return new Result(false, errorCode.getCode(), errorMsg, null, null);
    }

    public static Result fail(ErrorCode errorCode, String errorMsg, Object data){
        return new Result(false, errorCode.getCode(), errorMsg, data, null);
    }
}
