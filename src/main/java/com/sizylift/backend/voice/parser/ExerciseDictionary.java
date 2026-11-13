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
    /** Слова короче сравниваются по звучанию: «т» и «д» — не одно и то же. */
    private static final int MIN_PHONETIC = 3;
    private static final double PHONETIC_SIMILARITY = 0.9;
    /** Слово меньшей похожести в балл кандидата не идёт: «тренажер» не похож на «тяга». */
    private static final double MIN_SIMILARITY = 0.5;
    private static final String VOICED = "бвгджзо";
    private static final String VOICELESS = "пфктшса";
    private static final char SOFT_SIGN = 'ь';
    private static final char HARD_SIGN = 'ъ';

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
            int d = similarEdits(child.getKey(), t.stem);
            if (d >= 0 && edits + d <= MAX_EDITS) {
                walk(child.getValue(), tokens, pos + 1, edits + d, true, best, bestNode);
            }
        }
    }

    /**
     * Ближайшие упражнения для нераспознанного названия. Балл варианта — сумма похожести слов,
     * делённая на длину длинного из двух названий: «грудной тренажер» ближе к «гребной тренажёр»
     * (два похожих слова из двух), чем к «жим от груди в тренажёре» (два из трёх).
     */
    List<Exercise> candidates(List<String> stems) {
        record Scored(Exercise e, double score) { }
        var scored = new ArrayList<Scored>();
        for (var e : exercises) {
            double best = 0;
            for (var alias : e.aliases()) best = Math.max(best, aliasScore(stems, stems(alias)));
            if (best > 0) scored.add(new Scored(e, best));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(s -> s.e().name().length()))
                .limit(MAX_CANDIDATES).map(Scored::e).toList();
    }

    private static double aliasScore(List<String> spoken, List<String> alias) {
        if (spoken.isEmpty() || alias.isEmpty()) return 0;
        double sum = 0;
        for (var s : spoken) {
            double best = 0;
            for (var a : alias) best = Math.max(best, similarity(s, a));
            if (best >= MIN_SIMILARITY) sum += best;
        }
        return sum / Math.max(spoken.size(), alias.size());
    }

    /** 1 — одинаковые основы, ниже — доля совпавших букв; одинаковое звучание почти равно совпадению. */
    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1;
        if (a.length() >= MIN_PHONETIC && phonetic(a).equals(phonetic(b))) return PHONETIC_SIMILARITY;
        return 1 - (double) distance(a, b) / Math.max(a.length(), b.length());
    }

    /**
     * Число исправлений, при котором слово считается тем же, или -1. Сначала звучание: распознавание
     * путает звонкие и глухие и удвоенные буквы («гаг» вместо «гакк»), такая ошибка допустима даже
     * в коротком слове и стоит одно исправление. Иначе — буквы с допуском по длине.
     */
    static int similarEdits(String key, String spoken) {
        if (key.length() >= MIN_PHONETIC && spoken.length() >= MIN_PHONETIC && phonetic(key).equals(phonetic(spoken))) return 1;
        int d = distance(key, spoken);
        return d <= allowed(key, spoken) ? d : -1;
    }

    /** Ключ звучания: оглушение, «о» как «а», без мягкого знака, без удвоенных букв. */
    static String phonetic(String stem) {
        var b = new StringBuilder();
        for (char c : stem.toCharArray()) {
            int k = VOICED.indexOf(c);
            char p = k >= 0 ? VOICELESS.charAt(k) : c;
            if (p == SOFT_SIGN || p == HARD_SIGN) continue;
            if (b.isEmpty() || b.charAt(b.length() - 1) != p) b.append(p);
        }
        return b.toString();
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
