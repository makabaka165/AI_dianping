package com.hmdp.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.SaTokenException;
import com.hmdp.common.ErrorCode;
import com.hmdp.ai.api.dto.AiErrorDetails;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.infra.AiLogSanitizer;
import com.hmdp.dto.Result;
import com.hmdp.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(AiPlatformException.class)
    public Result handleAiPlatformException(AiPlatformException e) {
        Object details = e.getIssues().isEmpty() ? null : new AiErrorDetails(e.getIssues());
        return Result.fail(e.getErrorCode(), e.getMessage(), details);
    }

    @ExceptionHandler(NotLoginException.class)
    public Result handleNotLoginException(NotLoginException e) {
        return Result.fail(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(NotPermissionException.class)
    public Result handleNotPermissionException(NotPermissionException e) {
        return Result.fail(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(SaTokenException.class)
    public Result handleSaTokenException(SaTokenException e) {
        return Result.fail(ErrorCode.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public Result handleBusinessException(BusinessException e) {
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        return Result.fail(ErrorCode.PARAM_ERROR, firstBindingError(e.getBindingResult()));
    }

    @ExceptionHandler(BindException.class)
    public Result handleBindException(BindException e) {
        return Result.fail(ErrorCode.PARAM_ERROR, firstBindingError(e.getBindingResult()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        return Result.fail(ErrorCode.PARAM_ERROR, "request body is invalid");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result handleConstraintViolationException(ConstraintViolationException e) {
        return Result.fail(ErrorCode.PARAM_ERROR, e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(ErrorCode.PARAM_ERROR.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result handleIllegalArgumentException(IllegalArgumentException e) {
        return Result.fail(ErrorCode.PARAM_ERROR, safeParameterMessage(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public Result handleIllegalStateException(IllegalStateException e) {
        log.error(e.toString(), e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    private String firstBindingError(BindingResult bindingResult) {
        return bindingResult.getAllErrors().stream()
                .findFirst()
                .map(ObjectError::getDefaultMessage)
                .orElse(ErrorCode.PARAM_ERROR.getMessage());
    }

    private String safeParameterMessage(String message) {
        if (message == null || message.trim().isEmpty() || AiLogSanitizer.containsSensitive(message)) {
            return ErrorCode.PARAM_ERROR.getMessage();
        }
        return AiLogSanitizer.safe(message, 100);
    }
}
