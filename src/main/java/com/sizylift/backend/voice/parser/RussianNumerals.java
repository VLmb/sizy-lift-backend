package com.sizylift.backend.voice.parser;

import java.util.HashMap;
import java.util.Map;

/**
 * Словарь падежных форм числительных. Числа сравниваются по точной форме, без стемминга:
 * основы «пят» и «пятьдесят» слишком близки. Набор конечный: 0–19, десятки, сотни, тысяча, дроби.
 */
final class RussianNumerals {
    /**
     * Разряд определяет склейку: «восемьдесят два» — 82, «пять три» — два числа.
     * STANDALONE — дроби и жаргон («полторы», «двадцатка»): ни с чем не склеиваются.
     */
    enum Order { UNIT, TEEN, TENS, HUNDREDS, THOUSAND, STANDALONE }

    record Numeral(double value, Order order) { }

    private static final double HALF = 0.5;
    private static final double QUARTER = 0.25;
    private static final double ONE_AND_HALF = 1.5;
    private static final int TEN = 10;
    private static final int TWENTY = 20;
    private static final Map<String, Numeral> FORMS = new HashMap<>();

    static {
        unit(0, "ноль", "нуля", "нулю", "нулем");
        unit(1, "один", "одна", "одно", "одного", "одной", "одному", "одним", "одну", "одном");
        unit(2, "два", "две", "двух", "двум", "двумя");
        unit(3, "три", "трех", "трем", "тремя");
        unit(4, "четыре", "четырех", "четырем", "четырьмя");
        regular(Order.UNIT, 5, "пять");
        regular(Order.UNIT, 6, "шесть");
        regular(Order.UNIT, 7, "семь");
        regular(Order.UNIT, 8, "восемь");
        add(Order.UNIT, 8, "восьми", "восьмью");
        regular(Order.UNIT, 9, "девять");
        regular(Order.TEEN, 10, "десять");
        String[] teens = {"одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать",
                "семнадцать", "восемнадцать", "девятнадцать"};
        for (int i = 0; i < teens.length; i++) regular(Order.TEEN, 11 + i, teens[i]);
        regular(Order.TENS, 20, "двадцать");
        regular(Order.TENS, 30, "тридцать");
        add(Order.TENS, 40, "сорок", "сорока");
        // Разговорные «пятьдесятю», «восьмидесятью» — так говорят и так пишет распознавание.
        add(Order.TENS, 50, "пятьдесят", "пятидесяти", "пятьюдесятью", "пятьдесятю", "пятидесятью");
        add(Order.TENS, 60, "шестьдесят", "шестидесяти", "шестьюдесятью", "шестьдесятю", "шестидесятью");
        add(Order.TENS, 70, "семьдесят", "семидесяти", "семьюдесятью", "семьдесятю", "семидесятью");
        add(Order.TENS, 80, "восемьдесят", "восьмидесяти", "восьмьюдесятью", "восемьюдесятью", "восемьдесятю",
                "восьмидесятью", "восемдесят", "восемдесятю");
        add(Order.TENS, 90, "девяносто", "девяноста");
        add(Order.HUNDREDS, 100, "сто", "ста", "сотня", "сотню", "сотни");
        add(Order.HUNDREDS, 200, "двести", "двухсот", "двумстам", "двумястами", "двухстах");
        add(Order.HUNDREDS, 300, "триста", "трехсот", "тремстам", "тремястами", "трехстах");
        add(Order.HUNDREDS, 400, "четыреста", "четырехсот", "четырьмстам", "четырьмястами", "четырехстах");
        add(Order.HUNDREDS, 500, "пятьсот", "пятисот", "пятистам", "пятьюстами", "пятистах");
        add(Order.HUNDREDS, 600, "шестьсот", "шестисот", "шестистам", "шестьюстами", "шестистах");
        add(Order.HUNDREDS, 700, "семьсот", "семисот", "семистам", "семьюстами", "семистах");
        add(Order.HUNDREDS, 800, "восемьсот", "восьмисот", "восьмистам", "восьмьюстами", "восьмистах");
        add(Order.HUNDREDS, 900, "девятьсот", "девятисот", "девятистам", "девятьюстами", "девятистах");
        add(Order.THOUSAND, 1000, "тысяча", "тысячи", "тысячу", "тысяч", "тысячей", "тысячам", "тысячами");
        add(Order.STANDALONE, ONE_AND_HALF, "полтора", "полторы", "полутора");
        add(Order.STANDALONE, HALF, "пол", "половина", "половину");
        // Жаргон про блины и гантели: «с блином-двадцаткой», «пятнашку на каждую сторону».
        slang(5, "пятерка", "пятерку", "пятеркой", "пятерки", "пятерочка", "пятерочку");
        slang(10, "десятка", "десятку", "десяткой", "десятки", "десяточка", "десяточку");
        slang(15, "пятнашка", "пятнашку", "пятнашкой", "пятнашки");
        slang(20, "двадцатка", "двадцатку", "двадцаткой", "двадцатки");
        slang(25, "двадцатипятка", "двадцатипятку", "двадцатипяткой");
    }

    private RussianNumerals() { }

    static Numeral find(String word) { return FORMS.get(word); }

    /** «с половиной» после числа. */
    static double addition(String word) {
        return switch (word) {
            case "половиной", "половинкой" -> HALF;
            case "четвертью" -> QUARTER;
            default -> 0;
        };
    }

    /** Может ли {@code next} приклеиться к числу из разрядов {@code last}: только меньший свободный разряд. */
    static boolean joins(Order last, Order next) {
        return switch (last) {
            case HUNDREDS -> next == Order.TENS || next == Order.TEEN || next == Order.UNIT;
            case TENS -> next == Order.UNIT;
            default -> false;
        };
    }

    /** Цифры после числительного: «восемьдесят 2» распознавание пишет вперемешку. */
    static Numeral digits(String word) {
        if (word.isEmpty() || word.length() > 2 || !word.chars().allMatch(Character::isDigit)) return null;
        int v = Integer.parseInt(word);
        if (v >= 1 && v < TEN) return new Numeral(v, Order.UNIT);
        if (v >= TEN && v < TWENTY) return new Numeral(v, Order.TEEN);
        if (v >= TWENTY && v % TEN == 0) return new Numeral(v, Order.TENS);
        return null;
    }

    private static void unit(double value, String... forms) { add(Order.UNIT, value, forms); }

    private static void slang(double value, String... forms) { add(Order.STANDALONE, value, forms); }

    /** «пять, пяти, пятью»: родительный и творительный от основы без мягкого знака. */
    private static void regular(Order order, double value, String nominative) {
        var base = nominative.substring(0, nominative.length() - 1);
        add(order, value, nominative, base + "и", base + "ью");
    }

    private static void add(Order order, double value, String... forms) {
        for (var f : forms) FORMS.put(f, new Numeral(value, order));
    }
}
