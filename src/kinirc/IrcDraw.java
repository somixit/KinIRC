package kinirc;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.TextBox;

// Utilidades de dibujo compartidas por los Canvas. Centraliza el
// ajuste de texto (fitText), el troceado por palabras (wrap) y la
// paleta/medidas para no repetir la misma logica en cada vista.
public final class IrcDraw {
    private IrcDraw() {
    }

    // Teclado en minusculas al abrir la escritura (el movil suele
    // arrancar en mayusculas). Es solo una sugerencia inicial: si el
    // movil no conoce el subset lo ignora sin error.
    public static void lowerCaseInput(TextBox input) {
        if (input == null) {
            return;
        }
        try {
            input.setInitialInputMode("MIDP_LOWERCASE_LATIN");
        } catch (Exception e) {
            // Subset no soportado: se usa el modo por defecto del movil.
        }
    }

    // Paleta compartida (antes literales 0x... repartidos por los Canvas).
    public static final int STRIP_BG = 0x203040;
    public static final int STRIP_TEXT = 0xffffff;
    public static final int STRIP_POS = 0xccddee;
    public static final int BAR_ACTIVE = 0xffffff;
    public static final int BAR_UNREAD = 0x66ff66;
    public static final int BAR_IDLE = 0x5a6a7a;
    public static final int ROW_SELECTED_BG = 0x31506a;
    public static final int MENTION_NICK_BG = 0x0000ff;
    public static final int ROW_TEXT = 0xdddddd;
    public static final int EMPTY_TEXT = 0x8899aa;
    public static final int SCROLL_TRACK = 0x334455;
    public static final int CONNECTED_DOT = 0x33cc33;
    public static final int DISCONNECTED_DOT = 0xcc3333;
    // Barras de pantallas modales (listas y ayuda): marron oscuro para
    // distinguirlas del azul del chat a simple vista.
    public static final int MODAL_STRIP_BG = 0x3a2818;
    // Titulo en ambar: ventana en proceso de cierre (saliendo).
    public static final int CLOSING_TITLE = 0xffcc33;
    // Marca de tope del historico: se pinta arriba del todo al llegar a
    // lo mas viejo del buffer para no seguir subiendo en vacio.
    public static final int HISTORY_TOP_MARK = 0xff0000;

    // Medidas compartidas.
    public static final int HEADER_H = 19;
    public static final int HEADER_TOP = 22;

    // Repeticion al mantener pulsado: paso siempre de 1 (suave) y lo que
    // acelera es el ritmo. La tarea late cada TICK_MS y avanza cuando toca
    // segun el periodo, que baja de START_MS a MIN_MS de STEP en STEP.
    public static final int REPEAT_TICK_MS = 40;
    public static final int REPEAT_START_MS = 220;
    public static final int REPEAT_MIN_MS = 40;
    public static final int REPEAT_STEP_MS = 15;

    // Periodo entre pasos segun cuantos se llevan en esta pulsacion.
    public static int repeatPeriod(int moves) {
        int period = REPEAT_START_MS - moves * REPEAT_STEP_MS;
        if (period < REPEAT_MIN_MS) {
            period = REPEAT_MIN_MS;
        }
        return period;
    }

    // Recorta un texto con "..." al final si no cabe en el ancho dado.
    // Busqueda binaria con substringWidth: O(log n) medidas y una sola
    // copia al final. Sin asignaciones dentro del bucle (apto para paint).
    public static String fitText(String value, Font font, int width) {
        if (value == null) {
            return "";
        }
        if (width <= 0) {
            return "...";
        }
        if (font.stringWidth(value) <= width) {
            return value;
        }
        if (font.stringWidth("...") > width) {
            return "...";
        }
        int low = 0;
        int high = value.length();
        // Invariante: low cabe, high no cabe (salvo high==len ya mirado).
        while (low + 1 < high) {
            int mid = (low + high) / 2;
            int w = font.substringWidth(value, 0, mid);
            w += font.stringWidth("...");
            if (w <= width) {
                low = mid;
            } else {
                high = mid;
            }
        }
        if (low == 0) {
            return "...";
        }
        return value.substring(0, low) + "...";
    }

