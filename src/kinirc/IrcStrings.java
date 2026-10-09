package kinirc;

// Tabla central de idiomas (ES/EN). Acceso por indice int: O(1) y
// sin objetos por llamada. Se carga UNA vez al arrancar desde
// /lang/strings_<idioma>.txt y solo reside el idioma activo.
// Generado: no editar a mano (ver tools/gen_strings.py).
public final class IrcStrings {
    private IrcStrings() {
    }

    public static final int TITLE_PROFILES = 0;
    public static final int CMD_NEW = 1;
    public static final int CMD_EDIT = 2;
    public static final int CMD_DELETE = 3;
    public static final int CMD_INTERFACE = 4;
    public static final int CMD_HELP = 5;
    public static final int CMD_EXIT = 6;
    public static final int NOTICE_COMPLETE_PROFILE = 7;
    public static final int TITLE_NEW_PROFILE = 8;
    public static final int TITLE_EDIT_PROFILE = 9;
    public static final int FLD_PROFILE_NAME = 10;
    public static final int FLD_SERVER = 11;
    public static final int FLD_PORT = 12;
    public static final int FLD_NICK = 13;
    public static final int FLD_USER = 14;
    public static final int FLD_REALNAME = 15;
    public static final int FLD_PASSWORD = 16;
    public static final int FLD_CHANNELS = 17;
    public static final int CMD_SAVE = 18;
    public static final int CMD_SAVE_CONNECT = 19;
    public static final int CMD_CANCEL = 20;
    public static final int CMD_BACK_SHORT = 21;
    public static final int ERR_PORT_NUMBER = 22;
    public static final int ERR_PORT_INVALID = 23;
    public static final int ERR_MISSING_SERVER_NICK = 24;
    public static final int ERR_MAX_PROFILES = 25;
    public static final int TITLE_INTERFACE = 26;
    public static final int FLD_MSG_WINDOW = 27;
    public static final int GRP_JOIN = 28;
    public static final int JOIN_FINE = 29;
    public static final int JOIN_COMPACT = 30;
    public static final int JOIN_NORMAL = 31;
    public static final int JOIN_HIDDEN = 32;
    public static final int GRP_ALERTS = 36;
    public static final int ALERT_OFF = 37;
    public static final int ALERT_SOUND = 38;
    public static final int ALERT_VIBRATE = 39;
    public static final int GRP_RECONNECT = 40;
    public static final int OPT_YES = 41;
    public static final int OPT_NO = 42;
    public static final int GRP_NICKBOLD = 43;
    public static final int GRP_FONT = 44;
    public static final int FONT_MINI = 45;
    public static final int FONT_SMALL = 46;
    public static final int FONT_NORMAL = 47;
    public static final int FONT_LARGE = 48;
    public static final int FLD_MAX_CHAN = 49;
    public static final int FLD_MAX_WIN = 50;
    public static final int FLD_REFRESH = 51;
    public static final int GRP_BG = 52;
    public static final int BG_SAVE = 53;
    public static final int BG_KEEP = 54;
    public static final int GRP_INDICATOR = 55;
    public static final int IND_NUM = 56;
    public static final int IND_BARS = 57;
    public static final int IND_BOTH = 58;
    public static final int FLD_MARGIN = 60;
    public static final int ERR_ALL_NUMBERS = 61;
    public static final int ERR_LINES = 62;
    public static final int ERR_CHANS = 63;
    public static final int ERR_WINDOWS = 64;
    public static final int ERR_REFRESH = 65;
    public static final int ERR_MARGIN = 66;
    public static final int TITLE_ACTIONS = 67;
    public static final int ACT_EMPTY = 68;
    public static final int TITLE_ACTION = 69;
    public static final int TITLE_LANGUAGE = 70;
    public static final int GRP_LANGUAGE = 71;
    public static final int EMPTY_CHAT = 72;
    public static final int MEM_TITLE = 73;
    public static final int MEM_TOTAL = 74;
    public static final int MEM_USED = 75;
    public static final int MEM_MAX = 76;
    public static final int MEM_T = 77;
    public static final int MEM_W = 78;
    public static final int MEM_MSG = 79;
    public static final int MEM_NICKS = 80;
    public static final int MEM_THREADS = 81;
    public static final int TITLE_MESSAGE = 82;
    public static final int CMD_SEND = 83;
    public static final int TITLE_JOINKEY = 84;
    public static final int CMD_JOIN_ENTER = 85;
    public static final int NOTICE_NICKS_CHANNEL = 86;
    public static final int MENU_WHOIS_PRE = 87;
    public static final int MENU_UNIGNORE = 88;
    public static final int MENU_IGNORE = 89;
    public static final int MENU_NICKS = 90;
    public static final int MENU_ACTIONS = 91;
    public static final int MENU_JOIN = 92;
    public static final int MENU_WINDOWS = 93;
    public static final int MENU_CLEAR = 94;
    public static final int MENU_CHANLIST = 95;
    public static final int MENU_CHANCTL = 96;
    public static final int MENU_INTERFACE = 97;
    public static final int MENU_IGNORES = 98;
    public static final int MENU_EXIT = 99;
    public static final int MENU_DISCONNECT = 100;
    public static final int MENU_CONNECT = 101;
    public static final int MENU_MINIMIZE = 102;
    public static final int MENU_CLOSE = 103;
    public static final int CMD_BACK = 104;
    public static final int NOTICE_OFFLINE = 105;
    public static final int CC_TOPIC = 106;
    public static final int CC_KICK = 107;
    public static final int CC_BAN = 108;
    public static final int CC_UNBAN = 109;
    public static final int CC_OP = 110;
    public static final int CC_DEOP = 111;
    public static final int CC_VOICE = 112;
    public static final int CC_DEVOICE = 113;
    public static final int CC_PRIVATE = 114;
    public static final int CC_PUBLIC = 115;
    public static final int CC_INVITE = 116;
    public static final int CC_UNINVITE = 117;
    public static final int CC_MOD = 118;
    public static final int CC_UNMOD = 119;
    public static final int CC_SETKEY = 120;
    public static final int CC_UNSETKEY = 121;
    public static final int TITLE_TOPIC = 122;
    public static final int CMD_SET = 123;
    public static final int TITLE_KEY = 124;
    public static final int NOTICE_NO_PROFILE = 125;
    public static final int HINT_JOIN_CHAN = 126;
    public static final int STATE_CONNECTING = 127;
    public static final int STATE_CONNECTED_PRE = 128;
    public static final int STATE_DISCONNECTED = 129;
    public static final int NOTICE_NO_PROFILE_CFG = 130;
    public static final int STATUS_CONNECTING_PRE = 131;
    public static final int QUIT_MESSAGE = 132;
    public static final int ERR_NO_WINDOW = 133;
    public static final int ERR_NOT_IN_CHANNEL = 134;
    public static final int ERR_SEND_OFFLINE = 135;
    public static final int ERR_JOIN_OFFLINE = 136;
    public static final int ERR_NAMES_OFFLINE = 137;
    public static final int ERR_LIST_OFFLINE = 138;
    public static final int ERR_MAX_WINDOWS = 139;
    public static final int SYS_UNBANNED_PRE = 140;
    public static final int SUFFIX_IGNORED = 141;
    public static final int SUFFIX_UNIGNORED = 142;
    public static final int CHAN_ONE = 143;
    public static final int CHAN_MANY_SUF = 144;
    public static final int SYS_PAUSED_PRE = 145;
    public static final int SYS_RESUMED_PRE = 146;
    public static final int HELP_L1 = 149;
    public static final int HELP_L2 = 150;
    public static final int HELP_L3 = 151;
    public static final int HELP_L4 = 152;
    public static final int HELP_L5 = 153;
    public static final int USAGE_WHOIS = 154;
    public static final int USAGE_TOPIC = 155;
    public static final int USAGE_KICK = 156;
    public static final int USAGE_BAN = 157;
    public static final int USAGE_INVITE = 158;
    public static final int USAGE_MODE = 159;
    public static final int ERR_NOPERM = 160;
    public static final int ERR_TIMEOUT = 161;
    public static final int ERR_REFUSED = 162;
    public static final int ERR_UNREACH = 163;
    public static final int ERR_SOCKREAD = 164;
    public static final int ERR_CONN_LOST = 165;
    public static final int ERR_CONN_FAIL = 166;
    public static final int ERR_NO_RESPONSE = 167;
    public static final int REC_PREFIX = 168;
    public static final int REC_SUFFIX = 169;
    public static final int ERR_LINE_LONG = 170;
    public static final int ERR_NICK_TAKEN = 171;
    public static final int NOTICE_SERVER = 172;
    public static final int NOTICE_WHOIS = 173;
    public static final int ERR_CANNOT_JOIN_PRE = 174;
    public static final int T_002 = 175;
    public static final int T_003 = 176;
    public static final int T_004 = 177;
    public static final int T_005 = 178;
    public static final int T_251 = 179;
    public static final int T_252 = 180;
    public static final int T_253 = 181;
    public static final int T_254 = 182;
    public static final int T_255 = 183;
    public static final int T_265 = 184;
    public static final int T_266 = 185;
    public static final int T_329 = 186;
    public static final int T_333 = 187;
    public static final int T_341 = 188;
    public static final int T_352 = 189;
    public static final int T_315 = 190;
    public static final int T_401 = 191;
    public static final int T_402 = 192;
    public static final int T_405 = 193;
    public static final int T_406 = 194;
    public static final int T_421 = 195;
    public static final int T_441 = 196;
    public static final int T_442 = 197;
    public static final int T_472 = 198;
    public static final int T_474 = 199;
    public static final int T_478 = 200;
    public static final int T_481 = 201;
    public static final int CH_LIST_PRE = 202;
    public static final int CH_LIST_POST = 203;
    public static final int BANS_PRE = 204;
    public static final int WHO_USES = 205;
    public static final int WHO_IDLE_PRE = 206;
    public static final int WHO_IDLE_POST = 207;
    public static final int WHO_ON = 208;
    public static final int WHO_END = 209;
    public static final int JOIN_IN_PRE = 210;
    public static final int PART_LEFT_PRE = 211;
    public static final int NICK_LABEL_PRE = 216;
    public static final int ERR_LIST_NORESPONSE = 217;
    public static final int NOTICE_STATUS_CLOSE = 218;
    public static final int KICK_DEFAULT = 219;
    public static final int NICK_NOW = 220;
    public static final int TOPIC_PRE = 221;
    public static final int BAN_TITLE_PRE = 222;
    public static final int CMD_REMOVE = 223;
    public static final int CHAN_SEARCHING = 224;
    public static final int CHAN_TITLE_PRE = 225;
    public static final int CHAN_SEARCHING_SHORT = 226;
    public static final int LIST_EMPTY = 227;
    public static final int IGN_TITLE_PRE = 228;
    public static final int IGN_NICK_SUFFIX = 229;
    public static final int IGN_EMPTY = 230;
    public static final int WIN_TITLE_PRE = 231;
    public static final int WIN_VIEW_TOPIC = 232;
    public static final int NO_TOPIC = 233;
    public static final int CMD_CHOOSE = 234;
    public static final int GRP_EVENTS = 235;
    public static final int EVT_HIDE_ALL = 236;
    public static final int EVT_HIDE_JOINS = 237;
    public static final int EVT_SHOW_ALL = 238;
    public static final int GRP_JOINSTYLE = 239;
    public static final int ERR_NICK_IN_USE_PRE = 240;
    public static final int ERR_NICK_INVALID_PRE = 241;
    public static final int ERR_SAVE_FAILED = 242;

