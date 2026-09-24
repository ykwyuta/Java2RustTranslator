public class Strings {
    static final String SEP = " | ";

    static boolean isPalindrome(String s) {
        int i = 0, j = s.length() - 1;
        while (i < j) {
            if (s.charAt(i++) != s.charAt(j--)) return false;
        }
        return true;
    }

    static String caesar(String s, int shift) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append((char) ('A' + (c - 'A' + shift) % 26));
            } else if (Character.isLowerCase(c)) {
                sb.append((char) ('a' + (c - 'a' + shift) % 26));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        String s = "Hello, World";
        System.out.println(s.length() + SEP + s.toUpperCase() + SEP + s.toLowerCase() + SEP + s.substring(7));
        System.out.println(s.indexOf("o") + " " + s.lastIndexOf("o") + " " + s.indexOf('W') + " " + s.contains("World"));
        System.out.println(s.startsWith("Hell") + " " + s.endsWith("d") + " " + s.replace('l', 'L') + " " + s.replace("World", "Rust"));
        System.out.println("  padded  ".trim() + "|" + "ab".repeat(3) + "|" + "x".isEmpty() + "|" + "".isEmpty());
        System.out.println("apple".compareTo("banana") + " " + "b".compareTo("a") + " " + "abc".compareTo("ab") + " " + "Hello".hashCode());
        System.out.println(isPalindrome("racecar") + " " + isPalindrome("rust"));
        System.out.println(caesar("Hello, World!", 3));
        String t = "";
        for (int i = 0; i < 5; i++) {
            t += i;
            t += ',';
        }
        System.out.println(t);
        StringBuilder sb = new StringBuilder("abc");
        sb.append(1).append('-').append(2.5).append(true).insert(0, "[").append("]");
        System.out.println(sb + " " + sb.length() + " " + sb.reverse());
        char c = 'x';
        String fromChar = String.valueOf(c) + c + (char) (c + 1) + (c + 1);
        System.out.println(fromChar);
        System.out.println("Ünïcödé".length() + " " + "日本語".charAt(1) + " " + "日本語".substring(1, 2));
        System.out.println("equals: " + "abc".equals("a" + "bc") + " " + "ABC".equalsIgnoreCase("abc"));
        String num = "12345";
        int total = 0;
        for (int i = 0; i < num.length(); i++) {
            total += num.charAt(i) - '0';
        }
        System.out.println("digit sum " + total + " " + Character.isDigit('7') + " " + Character.isLetter('7'));
        System.out.println(1 + 2 + "3" + 4 + 5);
        System.out.println("tab\tquote\"backslash\\");
    }
}
