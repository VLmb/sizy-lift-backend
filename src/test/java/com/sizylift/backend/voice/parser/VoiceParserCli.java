package com.sizylift.backend.voice.parser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ручная проверка разбора: {@code ./gradlew -q voiceParse --console=plain}, затем фраза на строку.
 * Флаг {@code --json} печатает полный ответ (с кандидатами и уверенностью). Без Spring и базы.
 */
public final class VoiceParserCli {
    private static final String JSON_FLAG = "--json";
    /** По строке JSON на фразу, без подсказок и пояснений: для скриптов сравнения. */
    private static final String JSONL_FLAG = "--jsonl";

    private VoiceParserCli() { }

    public static void main(String[] args) throws Exception {
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        boolean json = args.length > 0 && JSON_FLAG.equals(args[0]);
        var mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        var parser = new WorkoutTextParser(ExerciseDictionary.system());
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (args.length > 0 && JSONL_FLAG.equals(args[0])) {
            var compact = JsonMapper.builder().build();
            in.lines().forEach(l -> out.println(compact.writeValueAsString(parser.parse(l))));
            return;
        }
        out.println("Фраза на строку, пустая строка или Ctrl+D — выход.");
        out.print("> ");
        String line;
        while ((line = in.readLine()) != null && !line.isBlank()) {
            long start = System.nanoTime();
            var result = parser.parse(line);
            long micros = (System.nanoTime() - start) / 1000;
            ParsedWorkoutText.render(result).forEach(r -> out.println("  " + r));
            for (var item : result.items()) {
                if (!item.candidates().isEmpty()) {
                    out.println("  кандидаты для «" + item.spoken() + "»: "
                            + item.candidates().stream().map(ParsedWorkout.Candidate::name).toList());
                }
            }
            if (json) out.println(mapper.writeValueAsString(result));
            out.println("  (" + micros + " мкс)");
            out.print("> ");
        }
    }
}
