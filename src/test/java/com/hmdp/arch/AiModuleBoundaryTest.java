package com.hmdp.arch;

import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.service.ShopStatsService;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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
                            .and(not(resideInAPackage("com.hmdp.entity.."))
                                    .or(assignableTo(DocumentMetadata.class))
                                    .or(assignableTo(DocumentStatus.class))));

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

    @ArchTest
    static final ArchRule platform_runtime_must_not_depend_on_legacy_compatibility =
            classes().that(resideInAPackage("com.hmdp.ai.runtime..")
                            .or(resideInAPackage("com.hmdp.ai.domain.."))
                            .or(resideInAPackage("com.hmdp.ai.application.agent..")))
                    .should().onlyDependOnClassesThat(not(resideInAPackage("com.hmdp.ai.legacy.compatibility..")));

    @Test
    void aiTopLevelPackagesShouldStayWithinCurrentBoundarySet() throws IOException {
        Set<String> allowedTopLevelPackages = Set.of(
                "application",
                "api",
                "config",
                "domain",
                "fallback",
                "guard",
                "infra",
                "infrastructure",
                "intent",
                "legacy",
                "memory",
                "model",
                "orchestration",
                "port",
                "prompt",
                "quota",
                "retrieval",
                "runtime",
                "shared",
                "task",
                "workflow"
        );
        try (Stream<Path> paths = Files.list(Path.of("src/main/java/com/hmdp/ai"))) {
            Set<String> actualTopLevelPackages = paths
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(actualTopLevelPackages).isSubsetOf(allowedTopLevelPackages);
            assertThat(actualTopLevelPackages)
                    .doesNotContain("architecture", "facade", "command", "pipeline", "plugin");
        }
    }

    @Test
    void pomShouldNotIntroduceNewSensitiveDependencyFamilies() throws IOException {
        String pom = Files.readString(Path.of("pom.xml")).toLowerCase();

        assertThat(pom)
                .doesNotContain("kafka")
                .doesNotContain("rabbitmq")
                .doesNotContain("elasticsearch")
                .doesNotContain("sentinel")
                .doesNotContain("opentelemetry");
    }

    @Test
    void dtoSerializationTestShouldCoverMemoryBoundary() throws IOException {
        String serializationTest = Files.readString(
                Path.of("src/test/java/com/hmdp/dto/ai/ShopAIResponseSerializationTest.java"));

        assertThat(serializationTest)
                .contains("ShopAIResponse")
                .contains("ShopSummaryResult")
                .contains("ShopAIStreamEvent")
                .contains("memoryId")
                .contains("memoryKey")
                .contains("hmdp:memory");
    }

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
