package kinirc;

import javax.microedition.lcdui.Font;

public final class IrcMessage {
    public static final int CHAT = 0;
    public static final int ACTION = 1;
    public static final int NOTICE = 4;
    public static final int SYSTEM = 5;
    public static final int ERROR = 6;
    public static final int TOPIC = 7;
    public static final int NICK = 8;
    public static final int KICK = 9;
    public static final int JOIN_ARROW = 10;
    public static final int PART_ARROW = 11;

    public static final int COLOR_TEXT = 0xffffff;
    public static final int COLOR_NICK = 0x33ccff;
    public static final int COLOR_JOIN = 0x00ff00;
    public static final int COLOR_PART = 0xff3333;
    public static final int COLOR_NOTICE = 0xffff00;
    public static final int COLOR_ACTION = 0xff66cc;
    public static final int COLOR_MENTION = 0xff6600;
    public static final int COLOR_ERROR = 0xff4444;
    public static final int COLOR_SYSTEM = 0xcccccc;
    public static final int COLOR_TOPIC = 0xffff99;

    private final int type;
    private final String nick;
    private final String text;
    private final int color;
    private final int nickColor;
    private final boolean bold;
    private final boolean mentioned;
    private final long time;
    // Cache de pintado: el texto visible y su troceado para un ancho y
    // letra dados. Se calcula una vez al insertar (hilo de red) y el
    // paint() solo lee: cero substringWidth y cero concatenaciones por
    // frame. No cuenta para el limite (no se almacena en la ventana).
    private String cachedDisplay;
    private String[] cachedLines;
    private int cachedWidth = -1;
    private int cachedFont = -1;
    private boolean cachedBold;

    private IrcMessage(int type, String nick, String text, int color, boolean bold) {
        this(type, nick, text, color, COLOR_NICK, bold, false);
    }

    private IrcMessage(int type, String nick, String text, int color, int nickColor, boolean bold) {
        this(type, nick, text, color, nickColor, bold, false);
    }

    private IrcMessage(int type, String nick, String text, int color, int nickColor, boolean bold,
        boolean mentioned) {
        this.type = type;
        this.nick = nick == null ? "" : nick;
        this.text = IrcText.stripCodes(IrcText.clean(text));
        this.color = color;
        this.nickColor = nickColor;
        this.bold = bold;
        this.mentioned = mentioned;
        // Marca temporal para separar bloques en Status (solo dibujo,
        // no se guarda nada extra ni consume limite).
        this.time = System.currentTimeMillis();
    }

    // Cuándo se creó el mensaje (hora que se pinta en Status).
    public long getTime() {
        return time;
    }

    // Texto "[HH:MM]" con la hora local: se calcula una sola vez por
    // mensaje y el paint() solo lo lee (cero basura por frame).
    private String cachedClock;

    public synchronized String getClockText() {
        if (cachedClock == null) {
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.setTime(new java.util.Date(time));
            int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
            int minute = calendar.get(java.util.Calendar.MINUTE);
            StringBuffer text = new StringBuffer("[");
            if (hour < 10) {
                text.append('0');
            }
            text.append(hour);
            text.append(':');
            if (minute < 10) {
                text.append('0');
            }
            text.append(minute);
            text.append(']');
            cachedClock = text.toString();
        }
        return cachedClock;
    }

    public static IrcMessage chat(String nick, String text, int nickColor) {
        return new IrcMessage(CHAT, nick, text, COLOR_TEXT, nickColor, true);
    }

    public static IrcMessage mention(String nick, String text, int nickColor) {
        return new IrcMessage(CHAT, nick, text, COLOR_MENTION, nickColor, false, true);
    }

    public static IrcMessage action(String nick, String text) {
        return new IrcMessage(ACTION, nick, text, COLOR_ACTION, true);
    }

    public static IrcMessage joinArrow(String nick) {
        return new IrcMessage(JOIN_ARROW, nick, "-> " + nick, COLOR_JOIN, true);
    }

    public static IrcMessage joinArrowFine(String nick) {
        return new IrcMessage(JOIN_ARROW, nick, "-> " + nick, COLOR_JOIN, false);
    }

    public static IrcMessage partArrow(String nick) {
        return new IrcMessage(PART_ARROW, nick, "<- " + nick, COLOR_PART, true);
    }

