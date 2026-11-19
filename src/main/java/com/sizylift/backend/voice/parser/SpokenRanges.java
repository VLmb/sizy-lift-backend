package com.sizylift.backend.voice.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.sizylift.backend.voice.parser.Token.Kind;
import com.sizylift.backend.voice.parser.Token.Unit;

final class SpokenRanges {
    static final int MAX_REPS = 1000;
    private static final Set<String> DASHES = Set.of("-", "–", "—");
    private static final Set<String> CONNECTORS = Set.of("до", "тире", "дефис");
    private static final Set<String> FROM = Set.of("от", "с", "со", "между");

    private record Match(Token token, int end) { }

    private SpokenRanges() { }

    static boolean isDash(String text) {
        return DASHES.contains(text);
    }

    static List<Token> merge(List<Token> tokens) {
        var result = new ArrayList<Token>();
        int index = 0;
        while (index < tokens.size()) {
            var match = matchAt(tokens, index);
            if (match == null) {
                result.add(tokens.get(index++));
            } else {
                result.add(match.token());
                index = match.end();
            }
        }
        return result;
    }

    private static Match matchAt(List<Token> tokens, int start) {
        var first = tokens.get(start);
        int index = isRepUnit(first) ? start + 1 : start;
        var prefix = at(tokens, index);
        boolean prefixed = prefix != null && FROM.contains(prefix.text);
        int lowIndex = index + (prefixed ? 1 : 0);
        var lower = at(tokens, lowIndex);
        var lowerUnit = isRepUnit(at(tokens, lowIndex + 1));
        int connectorIndex = lowIndex + (lowerUnit ? 2 : 1);
        var connector = at(tokens, connectorIndex);
        var upper = at(tokens, connectorIndex + 1);
        var suffix = at(tokens, connectorIndex + 2);
        if (lower == null || !lower.is(Kind.NUMBER) || upper == null || !upper.is(Kind.NUMBER)
                || !matchesConnector(prefix, prefixed, connector)
                || suffix != null && suffix.is(Kind.MONTH)
                || incompatiblePrefix(at(tokens, start - 1))) {
            return null;
        }

        boolean hasUnit = suffix != null && suffix.is(Kind.UNIT);
        Unit unit = hasUnit ? suffix.unit : isRepUnit(first) || lowerUnit ? Unit.REPS : null;
        // «С 60 до 80» без единицы может описывать вес.
        if (unit == null && prefixed && (prefix.text.equals("с") || prefix.text.equals("со"))) {
            return null;
        }
        var last = hasUnit ? suffix : upper;
        var range = new Token(first.from, last.to, lower.text + "–" + upper.text, null, Kind.RANGE);
        range.value = lower.value;
        range.upper = upper.value;
        range.unit = unit;
        return new Match(range, connectorIndex + (hasUnit ? 3 : 2));
    }

    private static boolean matchesConnector(Token prefix, boolean prefixed, Token connector) {
        if (connector == null) return false;
        if (!prefixed) return CONNECTORS.contains(connector.text) || isDash(connector.text);
        return connector.text.equals(prefix.text.equals("между") ? "и" : "до");
    }

    private static boolean incompatiblePrefix(Token token) {
        return token != null && (FROM.contains(token.text) || token.is(Kind.RPE) || token.is(Kind.RIR)
                || token.is(Kind.REST) || token.is(Kind.PAUSE) || token.is(Kind.WEIGHT_WORD));
    }

    private static boolean isRepUnit(Token token) {
        return token != null && token.is(Kind.UNIT) && token.unit == Unit.REPS;
    }

    private static Token at(List<Token> tokens, int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
    }
}
