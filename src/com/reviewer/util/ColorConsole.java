package com.reviewer.util;

import java.util.regex.*;
import java.util.*;

public class ColorConsole {
    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String BLACK = "\u001B[30m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";
    
    public static final String BG_YELLOW = "\u001B[43m";
    public static final String BG_MAGENTA = "\u001B[45m";
    public static final String BG_RED = "\u001B[41m";

    public static void success(String msg) {
        System.out.println(GREEN + BOLD + " [SUCCESS] " + RESET + GREEN + msg + RESET);
    }

    public static void warning(String msg) {
        System.out.println(YELLOW + BOLD + " [WARNING] " + RESET + YELLOW + msg + RESET);
    }

    public static void error(String msg) {
        System.out.println(RED + BOLD + " [ERROR]   " + RESET + RED + msg + RESET);
    }
}
