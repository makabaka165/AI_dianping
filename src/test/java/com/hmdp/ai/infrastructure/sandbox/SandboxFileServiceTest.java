package com.hmdp.ai.infrastructure.sandbox;
import org.junit.jupiter.api.Test;import java.nio.file.Paths;import static org.assertj.core.api.Assertions.assertThatThrownBy;
class SandboxFileServiceTest {@Test void rejectsTraversal(){SandboxFileService files=new SandboxFileService();assertThatThrownBy(()->files.resolve(Paths.get("target/sandbox").toAbsolutePath().normalize(),"../secret")).hasMessage("SANDBOX_PATH_TRAVERSAL");}}