    // Version con IrcWidth para no depender de Font en tests y en wrap.
    public static String fitText(String value, IrcWidth font, int width) {
        if (value == null) {
            return "";
        }
        if (width <= 0) {
            return "...";
        }
        if (font.stringWidth(value) <= width) {
            return value;
        }
        if (font.stringWidth("...") > width) {
            return "...";
        }
        int low = 0;
        int high = value.length();
        int dots = font.stringWidth("...");
        while (low + 1 < high) {
            int mid = (low + high) / 2;
            int w = font.substringWidth(value, 0, mid) + dots;
            if (w <= width) {
                low = mid;
            } else {
                high = mid;
            }
        }
        if (low == 0) {
            return "...";
        }
        return value.substring(0, low) + "...";
    }

    // Parte por palabras sin asignar substrings en el bucle interior:
    // usa substringWidth (sin copia) en vez de stringWidth(substring).
    public static int wrapCount(String value, IrcWidth font, int width) {
        if (value == null || value.length() == 0) {
            return 1;
        }
        if (width <= 0) {
            return value.length();
        }
        int length = value.length();
        int count = 0;
        int start = 0;
        while (start < length) {
            int end = start;
            while (end < length && font.substringWidth(value, start, end - start + 1) <= width) {
                end++;
            }
            if (end == start) {
                end = start + 1;
            }
            if (end < length) {
                int space = value.lastIndexOf(' ', end - 1);
                if (space > start) {
                    end = space;
                }
            }
            start = end;
            while (start < length && value.charAt(start) == ' ') {
                start++;
            }
            count++;
        }
        return count;
    }

    public static String wrapLine(String value, IrcWidth font, int width, int target) {
        if (value == null) {
            return null;
        }
        int length = value.length();
        if (width <= 0) {
            if (target >= 0 && target < length) {
                return value.substring(target, target + 1);
            }
            return target == length ? "" : null;
        }
        int count = 0;
        int start = 0;
        while (start < length) {
            int end = start;
            while (end < length && font.substringWidth(value, start, end - start + 1) <= width) {
                end++;
            }
            if (end == start) {
                end = start + 1;
            }
            if (end < length) {
                int space = value.lastIndexOf(' ', end - 1);
                if (space > start) {
                    end = space;
                }
            }
            if (count == target) {
                return value.substring(start, end);
            }
            start = end;
            while (start < length && value.charAt(start) == ' ') {
                start++;
            }
            count++;
        }
        if (count == target) {
            return "";
        }
        return null;
    }

    // Troceado completo en una pasada logica: reutiliza wrapCount/wrapLine
    // para garantizar el mismo corte. Se calcula una sola vez por mensaje
    // (al insertarlo) y el paint() solo lee el array cacheado.
    public static String[] wrapAll(String value, IrcWidth font, int width) {
        if (value == null) {
            value = "";
        }
        int n = wrapCount(value, font, width);
        String[] out = new String[n];
        int i = 0;
        while (i < n) {
            String line = wrapLine(value, font, width, i);
            out[i] = line == null ? "" : line;
            i++;
        }
        return out;
    }

    // Version con Font real para el precalculo en IrcMessage.
    public static String[] wrapAll(String value, Font font, int width) {
        return wrapAll(value, new FontMeasure(font), width);
    }

    // Ancho de un tramo con dos metricas: [start, prefixLen) en negrita y
    // el resto en normal. Solo enteros, sin copias ni objetos.
    private static int mixedWidth(String value, int prefixLen, IrcWidth bold, IrcWidth plain,
        int start, int length) {
        if (length <= 0) {
            return 0;
        }
        int end = start + length;
        int w = 0;
        if (start < prefixLen) {
            int boldEnd = end < prefixLen ? end : prefixLen;
            w += bold.substringWidth(value, start, boldEnd - start);
        }
        if (end > prefixLen) {
            int plainStart = start > prefixLen ? start : prefixLen;
            w += plain.substringWidth(value, plainStart, end - plainStart);
        }
        return w;
    }

