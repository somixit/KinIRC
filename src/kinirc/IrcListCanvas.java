package kinirc;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

// Base de las pantallas de lista (canales, nicks, baneos, ignorados,
// ventanas, ayuda): estado de seleccion, scroll con repeticion acelerada,
// teclas comunes y plantilla de pintado con ganchos. Ventanas y ayuda
// conservan su paint() propio (marquesina y fondo negro); el resto usa
// el de aqui. Sin objetos en caliente: un Timer por instancia, muerto
// en hideNotify.
public abstract class IrcListCanvas extends Canvas {
    protected static final int KEY_SOFT_LEFT = -6;
    protected static final int KEY_SOFT_RIGHT = -7;
    protected static final int KEY_BACK = -11;

    protected final IrcMidlet midlet;
    protected final IrcClient client;
    protected int selected;
    protected int first;

    private java.util.Timer repeatTimer;
    private java.util.TimerTask repeatTask;
    private int repeatDir;
    private int repeatMoves;
    private long repeatLast;

    protected IrcListCanvas(IrcMidlet midlet, IrcClient client) {
        this.midlet = midlet;
        this.client = client;
        setFullScreenMode(true);
    }

    // --- Ganchos de contenido (defaults: lista vacia) ---
    protected int rowCount() {
        return 0;
    }

    protected String headerText() {
        return "";
    }

    // Sufijo derecho de la barra superior que nunca se recorta (p. ej. el
    // numero de nicks). Se pinta pegado a la derecha con el margen de
    // pixels y la parte izquierda se acota para dejarle sitio, igual que
    // la barra superior del chat. Por defecto vacio: sin bloque derecho.
    protected String headerRightText() {
        return "";
    }

    protected String emptyText() {
        return IrcStrings.get(IrcStrings.LIST_EMPTY);
    }

    protected String rowText(int index) {
        return "";
    }

    protected int rowColor(int index, boolean isSelected) {
        if (isSelected) {
            return IrcDraw.STRIP_TEXT;
        }
        return IrcDraw.ROW_TEXT;
    }

    // Filas siempre en negrita (ventanas); el resto solo la seleccionada.
    protected boolean boldRows() {
        return false;
    }

    // Barra de scroll (ventanas no tiene).
    protected boolean scrollBar() {
        return true;
    }

    // Dibuja una fila; ventanas lo redefine para la marquesina.
    protected void drawRow(Graphics graphics, int index, int y, int width,
        Font font, Font boldFont, int lineHeight, boolean isSelected) {
        if (isSelected) {
            graphics.setColor(IrcDraw.ROW_SELECTED_BG);
            graphics.fillRect(1, y - 1, width - 2, lineHeight);
        }
        Font rowFont = (isSelected || boldRows()) ? boldFont : font;
        graphics.setFont(rowFont);
        graphics.setColor(rowColor(index, isSelected));
        graphics.drawString(IrcDraw.fitText(rowText(index), rowFont, width - 6), 3, y,
            Graphics.LEFT | Graphics.TOP);
    }

    // Pie: pista simple, o partida Volver/derecha si rightLabel no es vacio.
    protected String hintText() {
        return "";
    }

    protected String rightLabel() {
        return "";
    }

    // Extras al mostrar/ocultar (attach, limpiezas, recargas).
    protected void onShown() {
    }

    protected void onHidden() {
    }

    // Resto de teclas (no flechas); cada pantalla lo redefine. Por
    // defecto, atras vuelve al canvas (todas menos ayuda, que cierra).
    protected void onKey(int keyCode, int action) {
        if (keyCode == KEY_POUND || keyCode == KEY_BACK) {
            midlet.showCanvas();
        }
    }

    protected final void hideNotify() {
        stopRepeatTimer();
        onHidden();
        client.noteHidden(Display.getDisplay(midlet).getCurrent() == this);
    }

    protected final void showNotify() {
        onShown();
        client.noteShown();
        client.restoreFromPause();
        repaint();
    }

    // Un paso de scroll con clamp; la ayuda lo redefine con otra semantica.
    protected void moveDir(int dir) {
        int count = rowCount();
        if (count <= 0) {
            return;
        }
        if (dir < 0) {
            if (selected > 0) {
                selected--;
            }
        } else {
            if (selected + 1 < count) {
                selected++;
            }
        }
        repaint();
    }

