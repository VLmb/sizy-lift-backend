package com.sizylift.backend.feedback;

import java.util.Map;

import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.feedback.controller.FeedbackController;
import com.sizylift.backend.identity.deletion.AccountDeletionRepository;
import com.sizylift.backend.identity.service.ExternalIdentity;
import com.sizylift.backend.identity.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "sizylift.feedback.from=no-reply@example.test",
        "sizylift.feedback.recipient=owner@example.test"
})
@AutoConfigureMockMvc
class FeedbackApiTest extends PostgresIntegrationTest {
    private static final String PATH = FeedbackController.PATH;
    private static final String VALID_MESSAGE = """
            {"source":"PWA","message":"  Добавьте фильтры.  "}
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountService accounts;
    @Autowired AccountDeletionRepository deletion;
    @Autowired TransactionTemplate transactions;
    @MockitoBean MailSender mail;

    @Test
    void anonymousPostSendsFeedbackToConfiguredRecipient() throws Exception {
        mvc.perform(submit(VALID_MESSAGE, "192.0.2.1")).andExpect(status().isNoContent());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());

        var captured = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(captured.capture());
        var message = captured.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@example.test");
        assertThat(message.getTo()).containsExactly("owner@example.test");
        assertThat(message.getText()).isEqualTo("Источник: PWA\nАнонимный отзыв\n\nДобавьте фильтры.");
        assertThat(saved("Добавьте фильтры.")).containsEntry("source", "PWA").containsEntry("mail_status", "SENT")
                .containsEntry("account_id", null).containsEntry("email", null);
    }

    @Test
    void usesVerifiedClaimsAndKeepsTheReviewAfterAccountDeletion() throws Exception {
        var issuer = "https://identity.example.test";
        var account = accounts.resolve(new ExternalIdentity(issuer, "signed-feedback", "athlete@example.test", true));
        mvc.perform(submit("""
                {"source":"APP","message":"Добавьте поиск.","email":"forged@example.test"}
                """, "192.0.2.6").with(jwt().jwt(token -> token.issuer(issuer).subject("signed-feedback")
                .claim("email", "athlete@example.test").claim("email_verified", true)
                .claim("preferred_username", "athlete").claim("name", "Имя Спортсмена"))))
                .andExpect(status().isNoContent());

        assertThat(saved("Добавьте поиск.")).containsEntry("account_id", account.id())
                .containsEntry("email", "athlete@example.test").containsEntry("username", "athlete")
                .containsEntry("name", "Имя Спортсмена");
        var captured = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(captured.capture());
        assertThat(captured.getValue().getText()).contains("Email: athlete@example.test", "Username: athlete",
                "Имя: Имя Спортсмена", "Добавьте поиск.");

        transactions.executeWithoutResult(status -> {
            deletion.delete(account);
            deletion.complete(account.id());
        });
        assertThat(saved("Добавьте поиск.")).containsEntry("account_id", null).containsEntry("email", null)
                .containsEntry("username", null).containsEntry("name", null).containsEntry("mail_status", "SENT");
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
    void keepsFeedbackWhenSmtpRejectsTheMessage() throws Exception {
        doThrow(new MailSendException("Unavailable")).when(mail).send(any(SimpleMailMessage.class));
        mvc.perform(submit("""
                {"source":"WEB","message":"Отзыв при сбое почты"}
                """, "192.0.2.3")).andExpect(status().isNoContent());
        assertThat(saved("Отзыв при сбое почты")).containsEntry("mail_status", "FAILED");
    }

    @Test
    void limitsRepeatedAttemptsFromOneClientAndAllowsAnother() throws Exception {
        var message = """
                {"source":"PWA","message":"Проверка лимита"}
                """;
        for (int i = 0; i < 5; i++) {
            mvc.perform(submit(message, "192.0.2.4")).andExpect(status().isNoContent());
        }
        mvc.perform(submit(message, "192.0.2.4")).andExpect(status().isTooManyRequests());
        mvc.perform(submit(message, "192.0.2.5")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.product_feedback WHERE message = ?",
                Long.class, "Проверка лимита")).isEqualTo(6L);
    }

    private Map<String, Object> saved(String message) {
        return jdbc.queryForMap("""
                SELECT source, account_id, email, username, name, mail_status
                FROM sizylift.product_feedback WHERE message = ?
                """, message);
    }

    private static MockHttpServletRequestBuilder submit(String json, String address) {
        return post(PATH).contentType(MediaType.APPLICATION_JSON).content(json).with(request -> {
            request.setRemoteAddr(address);
            return request;
        });
    }
}
