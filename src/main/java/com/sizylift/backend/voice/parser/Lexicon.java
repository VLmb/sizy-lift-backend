package com.sizylift.backend.voice.parser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sizylift.backend.voice.parser.Token.Kind;
import com.sizylift.backend.voice.parser.Token.Unit;

/**
 * Служебные слова диктовки. Короткие слова и связки сравниваются по точной форме, единицы и термины —
 * по основе, чтобы падеж не мешал: «подхода», «подходов» → «подход».
 */
final class Lexicon {
    record Entry(Kind kind, Unit unit, double factor) { }

    private static final double SECONDS_PER_MINUTE = 60;
    private static final double SECONDS_PER_HOUR = 3600;
    private static final double METERS_PER_KILOMETER = 1000;
    /** Слова короче этого сравниваются только точно: у коротких основ слишком много совпадений. */
    private static final int MIN_STEM_LOOKUP = 3;

    /** Предлоги в названиях упражнений пропускаются при сравнении: «присед со штангой» = «присед штанга». */
    static final Set<String> NAME_PREPOSITIONS = Set.of("с", "со", "на", "в", "во", "из", "за", "к", "ко", "для", "от",
            "у", "по", "под", "над");

    /** Многословные термины склеиваются в один токен до разбора. */
    static final Map<List<String>, Entry> PHRASES = Map.ofEntries(
            Map.entry(List.of("до", "отказа"), entry(Kind.TO_FAILURE)),
            Map.entry(List.of("в", "отказ"), entry(Kind.TO_FAILURE)),
            Map.entry(List.of("по", "максимуму"), entry(Kind.TO_FAILURE)),
            Map.entry(List.of("на", "максимум"), entry(Kind.TO_FAILURE)),
            Map.entry(List.of("максимум", "раз"), entry(Kind.TO_FAILURE)),
            Map.entry(List.of("эр", "пэ", "е"), entry(Kind.RPE)),
            Map.entry(List.of("эр", "пе", "е"), entry(Kind.RPE)),
            Map.entry(List.of("р", "п", "е"), entry(Kind.RPE)),
            Map.entry(List.of("рп", "е"), entry(Kind.RPE)),
            Map.entry(List.of("эр", "ай", "эр"), entry(Kind.RIR)),
            Map.entry(List.of("на", "каждую", "сторону"), entry(Kind.EACH_SIDE)),
            Map.entry(List.of("на", "каждую", "ногу"), entry(Kind.EACH_SIDE)),
            Map.entry(List.of("на", "каждую", "руку"), entry(Kind.EACH_SIDE)),
            Map.entry(List.of("вес", "подберет", "сам"), entry(Kind.NOTE)),
            Map.entry(List.of("вес", "по", "самочувствию"), entry(Kind.NOTE)),
            Map.entry(List.of("по", "самочувствию"), entry(Kind.NOTE)),
            Map.entry(List.of("без", "веса"), entry(Kind.FILLER)),
            Map.entry(List.of("последний", "подход"), entry(Kind.LAST)),
            Map.entry(List.of("первый", "подход"), entry(Kind.FIRST)),
            Map.entry(List.of("то", "есть"), entry(Kind.CORRECTION)));

    private static final Map<String, Entry> EXACT = new HashMap<>();
    private static final Map<String, Entry> STEMS = new HashMap<>();

