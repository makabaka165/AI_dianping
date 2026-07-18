package com.hmdp.ai.runtime.tool;
import java.lang.annotation.*;
@Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME) public @interface AgentSkill {String code();int version() default 1;}
