import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Regexes {
    static void show(String label, Object value) {
        System.out.println(label + ": " + value);
    }

    public static void main(String[] args) {
        show("matches", "2024-01-15".matches("\\d{4}-\\d{2}-\\d{2}"));
        show("matches partial", "abc123".matches("[a-z]+"));
        show("email", "user.name+tag@example.co.jp".matches("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+"));
        show("replaceAll", "Hello   big    world".replaceAll("\\s+", " "));
        show("replaceAll groups", "2024-01-15".replaceAll("(\\d+)-(\\d+)-(\\d+)", "$3/$2/$1"));
        show("named groups", "John Smith".replaceAll("(?<first>\\w+) (?<last>\\w+)", "${last}, ${first}"));
        show("replaceFirst", "aaa".replaceFirst("a", "b"));
        show("escape dollar", "cost".replaceAll("cost", "\\$5"));
        show("empty matches", "abc".replaceAll("x*", "-"));
        show("case insensitive", "Java JAVA java".replaceAll("(?i)java", "J"));
        show("split", Arrays.toString("a1b22c333d".split("\\d+")));
        show("split limit", Arrays.toString("a,b,,c,,".split(",", -1)) + " " + Arrays.toString("a,b,,c,,".split(",", 2)));
        show("split lookahead", Arrays.toString("camelCaseString".split("(?=[A-Z])")));
        show("split empty", Arrays.toString("abc".split("")));
        show("split ws", Arrays.toString("  leading and trailing  ".split("\\s+")));
        show("split pipe", Arrays.toString("a|b|c".split("\\|")));

        Pattern p = Pattern.compile("(\\w+)@(\\w+)\\.com");
        Matcher m = p.matcher("contact: alice@example.com, bob@test.com; nobody@nowhere.org");
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group(1) + " at " + m.group(2) + " [" + m.start() + "," + m.end() + ")");
        }
        show("find", found);
        show("groupCount", m.groupCount());

        Matcher d = Pattern.compile("(?<year>\\d{4})-(?<month>\\d{2})").matcher("born 1990-05, moved 2001-11");
        StringBuilder sb = new StringBuilder();
        while (d.find()) {
            d.appendReplacement(sb, d.group("month") + "/" + d.group("year"));
        }
        d.appendTail(sb);
        show("appendReplacement", sb);

        show("greedy", firstGroup("<a><b>", "<(.+)>"));
        show("lazy", firstGroup("<a><b>", "<(.+?)>"));
        show("possessive", Pattern.compile("a++a").matcher("aaaa").find());
        show("atomic", Pattern.compile("(?>a+)b").matcher("aaab").find());
        show("backref", Pattern.compile("(\\w)\\1").matcher("hello").find() + " " + "abcabc".matches("(abc)\\1"));
        show("lookbehind", "price: $42, cost: $7".replaceAll("(?<=\\$)\\d+", "N"));
        show("negative lookahead", "foo1 foo2 bar3".replaceAll("\\b(?!bar)\\w+?(\\d)", "X$1"));
        show("anchors", Pattern.compile("^\\w+$", Pattern.MULTILINE).matcher("one\ntwo words\nthree").results().count());
        show("boundary", "cat concat cats".replaceAll("\\bcat\\b", "dog"));
        show("classes", "Hello, World! 123".replaceAll("[^a-zA-Z]", ""));
        show("intersection", "abcdefghij".replaceAll("[a-z&&[^aeiou]]", "*"));
        show("posix", "Tab\there".replaceAll("\\p{Upper}", "U").replaceAll("\\s", "_"));
        show("dotall", Pattern.compile("a.b", Pattern.DOTALL).matcher("a\nb").matches() + " " + "a\nb".matches("a.b"));
        show("alternation", "cat dog bird".replaceAll("cat|bird", "pet"));
        show("quantifier range", "aaaaa".replaceAll("a{2,3}", "X"));
        show("quote", "1+1=2".replaceAll(Pattern.quote("1+1"), "two"));
        show("unicode", "日本語テキスト abc".replaceAll("\\p{IsHiragana}|\\p{IsKatakana}", "_").length());
        Matcher lm = Pattern.compile("ab").matcher("abab");
        show("lookingAt", lm.lookingAt() + " " + lm.matches());
        try {
            Pattern.compile("(unclosed");
        } catch (PatternSyntaxException e) {
            show("syntax error", e.getMessage().split("\n")[0]);
        }
        try {
            Matcher x = Pattern.compile("a").matcher("b");
            x.group();
        } catch (IllegalStateException e) {
            show("no match", e.getMessage());
        }
    }

    static String firstGroup(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : null;
    }
}