    public static IrcMessage partArrowFine(String nick) {
        return new IrcMessage(PART_ARROW, nick, "<- " + nick, COLOR_PART, false);
    }

    public static IrcMessage notice(String nick, String text) {
        return new IrcMessage(NOTICE, nick, text, COLOR_ACTION, false);
    }

    public static IrcMessage system(String text) {
        return new IrcMessage(SYSTEM, "", text, COLOR_SYSTEM, false);
    }

    public static IrcMessage system(String text, int color) {
        return new IrcMessage(SYSTEM, "", text, color, false);
    }

    public static IrcMessage error(String text) {
        return new IrcMessage(ERROR, "", text, COLOR_ERROR, true);
    }

    public static IrcMessage topic(String nick, String text, int color) {
        return new IrcMessage(TOPIC, nick, text, color, true);
    }

    public static IrcMessage nick(String oldNick, String newNick) {
        return new IrcMessage(NICK, oldNick, newNick, COLOR_NICK, true);
    }

    public static IrcMessage nickFine(String oldNick, String newNick) {
        return new IrcMessage(NICK, oldNick, newNick, COLOR_NICK, false);
    }

    public static IrcMessage kick(String nick, String reason) {
        if (reason == null || reason.length() == 0) {
            reason = IrcStrings.get(IrcStrings.KICK_DEFAULT);
        }
        return new IrcMessage(KICK, nick, reason, COLOR_ERROR, true);
    }

    public int getType() {
        return type;
    }

    public String getNick() {
        return nick;
    }

    public String getText() {
        return text;
    }

    public int getColor() {
        return color;
    }

    public int getNickColor() {
        return nickColor;
    }

    public boolean isMentioned() {
        return mentioned;
    }

    public boolean isBold() {
        return bold;
    }

    public boolean isSingleLine() {
        return type == JOIN_ARROW || type == PART_ARROW || type == NICK;
    }

    public synchronized String getDisplayText() {
        if (cachedDisplay == null) {
            cachedDisplay = buildDisplayText();
        }
        return cachedDisplay;
    }

    // Precalculo del troceado para un ancho/letra (llamado al insertar en
    // la ventana; si la letra, el ancho o la negrita de nicks cambian se
    // recalcula una vez). Con nicks en negrita, el CHAT se trocea en
    // mixto (prefijo "nick: " en negrita, mensaje en normal) para no
    // desperdiciar el ancho del texto normal; el resto igual que antes.
    public synchronized void ensureWrap(int width, int fontSize, boolean nickBold) {
        if (cachedLines != null && cachedWidth == width && cachedFont == fontSize
            && cachedBold == nickBold) {
            return;
        }
        Font plain = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, fontSize);
        if (nickBold && type == CHAT) {
            Font bold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, fontSize);
            cachedLines = IrcDraw.wrapChatMixed(getDisplayText(), nick.length() + 2, bold, plain,
                width);
        } else {
            cachedLines = IrcDraw.wrapAll(getDisplayText(), plain, width);
        }
        cachedWidth = width;
        cachedFont = fontSize;
        cachedBold = nickBold;
    }

    public synchronized int getWrappedCount(int width, int fontSize, boolean nickBold) {
        ensureWrap(width, fontSize, nickBold);
        return cachedLines.length;
    }

    public synchronized String getWrappedLine(int width, int fontSize, boolean nickBold,
        int index) {
        ensureWrap(width, fontSize, nickBold);
        if (index < 0 || index >= cachedLines.length) {
            return null;
        }
        return cachedLines[index];
    }

    private String buildDisplayText() {
        if (type == CHAT) {
            return nick + ": " + text;
        }
        if (type == ACTION) {
            return "* " + nick + " " + text;
        }
        if (type == NOTICE) {
            if (nick.length() > 0) {
                return "[" + nick + "] " + text;
            }
            return text;
        }
        if (type == NICK) {
            return "~ " + nick + IrcStrings.get(IrcStrings.NICK_NOW) + text;
        }
        if (type == KICK) {
            return "! " + nick + " " + text;
        }
        if (type == TOPIC) {
            return IrcStrings.get(IrcStrings.TOPIC_PRE) + text;
        }
        if (type == JOIN_ARROW || type == PART_ARROW) {
            return text;
        }
        return text;
    }
}
