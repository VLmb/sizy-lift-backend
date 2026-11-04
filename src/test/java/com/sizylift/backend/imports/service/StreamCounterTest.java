package com.sizylift.backend.imports.service;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamCounterTest {
    @Test
    void countsOnlyCompletedTopLevelElementsAcrossArbitraryFragments() {
        var changes = new AtomicInteger();
        var counter = new StreamCounter(changes::incrementAndGet);
        var answer = """
                {"question":"","name":"a:{[\\"]}","exercises":[{"id":"e1","refs":["x"]},{"id":"e2","refs":[]}],
                 "plan":[{"week":1},{"week":2}],"weeks":[{"week":1,"days":[{"blocks":[{"items":[]}]}]},{"week":2,"days":[""" ;
        for (int i = 0; i < answer.length(); i += 3) counter.onArguments(answer.substring(i, Math.min(answer.length(), i + 3)));
        assertThat(counter.count("exercises")).isEqualTo(2);
        assertThat(counter.count("plan")).isEqualTo(2);
        assertThat(counter.count("weeks")).isEqualTo(1);
        assertThat(counter.count("days")).isZero();
        assertThat(changes.get()).isEqualTo(5);
        counter.onArguments("]}]}");
        assertThat(counter.count("weeks")).isEqualTo(2);
    }
}