    // Fin de linea con la misma regla que wrapLine (rellenar, preferir
    // corte en espacio, avanzar 1 si nada cabe), medida en mixto.
    private static int mixedEnd(String value, int prefixLen, IrcWidth bold, IrcWidth plain,
        int width, int start) {
        int length = value.length();
        int end = start;
        while (end < length && mixedWidth(value, prefixLen, bold, plain, start, end - start + 1)
            <= width) {
            end++;
        }
        if (end == start) {
            end = start + 1;
        }
        if (end < length) {
            int space = value.lastIndexOf(' ', end - 1);
            if (space > start) {
                end = space;
            }
        }
        return end;
    }

    // Troceado mixto para "nick: mensaje" con nick en negrita: el prefijo
    // se mide en negrita y el mensaje en normal, asi no se desperdicia el
    // ancho del texto normal. Mismo contrato que wrapAll.
    public static String[] wrapChatMixed(String value, int prefixLen, Font boldFont, Font plainFont,
        int width) {
        if (value == null) {
            value = "";
        }
        IrcWidth bold = new FontMeasure(boldFont);
        IrcWidth plain = new FontMeasure(plainFont);
        int length = value.length();
        int n = 0;
        int start = 0;
        while (start < length) {
            start = mixedEnd(value, prefixLen, bold, plain, width, start);
            while (start < length && value.charAt(start) == ' ') {
                start++;
            }
            n++;
        }
        if (n == 0) {
            n = 1;
        }
        String[] out = new String[n];
        start = 0;
        int i = 0;
        while (i < n) {
            if (start >= length) {
                out[i] = "";
            } else {
                int end = mixedEnd(value, prefixLen, bold, plain, width, start);
                if (end > length) {
                    end = length;
                }
                out[i] = value.substring(start, end);
                start = end;
                while (start < length && value.charAt(start) == ' ') {
                    start++;
                }
            }
            i++;
        }
        return out;
    }

    private static final class FontMeasure implements IrcWidth {
        private final Font font;

        FontMeasure(Font value) {
            font = value;
        }

        public int stringWidth(String value) {
            return font.stringWidth(value);
        }

        public int substringWidth(String value, int offset, int length) {
            return font.substringWidth(value, offset, length);
        }
    }

    // Barra de scroll proporcional compartida: sin objetos, solo int
    // (apta para KVM). Si todo cabe no dibuja nada.
    static void drawScrollBar(Graphics graphics, int top, int bottom, int x,
        int visible, int total, int pos, int trackColor, int thumbColor) {
        int span = bottom - top;
        if (span <= 0 || visible <= 0 || total <= 0) {
            return;
        }
        int maxPos = total - visible;
        if (maxPos <= 0) {
            return;
        }
        if (pos < 0) {
            pos = 0;
        }
        if (pos > maxPos) {
            pos = maxPos;
        }
        int thumb = (span * visible) / total;
        if (thumb < 3) {
            thumb = 3;
        }
        if (thumb > span) {
            thumb = span;
        }
        int travel = span - thumb;
        int fromTop = (travel * pos) / maxPos;
        graphics.setColor(trackColor);
        graphics.drawLine(x, top, x, bottom - 1);
        graphics.setColor(thumbColor);
        graphics.drawLine(x, top + fromTop, x, top + fromTop + thumb - 1);
    }

    static int clampScrollFirst(int selected, int first, int visible, int count) {
        if (selected >= count) {
            selected = count - 1;
        }
        if (selected < first) {
            first = selected;
        }
        if (selected >= first + visible) {
            first = selected - visible + 1;
        }
        if (first + visible > count) {
            first = count - visible;
        }
        if (first < 0) {
            first = 0;
        }
        return first;
    }
}