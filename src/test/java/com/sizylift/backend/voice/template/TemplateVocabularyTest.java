package com.sizylift.backend.voice.template;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import com.sizylift.backend.template.SetLine;
import com.sizylift.backend.template.TemplateVocabulary;
import com.sizylift.backend.template.TemplateVocabulary.Scope;
import com.sizylift.backend.voice.service.VoiceTestCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Сторож: словарь шаблона, текст промпта и строгий разборщик говорят об одних и тех же ключах и типах. */
class TemplateVocabularyTest {
    private static final Pattern KEY_IN_TEXT = Pattern.compile("([А-Яа-яЁёA-Za-z_]+)=");
    private static final List<String> RESULT_TYPES = List.of("weight_reps", "added_weight_reps", "reps", "duration",
            "distance_time", "free");

    @Test
    void everyKeyHasFormatAndEveryFormatHasKey() {
        assertThat(TemplateVocabulary.allKeys(Scope.IMPORT)).containsExactlyInAnyOrderElementsOf(SetLine.FORMATS.keySet());
        assertThat(TemplateVocabulary.allKeys(Scope.IMPORT)).containsAll(TemplateVocabulary.allKeys());
    }

    @Test
    void everyResultTypeHasTemplateWordAndBack() {
        assertThat(TemplateVocabulary.exerciseTypes()).extracting(TemplateVocabulary.ExerciseType::resultType)
                .containsExactlyInAnyOrderElementsOf(RESULT_TYPES);
        for (var t : TemplateVocabulary.exerciseTypes()) {
            assertThat(TemplateVocabulary.resultType(t.word())).contains(t.resultType());
        }
    }

    @Test
    void everySetTypeWordIsReadBack() {
        assertThat(TemplateVocabulary.setTypes()).filteredOn(TemplateVocabulary.SetType::byDefault).hasSize(1);
        for (var t : TemplateVocabulary.setTypes()) {
            assertThat(TemplateVocabulary.setType(t.word())).contains(t.value());
            for (var s : t.spoken()) assertThat(TemplateVocabulary.setType(s)).contains(t.value());
        }
    }

    /** Ключ, который промпт показывает модели, но разборщик не знает, молча терял бы значения. */
    @Test
    void promptMentionsOnlyKnownKeysAndAllOfThem() {
        var rules = TemplatePrompt.rules();
        assertThat(rules).doesNotContain("{{").doesNotContain("}}");
        var mentioned = new HashSet<String>();
        var m = KEY_IN_TEXT.matcher(rules);
        while (m.find()) mentioned.add(TemplateVocabulary.key(m.group(1)));
        assertThat(TemplateVocabulary.allKeys()).containsAll(mentioned);
        assertThat(mentioned).containsAll(TemplateVocabulary.allKeys());
    }

    /** Пример из промпта разбирается целиком: модель учится на шаблоне, который разборщик принимает. */
    @Test
    void promptExampleIsParsedWithoutSkips() {
        var rules = TemplatePrompt.rules();
        var template = rules.substring(rules.indexOf("Шаблон:\n") + 8, rules.indexOf("\n\nКАТАЛОГ"));
        var result = TemplateParser.parse(template, VoiceTestCatalog.system());
        assertThat(result.skipped()).isEmpty();
        assertThat(result.items()).extracting(i -> i.exerciseName())
                .containsExactly("Жим гантелей лёжа", "Молотки с гантелями", "Бег на улице");
        assertThat(result.items().getFirst().lines()).hasSize(3);
        assertThat(result.items().getFirst().comment()).isEqualTo("локти не разводи");
    }
}
