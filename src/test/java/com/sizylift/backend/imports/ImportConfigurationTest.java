package com.sizylift.backend.imports;

import com.sizylift.backend.imports.config.ImportConfiguration;
import com.sizylift.backend.imports.model.DeepSeekProgramParser;
import com.sizylift.backend.imports.model.ProgramParserModel;
import com.sizylift.backend.imports.service.ImportFailure;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

class ImportConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(application -> application.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(ImportConfiguration.class)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

    @Test
    void disabledImportDoesNotRequireAKeyOrProviderSettings() {
        context.run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(ProgramParserModel.class);
            assertThatThrownBy(() -> application.getBean(ProgramParserModel.class).parse(null))
                    .isInstanceOf(ImportFailure.class);
        });
    }

    @Test
    void enabledImportBuildsTheRealSdkClientWithoutMakingANetworkRequest() {
        configured("test-only").run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(ProgramParserModel.class);
            assertThat(application.getBean(ProgramParserModel.class)).isInstanceOf(DeepSeekProgramParser.class);
        });
    }

    @Test
    void enabledImportRejectsAnEmptyKey() {
        configured("").run(application -> {
            assertThat(application).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(application.getStartupFailure()).getMessage())
                    .contains("нужен ключ");
        });
    }

    @Test
    void requestTimeoutMustBeShorterThanTheWorkerLease() {
        configured("test-only").withPropertyValues("sizylift.imports.lease=1m").run(application -> {
            assertThat(application).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(application.getStartupFailure()).getMessage())
                    .contains("Аренда");
        });
    }

    private ApplicationContextRunner configured(String key) {
        return context.withPropertyValues("sizylift.imports.enabled=true", "sizylift.imports.deepseek.api-key=" + key,
                "sizylift.imports.deepseek.url=https://deepseek.test", "sizylift.imports.deepseek.model=deepseek-flash",
                "sizylift.imports.deepseek.timeout=5m");
    }
}
