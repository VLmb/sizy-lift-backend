package com.sizylift.backend.voice.parser;

import java.util.ArrayList;
import java.util.List;

import com.sizylift.backend.voice.parser.ParsedWorkout.Candidate;
import com.sizylift.backend.voice.parser.ParsedWorkout.Confidence;
import com.sizylift.backend.voice.parser.ParsedWorkout.Item;
import com.sizylift.backend.voice.parser.Token.Kind;

/**
 * Разбор продиктованной тренировки без модели: нормализация → названия по словарю → куски между
 * названиями → шаблоны параметров. Детерминирован: одна фраза — один результат.
 */
public final class WorkoutTextParser {
    private final ExerciseDictionary dictionary;

    public WorkoutTextParser(ExerciseDictionary dictionary) { this.dictionary = dictionary; }

    /** Упражнение (найденное или нет) и токены его параметров. */
    private static final class Slot {
        final ExerciseDictionary.Match match;
        final int nameFrom;
        final int nameTo;
        final List<Token> name;
        final List<Token> params = new ArrayList<>();

        Slot(ExerciseDictionary.Match match, int nameFrom, int nameTo, List<Token> name) {
            this.match = match;
            this.nameFrom = nameFrom;
            this.nameTo = nameTo;
            this.name = name;
        }

        boolean leading() { return name == null; }
        boolean unknown() { return match == null && name != null; }
        boolean hasParameters() { return params.stream().anyMatch(ParameterParser::startsParameters); }

        /** Есть числа или «до отказа», а не только отметки вроде «разминка». */
        boolean hasValues() {
            return params.stream().anyMatch(p -> p.is(Kind.NUMBER) || p.is(Kind.QUANTITY) || p.is(Kind.TO_FAILURE));
        }
    }

    public ParsedWorkout parse(String phrase) {
        var text = TextTokenizer.tokenize(phrase);
        var slots = slots(text.tokens());
        for (var slot : slots) {
            var params = TextTokenizer.quantities(slot.params);
            slot.params.clear();
            slot.params.addAll(params);
        }
        dropCorrected(slots);
        var leading = slots.getFirst();
        var unrecognized = new ArrayList<String>();
        // «Три по пять — присед»: параметры перед первым названием; «Разминка — велотренажёр»: только отметка режима.
        boolean moved = leading.hasParameters() && slots.size() > 1
                && (!slots.get(1).hasParameters() || !leading.hasValues());
        if (moved) {
            slots.get(1).params.addAll(0, leading.params);
        } else if (!leading.hasValues()) {
            unrecognized.addAll(ParameterParser.parse(leading.params, text, null).unrecognized());
        }
        var items = new ArrayList<Item>();
        if (leading.hasValues() && !moved) {
            // Подходы без названия («начнём со… пять по три на сто сорок»): не теряем, упражнение выберет тренер.
            var params = ParameterParser.parse(leading.params, text, null);
            items.add(new Item(null, null, null, "", Confidence.LOW, params.lines(), params.restSeconds(),
                    params.unrecognized(), params.issues(), List.of()));
        }
        for (var slot : slots.subList(1, slots.size())) items.add(item(slot, text));
        return new ParsedWorkout(items, unrecognized);
    }

    private List<Slot> slots(List<Token> tokens) {
        var slots = new ArrayList<Slot>();
        var current = new Slot(null, 0, 0, null);
        slots.add(current);
        int i = 0;
        while (i < tokens.size()) {
            var t = tokens.get(i);
            var m = t.is(Kind.PREPOSITION) ? null : dictionary.match(tokens, i);
            if (m != null) {
                current = new Slot(m, tokens.get(i).from, tokens.get(m.end() - 1).to, tokens.subList(i, m.end()));
                slots.add(current);
                i = m.end();
            } else if ((t.is(Kind.WORD) || lostName(tokens, i)) && nameStart(current, tokens, i) >= 0) {
                int start = nameStart(current, tokens, i);
                int end = unknownNameEnd(tokens, i);
                if (start < i) current.params.removeLast();
                current = new Slot(null, tokens.get(start).from, tokens.get(end - 1).to, tokens.subList(start, end));
                slots.add(current);
                i = end;
            } else {
                current.params.add(t);
                i++;
            }
        }
        return slots;
    }

