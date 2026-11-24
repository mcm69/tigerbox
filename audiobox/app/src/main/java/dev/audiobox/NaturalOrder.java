package dev.audiobox;

import java.util.Comparator;

/**
 * Natural filename order: digit runs compare by value, so "2 Intro" sorts before
 * "10 Outro". Everything else compares case-insensitively; ties fall back to the
 * plain string so the order is total and stable.
 */
public final class NaturalOrder implements Comparator<String> {
    public static final NaturalOrder INSTANCE = new NaturalOrder();

    private NaturalOrder() {}

    @Override
    public int compare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (isDigit(ca) && isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && isDigit(a.charAt(i))) i++;
                while (j < b.length() && isDigit(b.charAt(j))) j++;
                int c = compareNumbers(a.substring(si, i), b.substring(sj, j));
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        int c = Integer.compare(a.length() - i, b.length() - j);
        return c != 0 ? c : a.compareTo(b);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Compares digit strings by value without overflowing on long runs. */
    private static int compareNumbers(String x, String y) {
        String a = stripLeadingZeros(x), b = stripLeadingZeros(y);
        if (a.length() != b.length()) return Integer.compare(a.length(), b.length());
        int c = a.compareTo(b);
        return c != 0 ? c : Integer.compare(x.length(), y.length());
    }

    private static String stripLeadingZeros(String s) {
        int k = 0;
        while (k < s.length() - 1 && s.charAt(k) == '0') k++;
        return s.substring(k);
    }
}
