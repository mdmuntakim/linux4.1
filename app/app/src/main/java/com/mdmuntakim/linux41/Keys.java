package com.mdmuntakim.linux41;

/** X11 keysyms used by RFB and the mapping from Android key codes. */
public final class Keys {
    public static final int BACKSPACE = 0xff08;
    public static final int TAB = 0xff09;
    public static final int ENTER = 0xff0d;
    public static final int ESCAPE = 0xff1b;
    public static final int DELETE = 0xffff;
    public static final int HOME = 0xff50;
    public static final int END = 0xff57;
    public static final int PAGE_UP = 0xff55;
    public static final int PAGE_DOWN = 0xff56;
    public static final int INSERT = 0xff63;
    public static final int LEFT = 0xff51;
    public static final int UP = 0xff52;
    public static final int RIGHT = 0xff53;
    public static final int DOWN = 0xff54;
    public static final int SHIFT = 0xffe1;
    public static final int CONTROL = 0xffe3;
    public static final int ALT = 0xffe9;
    public static final int SUPER = 0xffeb;
    public static final int CAPS_LOCK = 0xffe5;
    public static final int F1 = 0xffbe;

    private Keys() { }

    /** Key code for printable keys on the on-screen keyboard. */
    public static int forChar(char c) {
        return c;
    }

    public static int forKeyCode(int keyCode) {
        if (keyCode >= 7 && keyCode <= 16) return '0' + (keyCode - 7);      // KEYCODE_0..9
        if (keyCode >= 29 && keyCode <= 54) return 'a' + (keyCode - 29);    // KEYCODE_A..Z
        switch (keyCode) {
            case 67: return BACKSPACE;
            case 61: return TAB;
            case 66: return ENTER;
            case 111: return ESCAPE;
            case 112: return DELETE;
            case 122: return HOME;
            case 123: return END;
            case 92: return PAGE_UP;
            case 93: return PAGE_DOWN;
            case 124: return INSERT;
            case 21: return LEFT;
            case 22: return RIGHT;
            case 19: return UP;
            case 20: return DOWN;
            case 59: return SHIFT;
            case 60: return SHIFT;
            case 113: return CONTROL;
            case 114: return CONTROL;
            case 57: return ALT;
            case 58: return ALT;
            case 117: return SUPER;
            case 118: return SUPER;
            case 115: return CAPS_LOCK;
            case 131: return F1;
            case 132: return F1 + 1;
            case 133: return F1 + 2;
            case 134: return F1 + 3;
            case 135: return F1 + 4;
            case 136: return F1 + 5;
            case 137: return F1 + 6;
            case 138: return F1 + 7;
            case 139: return F1 + 8;
            case 140: return F1 + 9;
            case 141: return F1 + 10;
            case 142: return F1 + 11;
            case 68: return 0xff2e;  // grave
            case 69: return 0x2d;    // minus
            case 70: return 0x3d;    // equal
            case 71: return 0x5b;    // bracketleft
            case 72: return 0x5d;    // bracketright
            case 73: return 0x5c;    // backslash
            case 74: return 0x3b;    // semicolon
            case 75: return 0x27;    // apostrophe
            case 76: return 0x2f;    // slash
            case 55: return 0x2c;    // comma
            case 56: return 0x2e;    // period
            default: return 0;
        }
    }
}