    static {
        exact(entry(Kind.PREPOSITION), "по", "под", "на", "с", "со", "х", "x", "в", "во", "до", "от", "к", "за", "из", "у", "при");
        exact(entry(Kind.RPE), "rpe", "рпе", "рпэ", "рпи", "эрпе", "эрпэе", "эрпээ");
        exact(entry(Kind.RIR), "rir", "рир");
        exact(entry(Kind.CORRECTION), "нет", "вернее", "точнее", "ой", "стоп");
        exact(entry(Kind.RESERVE), "запасе");
        exact(entry(Kind.EVERYWHERE), "везде");
        exact(entry(Kind.LAST), "последний", "последнем", "последнюю", "последнего");
        exact(entry(Kind.FIRST), "первый", "первом", "первую");
        exact(entry(Kind.FILLER), "потом", "дальше", "затем", "далее", "после", "этого", "следом", "следующее",
                "следующим", "следующий", "и", "а", "еще", "конце", "конец", "начнем", "начинаем", "сначала",
                "первым", "делом", "закончим", "заканчиваем", "завершим", "упражнение", "упражнения", "сделаем",
                "делаем", "делать", "сделать", "будет", "будем", "это", "нас", "его", "теперь", "тоже", "также", "пусть",
                "сделай", "хочу", "давай", "давайте", "нужно", "надо", "можно", "ну", "так", "вот", "э", "ээ", "эм",
                "мм", "короче", "значит", "типа", "сегодня", "завтра", "послезавтра", "ок", "окей", "хорошо", "ладно",
                "итак", "как", "обычно", "между", "оставить", "оставляем", "оставь", "оставляй", "держим", "всего", "все", "по-прежнему", "тренировка", "тренировку", "тренировке", "тренировки",
                "понедельник", "вторник", "среда", "среду", "четверг", "пятница", "пятницу", "суббота", "субботу",
                "воскресенье", "утром", "вечером");
        exact(entry(Kind.MONTH), "января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября",
                "октября", "ноября", "декабря");
        exact(unit(Unit.KG, 1), "кг", "кило");
        exact(unit(Unit.SECONDS, SECONDS_PER_MINUTE), "мин");
        exact(unit(Unit.SECONDS, 1), "сек");
        exact(unit(Unit.METERS, 1), "м");
        exact(unit(Unit.METERS, METERS_PER_KILOMETER), "км");
        exact(unit(Unit.PERCENT, 1), "%");
        exact(entry(Kind.PERCENT_BASE), "пм", "максимума", "максимального", "разового");

        stems(unit(Unit.SETS, 1), "подход", "подхода", "подходов", "подходы", "подходами", "подходик", "подходика",
                "сет", "сета", "сетов", "сеты");
        stems(unit(Unit.REPS, 1), "повтор", "повтора", "повторов", "повторы", "повторение", "повторения", "повторений",
                "раз", "раза", "разика", "повторик", "шаг", "шага", "шагов", "шаги");
        stems(unit(Unit.KG, 1), "килограмм", "килограмма", "килограммов", "килограммами", "килограм", "кила", "кил");
        stems(unit(Unit.SECONDS, SECONDS_PER_MINUTE), "минута", "минуты", "минут", "минуту", "минуте", "минутой",
                "минутка", "минутки");
        stems(unit(Unit.SECONDS, 1), "секунда", "секунды", "секунд", "секунду", "секундами");
        stems(unit(Unit.SECONDS, SECONDS_PER_HOUR), "час", "часа", "часов");
        stems(unit(Unit.METERS, 1), "метр", "метра", "метров");
        stems(unit(Unit.METERS, METERS_PER_KILOMETER), "километр", "километра", "километров", "километре");
        stems(unit(Unit.PERCENT, 1), "процент", "процента", "процентов", "процентами");
        // Инвентарь тоже указывает на вес: «гантели по десять», «с гантелей тридцать два».
        stems(entry(Kind.WEIGHT_WORD), "вес", "весом", "блин", "блином", "блинами", "отягощение", "отягощением",
                "гантели", "гантель", "гантелью", "гантелей", "гантелями", "гантелька", "гантельки", "штанга", "штангой",
                "штанги", "гриф", "грифом", "гиря", "гирей", "гири");
        stems(entry(Kind.REST), "отдых", "отдыха", "отдыхом", "отдыхать", "отдыхаем");
        stems(entry(Kind.PAUSE), "пауза", "паузой", "паузы");
        stems(entry(Kind.WARMUP), "разминка", "разминки", "разминочный", "разминочные", "разминочных", "разминочного");
        stems(entry(Kind.WORKING), "рабочий", "рабочие", "рабочих", "рабочего");
    }

    private Lexicon() { }

    static Entry find(String word, String stem) {
        var e = EXACT.get(word);
        if (e != null || word.length() < MIN_STEM_LOOKUP) return e;
        return STEMS.get(stem);
    }

    /** Есть ли у основы служебное значение — такие слова не исправляются нечётким поиском. */
    static boolean isServiceWord(String word, String stem) { return find(word, stem) != null; }

    private static Entry entry(Kind kind) { return new Entry(kind, null, 1); }

    private static Entry unit(Unit unit, double factor) { return new Entry(Kind.UNIT, unit, factor); }

    private static void exact(Entry e, String... words) { for (var w : words) EXACT.put(w, e); }

    private static void stems(Entry e, String... words) { for (var w : words) STEMS.put(RussianStemmer.stem(w), e); }
}
