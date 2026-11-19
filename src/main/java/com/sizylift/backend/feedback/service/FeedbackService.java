package com.sizylift.backend.feedback.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

import com.sizylift.backend.feedback.dto.FeedbackRequest;
import com.sizylift.backend.feedback.entity.FeedbackAuthor;
import com.sizylift.backend.feedback.repository.FeedbackRepository;
import com.sizylift.backend.feedback.repository.FeedbackRepository.MailStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {
    private static final Logger LOG = LoggerFactory.getLogger(FeedbackService.class);
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final int MAX_PER_CLIENT = 5;
    private static final int MAX_TOTAL = 60;
    private static final String SUBJECT = "[Sizy] Обратная связь";

    private final ObjectProvider<MailSender> mail;
    private final FeedbackRepository repository;
    private final String from;
    private final String recipient;
    private final Clock clock;
    private final Deque<Attempt> attempts = new ArrayDeque<>();

    public FeedbackService(ObjectProvider<MailSender> mail, FeedbackRepository repository, Clock clock,
                           @Value("${sizylift.feedback.from:}") String from,
                           @Value("${sizylift.feedback.recipient:}") String recipient) {
        this.mail = mail;
        this.repository = repository;
        this.clock = clock;
        this.from = from.strip();
        this.recipient = recipient.strip();
    }

    public boolean submit(FeedbackRequest feedback, String clientAddress, Supplier<FeedbackAuthor> resolveAuthor) {
        if (!reserveAttempt(clientAddress)) return false;
        var author = resolveAuthor.get();
        var text = feedback.message().strip();
        var id = repository.save(feedback.source(), text, author);
        var status = sendMail(feedback, text, author);
        try {
            repository.updateMailStatus(id, status);
        } catch (DataAccessException e) {
            LOG.warn("Не удалось обновить статус письма об отзыве ({})", e.getClass().getSimpleName());
        }
        return true;
    }

    private MailStatus sendMail(FeedbackRequest feedback, String text, FeedbackAuthor author) {
        if (recipient.isBlank() || from.isBlank()) return MailStatus.FAILED;
        var sender = mail.getIfAvailable();
        if (sender == null) return MailStatus.FAILED;

        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(SUBJECT);
        var authorDetails = author.accountId() == null ? "Анонимный отзыв" : """
                Email: %s
                Username: %s
                Имя: %s""".formatted(available(author.email()), available(author.username()), available(author.name()));
        message.setText("Источник: " + feedback.source() + "\n" + authorDetails + "\n\n" + text);
        try {
            sender.send(message);
            return MailStatus.SENT;
        } catch (MailException e) {
            LOG.warn("Не удалось отправить отзыв через SMTP ({})", e.getClass().getSimpleName());
            return MailStatus.FAILED;
        }
    }

    private synchronized boolean reserveAttempt(String clientAddress) {
        var now = clock.instant();
        var cutoff = now.minus(WINDOW);
        while (!attempts.isEmpty() && !attempts.getFirst().at().isAfter(cutoff)) {
            attempts.removeFirst();
        }
        if (attempts.size() >= MAX_TOTAL) return false;
        long fromClient = attempts.stream().filter(attempt -> attempt.clientAddress().equals(clientAddress)).count();
        if (fromClient >= MAX_PER_CLIENT) return false;
        attempts.addLast(new Attempt(now, clientAddress));
        return true;
    }

    private record Attempt(Instant at, String clientAddress) { }

    private static String available(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
