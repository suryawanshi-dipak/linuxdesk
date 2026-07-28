package com.linuxdesk.ui;

/**
 * Strips ANSI/VT100 escape sequences (color codes, cursor moves, OSC title-set sequences) from a
 * streamed character feed so a plain JavaFX TextArea renders clean text instead of escape-code
 * garbage. Stateful across calls so sequences split across read buffers still parse correctly.
 */
final class AnsiFilter {

    private enum State { NORMAL, ESC, CSI, OSC, OSC_ESC, CHARSET }

    private State state = State.NORMAL;

    String filter(String input) {
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (state) {
                case NORMAL:
                    if (c == 0x1B) {
                        state = State.ESC;
                    } else if (c != '\r') {
                        out.append(c);
                    }
                    break;
                case ESC:
                    if (c == '[') {
                        state = State.CSI;
                    } else if (c == ']') {
                        state = State.OSC;
                    } else if (c == '(' || c == ')') {
                        state = State.CHARSET;
                    } else {
                        state = State.NORMAL;
                    }
                    break;
                case CSI:
                    if (c >= 0x40 && c <= 0x7E) {
                        state = State.NORMAL;
                    }
                    break;
                case OSC:
                    if (c == 0x07) {
                        state = State.NORMAL;
                    } else if (c == 0x1B) {
                        state = State.OSC_ESC;
                    }
                    break;
                case OSC_ESC:
                    state = (c == '\\') ? State.NORMAL : State.OSC;
                    break;
                case CHARSET:
                    state = State.NORMAL;
                    break;
            }
        }
        return out.toString();
    }
}
