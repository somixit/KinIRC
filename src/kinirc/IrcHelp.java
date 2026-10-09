package kinirc;

import java.util.Vector;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

// Ayuda como las demas pantallas de texto (IrcChanList): canvas propio
// con fondo negro y texto blanco. El texto vive en /lang/help_<es|en>.txt
// y se carga al abrir (no reside en RAM el resto del tiempo).
public final class IrcHelp extends IrcListCanvas {
    private static final int COLOR_BG = 0x000000;
    private static final int COLOR_TEXT = 0xFFFFFF;

    private int first;
    // Lineas ajustadas compartidas: el texto es fijo y solo depende del
    // ancho, asi que se calculan una vez y se reutilizan en cada apertura.
    private static Vector sharedLines;
    private static Vector sharedTitle;
    private static int sharedWidth = -1;
    private static int sharedLang = -1;
    private static boolean prewarmed;

    public IrcHelp(IrcMidlet midlet) {
        super(midlet, midlet.getClient());
    }

    // Precalcula en segundo plano al arrancar para que la primera
    // apertura tambien sea instantanea. Una sola vez.
    static synchronized void prewarm(int width) {
        if (prewarmed || width <= 0) {
            return;
        }
        prewarmed = true;
        buildLines(width);
    }

    static synchronized boolean needsPrewarm() {
        return !prewarmed;
    }

    protected void sizeChanged(int width, int height) {
        first = 0;
        repaint();
    }

    protected void onHidden() {
        // La ayuda se mira una vez y casi no se reabre: se liberan los
        // ~10 KB de la cache al salir. Al reabrir se reajusta al vuelo.
        freeLines();
    }

    private static synchronized void freeLines() {
        sharedLines = null;
        sharedTitle = null;
        sharedWidth = -1;
        sharedLang = -1;
    }

    private void ensureLines(int width) {
        buildLines(width);
        if (sharedLines != null && first >= sharedLines.size()) {
            first = 0;
        }
    }

    private static synchronized void buildLines(int width) {
        int lang = IrcStrings.getLanguage();
        if (sharedLines != null && sharedWidth == width && sharedLang == lang) {
            return;
        }
        if (width <= 0) {
            return;
        }
        // Texto del fichero de ayuda del idioma activo; si falta, una
        // seccion minima en vez de pantalla vacia.
        String[][] sections = IrcStrings.readHelp();
        if (sections == null) {
            sections = new String[][] { { "", IrcStrings.get(IrcStrings.LIST_EMPTY) } };
        }
        Font font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        int usable = width - 6;
        if (usable < 20) {
            usable = 20;
        }
        Vector builtLines = new Vector();
        Vector builtTitle = new Vector();
        int s = 0;
        while (s < sections.length) {
            builtLines.addElement(sections[s][0]);
            builtTitle.addElement(new Integer(1));
            builtLines.addElement("");
            builtTitle.addElement(new Integer(0));
            appendWrapped(builtLines, builtTitle, sections[s][1], font, usable);
            builtLines.addElement("");
            builtTitle.addElement(new Integer(0));
            s++;
        }
        sharedLines = builtLines;
        sharedTitle = builtTitle;
        sharedWidth = width;
        sharedLang = lang;
    }

    private static void appendWrapped(Vector outLines, Vector outTitle, String text, Font font,
        int width) {
        int start = 0;
        while (start <= text.length()) {
            int cut = text.indexOf('\n', start);
            if (cut < 0) {
                wrapParagraph(outLines, outTitle, text.substring(start), font, width);
                return;
            }
            wrapParagraph(outLines, outTitle, text.substring(start, cut), font, width);
            start = cut + 1;
        }
    }

    private static void wrapParagraph(Vector outLines, Vector outTitle, String paragraph,
        Font font, int width) {
        String rest = IrcText.trim(paragraph);
        if (rest.length() == 0) {
            outLines.addElement("");
            outTitle.addElement(new Integer(0));
            return;
        }
        while (rest.length() > 0) {
            int totalWidth = font.stringWidth(rest);
            if (totalWidth <= width) {
                outLines.addElement(rest);
                outTitle.addElement(new Integer(0));
                return;
            }
            // Estimacion por regla de tres en vez de recortar caracter a
            // caracter (pasa de miles de mediciones a unas pocas).
            int take = (width * rest.length()) / totalWidth;
            if (take < 1) {
                take = 1;
            }
            if (take > rest.length()) {
                take = rest.length();
            }
            while (take > 1 && font.stringWidth(rest.substring(0, take)) > width) {
                take--;
            }
            while (take < rest.length()
                && font.stringWidth(rest.substring(0, take + 1)) <= width) {
                take++;
            }
            int space = -1;
            int i = 0;
            while (i < take) {
                if (rest.charAt(i) == ' ') {
                    space = i;
                }
                i++;
            }
            if (space > 0) {
                take = space;
            }
            outLines.addElement(rest.substring(0, take));
            outTitle.addElement(new Integer(0));
            rest = IrcText.trim(rest.substring(take));
        }
    }

    protected void paint(Graphics graphics) {
        int width = getWidth();
        int height = getHeight();
        ensureLines(width);
        graphics.setColor(COLOR_BG);
        graphics.fillRect(0, 0, width, height);
        if (sharedLines == null) {
            return;
        }
        Font plain = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        Font bold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        int lineHeight = plain.getHeight() + 1;
        int bottom = height - 20;
        int visible = Math.max(1, (bottom - 2) / lineHeight);
        int count = sharedLines.size();
        int maxFirst = count - visible;
        if (maxFirst < 0) {
            maxFirst = 0;
        }
        if (first > maxFirst) {
            first = maxFirst;
        }
        if (first < 0) {
            first = 0;
        }
        int y = 2;
        int i = first;
        while (i < count && y < bottom) {
            boolean title = false;
            if (sharedTitle != null && i < sharedTitle.size()) {
                title = ((Integer) sharedTitle.elementAt(i)).intValue() == 1;
            }
            graphics.setFont(title ? bold : plain);
            graphics.setColor(COLOR_TEXT);
            graphics.drawString((String) sharedLines.elementAt(i), 3, y,
                Graphics.LEFT | Graphics.TOP);
            y += lineHeight;
            i++;
        }
        // Cursor blanco para contraste sobre el fondo negro.
        IrcDraw.drawScrollBar(graphics, 2, bottom, width - 1, visible, count, first,
            IrcDraw.SCROLL_TRACK, COLOR_TEXT);
        graphics.setColor(IrcDraw.MODAL_STRIP_BG);
        graphics.fillRect(0, height - 19, width, 19);
        graphics.setFont(plain);
        graphics.setColor(COLOR_TEXT);
        graphics.drawString(IrcStrings.get(IrcStrings.CMD_BACK), 3, height - 17,
            Graphics.LEFT | Graphics.TOP);
    }

    protected void onKey(int keyCode, int action) {
        stopRepeatTimer();
        midlet.closeHelp();
    }

    protected void moveDir(int dir) {
        first += dir;
        if (first < 0) {
            first = 0;
        }
        repaint();
    }
}