    public static final int COUNT = 243;

    private static String[] table;
    private static int language = -1;

    public static synchronized int getLanguage() {
        return language;
    }

    public static synchronized boolean isLoaded() {
        return table != null;
    }

    public static synchronized String get(int index) {
        if (table == null || index < 0 || index >= table.length) {
            return "";
        }
        String value = table[index];
        return value == null ? "" : value;
    }

    // Carga el idioma (0=ES, 1=EN): siempre el ES de base y el EN
    // encima; lo que falte en EN cae al ES sin huecos.
    public static synchronized void load(int lang) {
        String[] base = readTable("/lang/strings_es.txt");
        String[] merged;
        if (lang == 1 && base != null) {
            String[] en = readTable("/lang/strings_en.txt");
            if (en != null) {
                int i = 0;
                while (i < COUNT) {
                    if (en[i] == null) {
                        en[i] = base[i];
                    }
                    i++;
                }
                merged = en;
            } else {
                merged = base;
                lang = 0;
            }
        } else {
            merged = base;
            lang = 0;
        }
        if (merged != null) {
            table = merged;
            language = lang;
        }
    }

    // Lee indice=texto (ignora huecos, '#' y lineas rotas). Sin
    // objetos por linea mas alla del String resultante.
    private static String[] readTable(String path) {
        java.io.InputStream input = null;
        try {
            input = IrcStrings.class.getResourceAsStream(path);
            if (input == null) {
                return null;
            }
            byte[] buf = new byte[512];
            java.io.ByteArrayOutputStream bytes =
                new java.io.ByteArrayOutputStream(12288);
            int n;
            while ((n = input.read(buf)) > 0) {
                bytes.write(buf, 0, n);
            }
            String text;
            try {
                text = new String(bytes.toByteArray(), 0, bytes.size(), "UTF-8");
            } catch (Exception e) {
                text = new String(bytes.toByteArray(), 0, bytes.size());
            }
            String[] table = new String[COUNT];
            int start = 0;
            int pos = 0;
            int length = text.length();
            while (pos <= length) {
                char c = pos < length ? text.charAt(pos) : '\n';
                if (c == '\n') {
                    parseLine(text.substring(start, pos), table);
                    start = pos + 1;
                }
                pos++;
            }
            return table;
        } catch (Exception e) {
            return null;
        } catch (Error e) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void parseLine(String line, String[] table) {
        int length = line.length();
        if (length == 0) {
            return;
        }
        if (line.charAt(0) == '#') {
            return;
        }
        // Ultimo \r fuera (ficheros con CRLF).
        if (line.charAt(length - 1) == '\r') {
            line = line.substring(0, length - 1);
            length--;
            if (length == 0) {
                return;
            }
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return;
        }
        int index;
        try {
            index = Integer.parseInt(line.substring(0, eq));
        } catch (NumberFormatException e) {
            return;
        }
        if (index < 0 || index >= COUNT) {
            return;
        }
        table[index] = unescape(line.substring(eq + 1));
    }

    // Solo \\n y \\\\: el resto se deja tal cual.
    private static String unescape(String value) {
        int mark = value.indexOf('\\');
        if (mark < 0) {
            return value;
        }
        StringBuffer out = new StringBuffer(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == 'n') {
                    out.append('\n');
                    i += 2;
                    continue;
                }
                if (next == '\\') {
                    out.append('\\');
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // Ayuda bajo demanda: secciones {titulo, cuerpo} del
    // fichero help_<es|en>.txt segun el idioma cargado.
    public static synchronized String[][] readHelp() {
        String path = language == 1 ? "/lang/help_en.txt" : "/lang/help_es.txt";
        try {
            java.io.InputStream input =
                IrcStrings.class.getResourceAsStream(path);
            if (input == null) {
                return null;
            }
            byte[] buf = new byte[512];
            java.io.ByteArrayOutputStream bytes =
                new java.io.ByteArrayOutputStream(8192);
            int n;
            while ((n = input.read(buf)) > 0) {
                bytes.write(buf, 0, n);
            }
            try {
                input.close();
            } catch (Exception ignored) {
            }
            String text;
            try {
                text = new String(bytes.toByteArray(), 0, bytes.size(), "UTF-8");
            } catch (Exception e) {
                text = new String(bytes.toByteArray(), 0, bytes.size());
            }
            java.util.Vector titles = new java.util.Vector();
            java.util.Vector bodies = new java.util.Vector();
            String title = null;
            StringBuffer body = new StringBuffer();
            int start = 0;
            int pos = 0;
            int length = text.length();
            while (pos <= length) {
                char c = pos < length ? text.charAt(pos) : '\n';
                if (c == '\n') {
                    String line = text.substring(start, pos);
                    int end = line.length();
                    if (end > 0 && line.charAt(end - 1) == '\r') {
                        line = line.substring(0, end - 1);
                    }
                    if (line.length() > 3 && line.charAt(0) == '#'
                        && line.charAt(1) == '#'
                        && line.charAt(2) == ' ') {
                        if (title != null) {
                            titles.addElement(title);
                            bodies.addElement(body.toString());
                        }
                        title = line.substring(3);
                        body = new StringBuffer();
                    } else if (title != null && line.length() > 0) {
                        if (body.length() > 0) {
                            body.append('\n');
                        }
                        body.append(line);
                    }
                    start = pos + 1;
                }
                pos++;
            }
            if (title != null) {
                titles.addElement(title);
                bodies.addElement(body.toString());
            }
            if (titles.size() == 0) {
                return null;
            }
            String[][] out = new String[titles.size()][2];
            int i = 0;
            while (i < titles.size()) {
                out[i][0] = (String) titles.elementAt(i);
                out[i][1] = (String) bodies.elementAt(i);
                i++;
            }
            return out;
        } catch (Exception e) {
            return null;
        } catch (Error e) {
            return null;
        }
    }
}