    /**
     * Начало названия нового упражнения или -1. Неизвестные слова — название, если за ними идут параметры,
     * а перед ними начало фразы, связка или параметры предыдущего. Сразу за названием
     * («жим лёжа с паузой 3 по 3») это уточнение. После предлога — тоже уточнение, кроме случая, когда
     * у предыдущего упражнения уже есть подходы и перед предлогом связка: «…до отказа, и с гантелями по 22 кг»
     * — распознавание потеряло слово «шраги», и подходы нельзя приписать подтягиваниям.
     */
    private static int nameStart(Slot current, List<Token> tokens, int i) {
        int j = unknownNameEnd(tokens, i);
        while (j < tokens.size() && tokens.get(j).isNoise()) j++;
        if (j >= tokens.size() || !ParameterParser.startsParameters(tokens.get(j))) return -1;
        if (i > 0 && tokens.get(i - 1).is(Kind.PREPOSITION)) {
            var before = i > 1 ? tokens.get(i - 2) : null;
            boolean afterBreak = before != null && (before.is(Kind.FILLER) || before.is(Kind.SEPARATOR));
            return afterBreak && !current.leading() && current.hasParameters() ? i - 1 : -1;
        }
        if (current.leading() || current.hasParameters()) return i;
        return current.params.stream().anyMatch(p -> p.is(Kind.FILLER) || p.is(Kind.SEPARATOR)) ? i : -1;
    }

    /** «…до отказа, и с гантелями по 22»: инвентарь после связки и предлога — остаток потерянного названия. */
    private static boolean lostName(List<Token> tokens, int i) {
        return tokens.get(i).is(Kind.WEIGHT_WORD) && i > 1 && tokens.get(i - 1).is(Kind.PREPOSITION)
                && tokens.get(i - 2).is(Kind.FILLER);
    }

    /** Название — слова подряд, внутри могут быть предлоги: «жим арнольда», «тяга с резиной». */
    private static int unknownNameEnd(List<Token> tokens, int i) {
        int end = i + 1;
        int j = i + 1;
        while (j < tokens.size() && (tokens.get(j).is(Kind.WORD) || tokens.get(j).is(Kind.PREPOSITION))) {
            if (tokens.get(j).is(Kind.WORD)) end = j + 1;
            j++;
        }
        return end;
    }

    /** «Присед, нет, фронтальный присед»: упражнение без параметров, за которым идёт исправление, отменяется. */
    private static void dropCorrected(List<Slot> slots) {
        for (int k = slots.size() - 2; k >= 1; k--) {
            var s = slots.get(k);
            if (!s.hasParameters() && s.params.stream().anyMatch(p -> p.is(Kind.CORRECTION))) slots.remove(k);
        }
    }

    private Item item(Slot slot, TextTokenizer.Result text) {
        var spoken = text.text(slot.nameFrom, slot.nameTo);
        var type = slot.unknown() ? null : slot.match.exercise().resultType();
        var params = ParameterParser.parse(slot.params, text, type);
        if (slot.unknown()) {
            var stems = slot.name.stream().filter(t -> t.is(Kind.WORD)).map(t -> t.stem).toList();
            var candidates = dictionary.candidates(stems).stream().map(e -> new Candidate(e.id(), e.name())).toList();
            return new Item(null, null, null, spoken, Confidence.LOW, params.lines(), params.restSeconds(),
                    params.unrecognized(), params.issues(), candidates);
        }
        var m = slot.match;
        var confidence = m.edits() == 0 && !m.ambiguous() ? Confidence.HIGH : Confidence.MEDIUM;
        var alternatives = m.alternatives().stream().map(e -> new Candidate(e.id(), e.name())).toList();
        return new Item(m.exercise().id(), m.exercise().name(), type, spoken, confidence, params.lines(),
                params.restSeconds(), params.unrecognized(), params.issues(), alternatives);
    }
}
