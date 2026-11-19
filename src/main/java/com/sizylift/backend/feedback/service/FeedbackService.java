package com.sizylift.backend.feedback.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

import com.sizylift.backend.feedback.dto.FeedbackRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
    private final String from;
    private final String recipient;
    private final Clock clock;
    private final Deque<Attempt> attempts = new ArrayDeque<>();

    public FeedbackService(ObjectProvider<MailSender> mail, Clock clock,
                           @Value("${sizylift.feedback.from:}") String from,
                           @Value("${sizylift.feedback.recipient:}") String recipient) {
        this.mail = mail;
        this.clock = clock;
        this.from = from.strip();
        this.recipient = recipient.strip();
    }

    public Result send(FeedbackRequest feedback, String clientAddress) {
        if (recipient.isBlank() || from.isBlank()) return Result.UNAVAILABLE;
        var sender = mail.getIfAvailable();
        if (sender == null) return Result.UNAVAILABLE;
        if (!reserveAttempt(clientAddress)) return Result.RATE_LIMITED;

        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(SUBJECT);
        message.setText("Источник: " + feedback.source() + "\n\n" + feedback.message().strip());
        try {
            sender.send(message);
            return Result.SENT;
        } catch (MailException e) {
            LOG.warn("Не удалось отправить отзыв через SMTP ({})", e.getClass().getSimpleName());
            return Result.UNAVAILABLE;
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

    public enum Result {
        SENT, RATE_LIMITED, UNAVAILABLE
    }
}
