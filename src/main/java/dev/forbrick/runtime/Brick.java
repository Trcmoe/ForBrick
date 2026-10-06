package dev.forbrick.runtime;

import java.lang.instrument.Instrumentation;

/** The deliberately silent, immediate exit used by the selected version only. */
public final class Brick {
    private Brick() { }

    public static void main(String[] args) {
        Runtime.getRuntime().halt(0);
    }

    public static void premain(String args, Instrumentation instrumentation) {
        Runtime.getRuntime().halt(0);
    }
}
