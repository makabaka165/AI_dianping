package com.hmdp.ai.guard;

import com.hmdp.ai.fallback.FallbackReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 模型治理模板：统一封装「生成 -> 质量校验 -> 一次修复 -> 复校验 -> 降级」控制流。
 * 仅负责模型调用的治理控制流；不负责上下文构造、证据充分性判断、缓存、记忆写入，
 * 这些业务差异保留在各 Workflow 中。
 */
@Component
@Slf4j
public class GovernedGeneration {

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingFunction<A, R> {
        R apply(A a) throws Exception;
    }

    /**
     * @param generate   模型生成调用（允许抛受检异常）
     * @param repair     质量不达标时的一次修复调用，入参为拒绝原因
     * @param validate   质量校验
     * @param fallback   最终降级结果（生成异常或两次校验均不通过时）
     * @param onAccepted 校验通过后的后处理（如 postProcess、置 degraded=false）；无后处理传 UnaryOperator.identity()
     */
    public <T> GovernedResult<T> run(
            ThrowingSupplier<T> generate,
            ThrowingFunction<String, T> repair,
            Function<T, QualityCheck> validate,
            Supplier<T> fallback,
            UnaryOperator<T> onAccepted) {
        return runWithReason(generate, repair, validate, ignored -> fallback.get(), onAccepted);
    }

    public <T> GovernedResult<T> runWithReason(
            ThrowingSupplier<T> generate,
            ThrowingFunction<String, T> repair,
            Function<T, QualityCheck> validate,
            Function<FallbackReason, T> fallback,
            UnaryOperator<T> onAccepted) {
        try {
            T result = generate.get();
            QualityCheck quality = validate.apply(result);
            if (!quality.pass()) {
                result = repair.apply(quality.getReason());
                quality = validate.apply(result);
                if (!quality.pass()) {
                    return GovernedResult.degraded(fallback.apply(FallbackReason.QUALITY_REJECTED),
                            FallbackReason.QUALITY_REJECTED);
                }
            }
            return GovernedResult.accepted(onAccepted.apply(result));
        } catch (Exception e) {
            log.warn("governed generation degraded due to exception", e);
            return GovernedResult.degraded(fallback.apply(FallbackReason.MODEL_UNAVAILABLE),
                    FallbackReason.MODEL_UNAVAILABLE);
        }
    }

    public static final class GovernedResult<T> {
        private final T value;
        private final boolean degraded;
        private final FallbackReason fallbackReason;

        private GovernedResult(T value, boolean degraded, FallbackReason fallbackReason) {
            this.value = value;
            this.degraded = degraded;
            this.fallbackReason = fallbackReason;
        }

        static <T> GovernedResult<T> accepted(T value) {
            return new GovernedResult<>(value, false, null);
        }

        static <T> GovernedResult<T> degraded(T value, FallbackReason fallbackReason) {
            return new GovernedResult<>(value, true, fallbackReason);
        }

        public T getValue() {
            return value;
        }

        public boolean isDegraded() {
            return degraded;
        }

        public FallbackReason getFallbackReason() {
            return fallbackReason;
        }
    }
}
