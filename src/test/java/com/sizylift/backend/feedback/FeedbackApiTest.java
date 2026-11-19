package com.sizylift.backend.feedback;

import java.time.Clock;
import java.time.Instant;

import com.sizylift.backend.feedback.controller.FeedbackController;
import com.sizylift.backend.feedback.service.FeedbackService;
import com.sizylift.backend.security.SecurityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FeedbackController.class, properties = {
        "sizylift.feedback.from=no-reply@example.test",
        "sizylift.feedback.recipient=owner@example.test"
})
@Import({FeedbackService.class, SecurityConfiguration.class})
class FeedbackApiTest {
    private static final String PATH = FeedbackController.PATH;
    private static final String VALID_MESSAGE = """
            {"source":"PWA","message":"  Добавьте фильтры.  "}
            """;

    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean MailSender mail;

    @BeforeEach
    void time() {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-19T00:00:00Z"));
    }

    @Test
    void anonymousPostSendsFeedbackToConfiguredRecipient() throws Exception {
        mvc.perform(submit(VALID_MESSAGE, "192.0.2.1")).andExpect(status().isNoContent());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());

        var captured = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(captured.capture());
        var message = captured.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@example.test");
        assertThat(message.getTo()).containsExactly("owner@example.test");
        assertThat(message.getText()).isEqualTo("Источник: PWA\n\nДобавьте фильтры.");
    }

    @Test
    void rejectsInvalidOrOversizedInputBeforeSending() throws Exception {
        mvc.perform(submit("""
                {"source":"PWA","message":"   "}
                """, "192.0.2.2")).andExpect(status().isBadRequest());
        mvc.perform(submit("""
                {"source":"UNKNOWN","message":"Test"}
                """, "192.0.2.2")).andExpect(status().isBadRequest());
        mvc.perform(submit("""
                {"source":"WEB","message":"%s"}
                """.formatted("a".repeat(2001)), "192.0.2.2"))
                .andExpect(status().isBadRequest());
        mvc.perform(submit("a".repeat(17 * 1024), "192.0.2.2")).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(mail);
    }

    @Test
    void returnsUnavailableWhenSmtpRejectsTheMessage() throws Exception {
        doThrow(new MailSendException("Unavailable")).when(mail).send(any(SimpleMailMessage.class));
        mvc.perform(submit(VALID_MESSAGE, "192.0.2.3")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void limitsRepeatedAttemptsFromOneClientAndAllowsAnother() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(submit(VALID_MESSAGE, "192.0.2.4")).andExpect(status().isNoContent());
        }
        mvc.perform(submit(VALID_MESSAGE, "192.0.2.4")).andExpect(status().isTooManyRequests());
        mvc.perform(submit(VALID_MESSAGE, "192.0.2.5")).andExpect(status().isNoContent());
    }

    private static MockHttpServletRequestBuilder submit(String json, String address) {
        return post(PATH).contentType(MediaType.APPLICATION_JSON).content(json).with(request -> {
            request.setRemoteAddr(address);
            return request;
        });
    }
}
