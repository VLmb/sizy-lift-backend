package com.sizylift.backend.voice.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.sizylift.backend.voice.parser.Token.Kind;

/**
 * Словарь вариантов названий: дерево по цепочкам основ. Поиск берёт самое длинное совпадение
 * («жим лёжа узким хватом» раньше «жим лёжа») и допускает опечатки в основах неизвестных слов.
 */
public final class ExerciseDictionary {
    /** Упражнение и его варианты названий. {@code resultType} — как в каталоге: weight_reps, duration… */
    public record Exercise(String id, String name, String resultType, List<String> aliases) { }

    record Match(Exercise exercise, int start, int end, int edits, boolean ambiguous, List<Exercise> alternatives) { }

    private static final String SYSTEM_RESOURCE = "voice/exercise-aliases.txt";
    private static final String SYSTEM_ID_PREFIX = "14000000-0000-4000-8000-";
    private static final int SYSTEM_ID_DIGITS = 12;
    private static final Pattern FIELD_SEPARATOR = Pattern.compile("\\|");
    private static final String COMMENT = "#";
    private static final int FIRST_ALIAS_FIELD = 2;
    /** Основы короче — только точное совпадение: «жим» и «дим» — разные слова. */
    private static final int MIN_FUZZY_ONE = 4;
    private static final int MIN_FUZZY_TWO = 8;
    private static final int MAX_EDITS = 2;
    private static final int MAX_CANDIDATES = 3;

    private static final class Node {
        final Map<String, Node> children = new HashMap<>();
        final LinkedHashSet<Exercise> hits = new LinkedHashSet<>();
    }

    private final Node root = new Node();
    private final List<Exercise> exercises;

    public ExerciseDictionary(List<Exercise> exercises) {
        this.exercises = List.copyOf(exercises);
        for (var e : exercises) for (var alias : e.aliases()) add(e, alias);
    }

    /** Системный каталог из ресурса (черновик синонимов). */
    public static ExerciseDictionary system() { return new ExerciseDictionary(loadSystem()); }

    public static List<Exercise> loadSystem() {
        var in = ExerciseDictionary.class.getClassLoader().getResourceAsStream(SYSTEM_RESOURCE);
        Objects.requireNonNull(in, SYSTEM_RESOURCE);
        var out = new ArrayList<Exercise>();
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith(COMMENT)) continue;
                var f = FIELD_SEPARATOR.split(line.strip());
                var id = SYSTEM_ID_PREFIX + "0".repeat(SYSTEM_ID_DIGITS - f[0].length()) + f[0];
                var aliases = List.of(f).subList(FIRST_ALIAS_FIELD, f.length);
                out.add(new Exercise(id, f[FIRST_ALIAS_FIELD], f[1], aliases));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Нет словаря " + SYSTEM_RESOURCE, e);
        }
        return out;
    }

    public List<Exercise> exercises() { return exercises; }

    /**
     * Цепочка основ варианта без предлогов. Однословный вариант, совпадающий со служебным словом
     * («Разминка»), в поиск не попадает: иначе «разминка 40 на 10» стала бы упражнением.
     */
    static List<String> stems(String alias) {
        var out = new ArrayList<String>();
        for (var w : TextTokenizer.words(alias)) {
            if (Lexicon.NAME_PREPOSITIONS.contains(w) || !Character.isLetter(w.charAt(0))) continue;
            out.add(RussianStemmer.stem(w));
        }
        return out;
    }

    static boolean searchable(String alias) {
        var words = TextTokenizer.words(alias).stream().filter(w -> Character.isLetter(w.charAt(0))).toList();
        return !(words.size() == 1 && Lexicon.isServiceWord(words.getFirst(), RussianStemmer.stem(words.getFirst())));
    }

    private void add(Exercise e, String alias) {
        if (!searchable(alias)) return;
        var node = root;
        for (var s : stems(alias)) node = node.children.computeIfAbsent(s, k -> new Node());
        if (node != root) node.hits.add(e);
    }

    /** Самое длинное совпадение с позиции {@code start}; при равной длине — с меньшим числом опечаток. */
    Match match(List<Token> tokens, int start) {
        var best = new int[] {-1, Integer.MAX_VALUE};
        var bestNode = new Node[1];
        walk(root, tokens, start, 0, false, best, bestNode);
        if (bestNode[0] == null) return null;
        var hits = List.copyOf(bestNode[0].hits);
        return new Match(hits.getFirst(), start, best[0], best[1], hits.size() > 1, hits.subList(1, hits.size()));
    }

    private void walk(Node node, List<Token> tokens, int pos, int edits, boolean consumed, int[] best, Node[] bestNode) {
        if (consumed && !node.hits.isEmpty() && (pos > best[0] || pos == best[0] && edits < best[1])) {
            best[0] = pos;
            best[1] = edits;
            bestNode[0] = node;
        }
        if (pos >= tokens.size()) return;
        var t = tokens.get(pos);
        if (t.stem == null) return;
        if (node != root && Lexicon.NAME_PREPOSITIONS.contains(t.text)) walk(node, tokens, pos + 1, edits, false, best, bestNode);
        var exact = node.children.get(t.stem);
        if (exact != null) walk(exact, tokens, pos + 1, edits, true, best, bestNode);
        if (!t.is(Kind.WORD)) return;
        for (var child : node.children.entrySet()) {
            if (child.getKey().equals(t.stem)) continue;
            int d = distance(child.getKey(), t.stem);
            if (d <= allowed(child.getKey(), t.stem) && edits + d <= MAX_EDITS) {
                walk(child.getValue(), tokens, pos + 1, edits + d, true, best, bestNode);
            }
        }
    }

    /** Ближайшие упражнения для нераспознанного названия: по числу совпавших основ. */
    List<Exercise> candidates(List<String> stems) {
        record Scored(Exercise e, int score) { }
        var scored = new ArrayList<Scored>();
        for (var e : exercises) {
            var known = e.aliases().stream().flatMap(a -> stems(a).stream()).toList();
            int score = 0;
            for (var s : stems) {
                if (known.stream().anyMatch(k -> distance(k, s) <= allowed(k, s))) score++;
            }
            if (score > 0) scored.add(new Scored(e, score));
        }
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(s -> s.e().name().length()))
                .limit(MAX_CANDIDATES).map(Scored::e).toList();
    }

    private static int allowed(String a, String b) {
        int len = Math.min(a.length(), b.length());
        return len < MIN_FUZZY_ONE ? 0 : len < MIN_FUZZY_TWO ? 1 : 2;
    }

    /** Расстояние Дамерау — Левенштейна: замена, вставка, удаление, перестановка соседних букв. */
    static int distance(String a, String b) {
        var d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
