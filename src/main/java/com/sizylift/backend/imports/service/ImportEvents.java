package com.sizylift.backend.imports.service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.sizylift.backend.imports.dto.ImportDtos.View;
import com.sizylift.backend.imports.repository.ImportRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Поток состояния сессии импорта (Server-Sent Events, docs/imports.md, «Поток событий»). Клиент подписывается
 * на сессию и получает событие {@code state} с тем же View, что отдаёт GET, — сразу и при каждом изменении.
 * Сессию может разбирать worker другого экземпляра backend, поэтому изменения ищет один фоновый цикл: раз в
 * {@link #POLL} он одним запросом сверяет отпечатки состояния всех сессий с подписчиками; запись на этом же
 * экземпляре будит цикл сразу ({@link #changed}). Поток закрывается, когда разбор закончен и сессия ждёт
 * пользователя: после его действия клиент подписывается снова.
 */
@Component
public class ImportEvents {
    static final String STATE = "state";
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration HEARTBEAT = Duration.ofSeconds(15);
    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_PER_SESSION = 5;
    private static final int MAX_TOTAL = 1000;
    /** Сессия ждёт пользователя или завершена: дальше она сама не изменится. */
    private static final Set<String> SETTLED = Set.of("review", "clarification_required", "sheet_selection", "failed",
            "committed", "cancelled", "expired");
    private final ImportService imports;
    private final ImportRepository sessions;
    private final Map<UUID, List<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wake = lock.newCondition();
    private volatile boolean running = true;
    private boolean pending;
    private Thread loop;

    public ImportEvents(ImportService imports, ImportRepository sessions) {
        this.imports = imports;
        this.sessions = sessions;
    }

    private static final class Subscriber {
        final UUID user;
        final SseEmitter emitter;
        String stamp;
        long sent = System.nanoTime();
        Subscriber(UUID user, SseEmitter emitter) { this.user = user; this.emitter = emitter; }
    }

    /**
     * Подписка: доступ проверяется как у GET (чужая сессия — 404 до начала потока), первым событием — текущее
     * состояние. Уже завершённый разбор отдаётся одним событием, и поток сразу закрывается.
     */
    public SseEmitter subscribe(UUID user, UUID id) {
        // Отпечаток — до чтения состояния: изменение между ними даст лишнее событие, но не потеряется.
        var stamp = sessions.stamps(List.of(id)).get(id);
        var view = imports.get(user, id);
        var emitter = new SseEmitter(TIMEOUT.toMillis());
        if (SETTLED.contains(view.status())) {
            send(emitter, view);
            emitter.complete();
            return emitter;
        }
        var list = subscribers.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>());
        if (list.size() >= MAX_PER_SESSION || total() >= MAX_TOTAL)
            throw new ImportFailure(HttpStatus.TOO_MANY_REQUESTS, "import_events_limit", "Слишком много подписок на импорт.");
        var subscriber = new Subscriber(user, emitter);
        subscriber.stamp = stamp;
        Runnable remove = () -> list.remove(subscriber);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        list.add(subscriber);
        if (!send(emitter, view)) list.remove(subscriber);
        return emitter;
    }

    /** Сессия изменилась на этом экземпляре: цикл проверит подписчиков сразу, не дожидаясь опроса. */
    public void changed(UUID id) {
        if (!subscribers.containsKey(id)) return;
        lock.lock();
        try {
            pending = true;
            wake.signal();
        } finally {
            lock.unlock();
        }
    }

    @PostConstruct
    void start() { loop = Thread.ofVirtual().name("import-events").start(this::run); }

    @PreDestroy
    void stop() {
        running = false;
        if (loop != null) loop.interrupt();
        subscribers.values().forEach(list -> list.forEach(s -> s.emitter.complete()));
    }

    private void run() {
        while (running) {
            try {
                await();
                tick();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // Сбой одного прохода (например, база недоступна) не останавливает поток: следующий проход повторит.
            }
        }
    }

    private void await() throws InterruptedException {
        lock.lock();
        try {
            if (!pending) wake.awaitNanos(POLL.toNanos());
            pending = false;
        } finally {
            lock.unlock();
        }
    }

    /** Один проход: отпечатки всех сессий с подписчиками одним запросом, затем отправка изменившимся. */
    void tick() {
        subscribers.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (subscribers.isEmpty()) return;
        var stamps = sessions.stamps(new ArrayList<>(subscribers.keySet()));
        subscribers.forEach((id, list) -> {
            var stamp = stamps.get(id);
            for (var subscriber : list) {
                if (stamp == null) close(list, subscriber);
                else if (!stamp.equals(subscriber.stamp)) deliver(id, list, subscriber, stamp);
                else if (System.nanoTime() - subscriber.sent > HEARTBEAT.toNanos()) heartbeat(list, subscriber);
            }
        });
    }

    private void deliver(UUID id, List<Subscriber> list, Subscriber subscriber, String stamp) {
        View view;
        try {
            // Доступ проверяется на каждом событии: отозванная связь тренера закрывает поток.
            view = imports.get(subscriber.user, id);
        } catch (ImportFailure e) {
            close(list, subscriber);
            return;
        }
        subscriber.stamp = stamp;
        subscriber.sent = System.nanoTime();
        if (!send(subscriber.emitter, view)) list.remove(subscriber);
        else if (SETTLED.contains(view.status())) close(list, subscriber);
    }

    private void heartbeat(List<Subscriber> list, Subscriber subscriber) {
        subscriber.sent = System.nanoTime();
        try {
            subscriber.emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | IllegalStateException e) {
            list.remove(subscriber);
        }
    }

    private static boolean send(SseEmitter emitter, View view) {
        try {
            emitter.send(SseEmitter.event().name(STATE).id(view.status() + ":" + view.version()).data(view, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            // Клиент отключился: Spring сам завершит emitter, подписка удаляется.
            return false;
        }
    }

    private static void close(List<Subscriber> list, Subscriber subscriber) {
        list.remove(subscriber);
        subscriber.emitter.complete();
    }

    private int total() { return subscribers.values().stream().mapToInt(List::size).sum(); }
}