    protected void paint(Graphics graphics) {
        client.processPendingEvents();
        int width = getWidth();
        int height = getHeight();
        graphics.setColor(client.getBgColor());
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(IrcDraw.MODAL_STRIP_BG);
        graphics.fillRect(0, 0, width, IrcDraw.HEADER_H);
        Font headerFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        graphics.setFont(headerFont);
        graphics.setColor(IrcDraw.STRIP_TEXT);
        String headerRight = headerRightText();
        if (headerRight == null) {
            headerRight = "";
        }
        int barMargin = 0;
        try {
            barMargin = client.getBarMargin();
        } catch (Exception e) {
            barMargin = 0;
        }
        if (barMargin < 0) {
            barMargin = 0;
        }
        if (headerRight.length() == 0) {
            graphics.drawString(IrcDraw.fitText(headerText(), headerFont, width - 4 - barMargin),
                2, 2, Graphics.LEFT | Graphics.TOP);
        } else {
            int maxRight = width - 4 - barMargin;
            if (maxRight < 0) {
                maxRight = 0;
            }
            if (headerFont.stringWidth(headerRight) > maxRight) {
                headerRight = IrcDraw.fitText(headerRight, headerFont, maxRight);
            }
            int rightWidth = headerFont.stringWidth(headerRight);
            int rightX = width - 2 - barMargin - rightWidth;
            if (rightX < 2) {
                rightX = 2;
            }
            int leftWidth = rightX - 2 - 2;
            if (leftWidth < 0) {
                leftWidth = 0;
            }
            graphics.drawString(IrcDraw.fitText(headerText(), headerFont, leftWidth), 2, 2,
                Graphics.LEFT | Graphics.TOP);
            graphics.drawString(headerRight, rightX, 2, Graphics.LEFT | Graphics.TOP);
        }
        int top = IrcDraw.HEADER_TOP;
        Font hintFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        int softBar = hintFont.getHeight() + 4;
        int bottom = height - softBar;
        Font font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, client.getFontSizeConstant());
        Font boldFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, client.getFontSizeConstant());
        int lineHeight = font.getHeight() + (client.isCompactLeading() ? 0 : 1);
        int visible = Math.max(1, (bottom - top) / lineHeight);
        int count = rowCount();
        if (count == 0) {
            graphics.setFont(font);
            graphics.setColor(IrcDraw.EMPTY_TEXT);
            graphics.drawString(emptyText(), 3, top, Graphics.LEFT | Graphics.TOP);
        } else {
            if (selected < 0) {
                selected = 0;
            }
            first = IrcDraw.clampScrollFirst(selected, first, visible, count);
            if (selected >= count) {
                selected = count - 1;
            }
            int y = top;
            int i = first;
            while (i < count && y < bottom) {
                drawRow(graphics, i, y, width, font, boldFont, lineHeight, i == selected);
                y += lineHeight;
                i++;
            }
            if (client.getScrollBar() && scrollBar()) {
                IrcDraw.drawScrollBar(graphics, top, bottom, width - 1, visible, count, first,
                    IrcDraw.SCROLL_TRACK, IrcDraw.STRIP_POS);
            }
        }
        String right = rightLabel();
        graphics.setColor(IrcDraw.MODAL_STRIP_BG);
        graphics.fillRect(0, height - softBar, width, softBar);
        graphics.setFont(hintFont);
        if (right.length() == 0) {
            graphics.setColor(IrcDraw.STRIP_POS);
            graphics.drawString(IrcDraw.fitText(hintText(), hintFont, width - 4), 2,
                height - softBar + 2, Graphics.LEFT | Graphics.TOP);
        } else {
            graphics.setColor(IrcDraw.STRIP_TEXT);
            graphics.drawString(IrcDraw.fitText(IrcStrings.get(IrcStrings.CMD_BACK), hintFont,
                width / 2 - 6), 3, height - softBar + 2, Graphics.LEFT | Graphics.TOP);
            String fitted = IrcDraw.fitText(right, hintFont, width / 2 - 6);
            graphics.drawString(fitted, width - 3 - hintFont.stringWidth(fitted),
                height - softBar + 2, Graphics.LEFT | Graphics.TOP);
        }
    }

    protected void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == UP) {
            moveDir(-1);
            startRepeat(-1);
        } else if (action == DOWN) {
            moveDir(1);
            startRepeat(1);
        } else {
            onKey(keyCode, action);
        }
    }

    protected void keyReleased(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == UP || action == DOWN) {
            stopRepeat();
        }
    }

    private synchronized void startRepeat(int dir) {
        repeatDir = dir;
        repeatMoves = 0;
        repeatLast = System.currentTimeMillis();
        if (repeatTask != null) {
            repeatTask.cancel();
            repeatTask = null;
        }
        if (repeatTimer == null) {
            try {
                repeatTimer = new java.util.Timer();
            } catch (Exception e) {
                return;
            }
        }
        try {
            repeatTask = new RepeatTask();
            repeatTimer.scheduleAtFixedRate(repeatTask, 400, IrcDraw.REPEAT_TICK_MS);
        } catch (Exception e) {
            repeatTask = null;
        }
    }

    private synchronized void stopRepeat() {
        if (repeatTask != null) {
            repeatTask.cancel();
            repeatTask = null;
        }
        repeatDir = 0;
        repeatMoves = 0;
    }

    protected void stopRepeatTimer() {
        stopRepeat();
        if (repeatTimer != null) {
            repeatTimer.cancel();
            repeatTimer = null;
        }
    }

    private synchronized void repeatTick() {
        if (repeatDir == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - repeatLast >= IrcDraw.repeatPeriod(repeatMoves)) {
            repeatLast = now;
            repeatMoves++;
            moveDir(repeatDir);
        }
    }

    private final class RepeatTask extends java.util.TimerTask {
        public void run() {
            try {
                repeatTick();
            } catch (Exception e) {
                // Nunca matar el hilo del Timer por una excepcion.
            }
        }
    }
}
