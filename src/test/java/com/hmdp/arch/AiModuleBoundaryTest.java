package com.hmdp.arch;

import com.hmdp.service.ShopStatsService;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.hmdp", importOptions = ImportOption.DoNotIncludeTests.class)
class AiModuleBoundaryTest {

    @ArchTest
    static final ArchRule ai_core_should_not_depend_on_mapper_or_entity =
            classes().that(resideInAPackage("com.hmdp.ai..")
                            .and(not(resideInAPackage("com.hmdp.ai.port.adapter.."))))
                    .should().onlyDependOnClassesThat(not(resideInAPackage("com.hmdp.mapper.."))
                            .and(not(resideInAPackage("com.hmdp.entity.."))));

    @ArchTest
    static final ArchRule ai_should_not_depend_on_outer_application_layers =
            classes().that().resideInAPackage("com.hmdp.ai..")
                    .should().onlyDependOnClassesThat(not(resideInAPackage("com.hmdp.controller.."))
                            .and(not(resideInAPackage("com.hmdp.service.impl..")))
                            .and(not(resideInAPackage("com.hmdp.event.."))));

    @ArchTest
    static final ArchRule ai_should_not_depend_on_shop_stats_service =
            classes().that().resideInAPackage("com.hmdp.ai..")
                    .should().onlyDependOnClassesThat(not(assignableTo(ShopStatsService.class)));

    @ArchTest
    static final ArchRule ai_port_adapters_should_only_use_allowed_business_dependencies =
            classes().that().resideInAPackage("com.hmdp.ai.port.adapter..")
                    .should(onlyUseAllowedBusinessDependencies());

    @ArchTest
    static final ArchRule ai_slices_should_be_free_of_cycles =
            slices().matching("com.hmdp.ai.(*)..").should().beFreeOfCycles();

    private static ArchCondition<JavaClass> onlyUseAllowedBusinessDependencies() {
        Set<String> allowedPrefixes = Set.of(
                "com.hmdp.ai.",
                "com.hmdp.dto.",
                "com.hmdp.mapper.",
                "com.hmdp.entity.Blog",
                "com.hmdp.entity.Shop",
                "com.hmdp.utils.LocalCacheManager",
                "com.baomidou.mybatisplus.",
                "java.",
                "javax.",
                "org.springframework.",
                "org.slf4j.",
                "lombok.",
                "dev.langchain4j.",
                "reactor."
        );
        return new ArchCondition<>("only use allowed adapter dependencies") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getDirectDependenciesFromSelf().forEach(dependency -> {
                    String targetName = dependency.getTargetClass().getName();
                    boolean allowed = allowedPrefixes.stream().anyMatch(targetName::startsWith);
                    if (!allowed) {
                        events.add(SimpleConditionEvent.violated(item,
                                item.getName() + " depends on " + targetName + " via " + dependency.getDescription()));
                    }
                });
            }
        };
    }
}
