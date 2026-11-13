package com.sizylift.backend.voice.parser;

import java.util.HashMap;
import java.util.Map;

/**
 * Словарь падежных форм числительных. Числа сравниваются по точной форме, без стемминга:
 * основы «пят» и «пятьдесят» слишком близки. Набор конечный: 0–19, десятки, сотни, тысяча, дроби.
 */
final class RussianNumerals {
    /** Разряд определяет склейку: «восемьдесят два» — 82, «пять три» — два числа. */
    enum Order { UNIT, TEEN, TENS, HUNDREDS, THOUSAND, FRACTION }

    record Numeral(double value, Order order) { }

    private static final double HALF = 0.5;
    private static final double QUARTER = 0.25;
    private static final double ONE_AND_HALF = 1.5;
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
        add(Order.TENS, 50, "пятьдесят", "пятидесяти", "пятьюдесятью");
        add(Order.TENS, 60, "шестьдесят", "шестидесяти", "шестьюдесятью");
        add(Order.TENS, 70, "семьдесят", "семидесяти", "семьюдесятью");
        add(Order.TENS, 80, "восемьдесят", "восьмидесяти", "восьмьюдесятью", "восемьюдесятью");
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
        add(Order.FRACTION, ONE_AND_HALF, "полтора", "полторы", "полутора");
        add(Order.FRACTION, HALF, "пол", "половина", "половину");
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

    private static void unit(double value, String... forms) { add(Order.UNIT, value, forms); }

    /** «пять, пяти, пятью»: родительный и творительный от основы без мягкого знака. */
    private static void regular(Order order, double value, String nominative) {
        var base = nominative.substring(0, nominative.length() - 1);
        add(order, value, nominative, base + "и", base + "ью");
    }

    private static void add(Order order, double value, String... forms) {
        for (var f : forms) FORMS.put(f, new Numeral(value, order));
    }
}
