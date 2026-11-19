package com.sizylift.backend.voice.config;

import java.time.Clock;

import com.sizylift.backend.voice.model.GapResolver;
import com.sizylift.backend.voice.service.VoicePipeline;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(application -> application.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(VoiceConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

    @Test
    void defaultModelAllowsVoiceToBeDisabledWithoutProviderKeys() {
        context.run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(VoicePipeline.class);
            assertThat(application.getBean(VoiceProperties.class).parser()).isEqualTo(VoiceProperties.Parser.MODEL);
        });
    }

    @Test
    void enabledModelDoesNotSetUpTheLegacyFallback() {
        context.withPropertyValues("sizylift.voice.enabled=true", "sizylift.voice.deepgram.api-key=test-only",
                "sizylift.voice.fallback.api-key=test-only").run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(VoicePipeline.class);
            assertThat(application.getBean(GapResolver.class).enabled()).isFalse();
        });
    }
}
