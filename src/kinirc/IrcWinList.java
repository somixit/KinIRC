package kinirc;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import java.util.Vector;

public final class IrcWinList extends IrcListCanvas {
    private static final int COLOR_STATUS = 0xffff00;
    private static final int COLOR_CHANNEL = 0x00ff00;
    private static final int COLOR_CHANNEL_OUT = 0xff0000;
    private static final int COLOR_QUERY = 0xdddddd;

    // Marquesina de topic: cada TICK_MS avanza STEP_CHARS letras de golpe
    // (bloques legibles con pausa entre ellos) y GAP_CHARS son los espacios
    // en blanco al final antes de repetir. Solo late con la lista abierta.
    private static final int TICKER_TICK_MS = 700;
    private static final int TICKER_STEP_CHARS = 8;
    private static final int TICKER_GAP_CHARS = 3;
    private String tickerChannel = "";
    private String tickerText;
    private String tickerTopic;
    private String tickerModes;
    private int tickerOffset;
    private int tickerClock;
    private java.util.Timer tickerTimer;
    private java.util.TimerTask tickerTask;

    protected int rowCount() {
        return client.getOrderedWindows().size();
    }

    public IrcWinList(IrcMidlet midlet, IrcClient client) {
        super(midlet, client);
        client.attachWinList(this);
    }

    protected void onHidden() {
        stopTicker();
        tickerChannel = "";
        client.detachWinList(this);
    }

    protected String headerText() {
        return IrcStrings.get(IrcStrings.WIN_TITLE_PRE) + rowCount();
    }

    protected String rowText(int index) {
        IrcWindow window = windowAt(index);
        if (window == null) {
            return "";
        }
        String label = window.getName();
        if (window == client.getActiveWindow()) {
            label = label + " <-";
        }
        if (window.getUnread() > 0) {
            label = label + " (" + window.getUnread() + ")";
        }
        return label;
    }

    protected int rowColor(int index, boolean isSelected) {
        if (isSelected) {
            return IrcDraw.STRIP_TEXT;
        }
        IrcWindow window = windowAt(index);
        if (window == null) {
            return IrcDraw.ROW_TEXT;
        }
        if (window.isServer()) {
            return COLOR_STATUS;
        }
        if (window.isChannel()) {
            return window.isJoined() ? COLOR_CHANNEL : COLOR_CHANNEL_OUT;
        }
        return COLOR_QUERY;
    }

    protected boolean boldRows() {
        return true;
    }

    protected boolean scrollBar() {
        return false;
    }

    protected String rightLabel() {
        return IrcStrings.get(IrcStrings.WIN_VIEW_TOPIC);
    }

    protected void drawRow(Graphics graphics, int index, int y, int width,
        Font font, Font boldFont, int lineHeight, boolean isSelected) {
        IrcWindow window = windowAt(index);
        if (window != null && isTickerRow(window)) {
            if (isSelected) {
                graphics.setColor(IrcDraw.ROW_SELECTED_BG);
                graphics.fillRect(1, y - 1, width - 2, lineHeight);
            }
            graphics.setFont(boldFont);
            graphics.setColor(rowColor(index, isSelected));
            drawTickerRow(graphics, window, boldFont, y);
            return;
        }
        super.drawRow(graphics, index, y, width, font, boldFont, lineHeight, isSelected);
    }

    private IrcWindow windowAt(int index) {
        Vector ordered = client.getOrderedWindows();
        if (index < 0 || index >= ordered.size()) {
            return null;
        }
        return (IrcWindow) ordered.elementAt(index);
    }

    private boolean isTickerRow(IrcWindow window) {
        return tickerChannel.length() > 0 && window != null && window.isChannel()
            && IrcText.ircEquals(window.getName(), tickerChannel);
    }

    // Reconstruye el texto solo si topic o modos cambiaron, con cola de
    // espacios para que el reinicio no sea brusco.
    private void refreshTickerText(String channel) {
        String topic = client.getTopicOf(channel);
        String modes = client.getModesOf(channel);
        if (tickerText != null && topic.equals(tickerTopic) && modes.equals(tickerModes)) {
            return;
        }
        tickerTopic = topic;
        tickerModes = modes;
        // Modos al final: al completarse solo crece la cola y el
        // principio (lo que se esta leyendo) no se mueve ni un caracter.
        String built;
        if (topic.length() == 0) {
            built = IrcStrings.get(IrcStrings.NO_TOPIC);
        } else if (modes.length() > 0) {
            built = IrcStrings.get(IrcStrings.TOPIC_PRE) + topic + " [" + modes + "]";
        } else {
            built = IrcStrings.get(IrcStrings.TOPIC_PRE) + topic;
        }
        StringBuffer withGap = new StringBuffer(built.length() + TICKER_GAP_CHARS);
        withGap.append(built);
        int g = 0;
        while (g < TICKER_GAP_CHARS) {
            withGap.append(' ');
            g++;
        }
        tickerText = withGap.toString();
        // Sin resetear el offset: al completarse con los modos la cinta
        // continua en vez de reiniciar de golpe (el toggle si parte de 0).
    }

    private void drawTickerRow(Graphics graphics, IrcWindow window, Font font, int y) {
        refreshTickerText(window.getName());
        if (tickerText == null || tickerText.length() == 0) {
            return;
        }
        if (tickerOffset >= tickerText.length()) {
            tickerOffset = tickerOffset % tickerText.length();
        }
        graphics.setFont(font);
        // Unico objeto por frame; el canvas recorta a la derecha solo.
        graphics.drawString(tickerText.substring(tickerOffset), 3, y,
            Graphics.LEFT | Graphics.TOP);
    }

    protected void onKey(int keyCode, int action) {
        if (action == FIRE || keyCode == KEY_STAR) {
            switchTo(selected);
        } else if (keyCode == KEY_SOFT_RIGHT) {
            toggleTicker();
        } else if (keyCode == KEY_SOFT_LEFT) {
            midlet.showCanvas();
        } else {
            super.onKey(keyCode, action);
        }
    }

    private void switchTo(int index) {
        Vector ordered = client.getOrderedWindows();
        if (index >= 0 && index < ordered.size()) {
            client.setActiveWindow((IrcWindow) ordered.elementAt(index));
        }
        midlet.showCanvas();
    }

    // Marquesina de topic en la fila: la pide fresca al servidor (MODE y
    // TOPIC responden 324/332 y la cinta se completa en vivo) y desplaza
    // 2 px cada 150 ms. Segunda pulsacion la apaga.
    private void toggleTicker() {
        Vector ordered = client.getOrderedWindows();
        if (selected < 0 || selected >= ordered.size()) {
            return;
        }
        IrcWindow window = (IrcWindow) ordered.elementAt(selected);
        if (window == null || !window.isChannel()) {
            return;
        }
        String name = window.getName();
        if (tickerChannel.length() > 0 && IrcText.ircEquals(tickerChannel, name)) {
            stopTicker();
            tickerChannel = "";
            repaint();
            return;
        }
        stopTicker();
        tickerChannel = name;
        tickerText = null;
        tickerOffset = 0;
        tickerClock = 0;
        client.requestTopicModes(name);
        startTicker();
        repaint();
    }

    private synchronized void startTicker() {
        stopTicker();
        if (tickerTimer == null) {
            try {
                tickerTimer = new java.util.Timer();
            } catch (Exception e) {
                return;
            }
        }
        try {
            tickerTask = new TickerTask();
            tickerTimer.scheduleAtFixedRate(tickerTask, TICKER_TICK_MS, TICKER_TICK_MS);
        } catch (Exception e) {
            tickerTask = null;
        }
    }

    // Como el resto de timers de un solo uso: se mata para no dejar su
    // hilo ocioso para siempre.
    private synchronized void stopTicker() {
        if (tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }
        if (tickerTimer != null) {
            tickerTimer.cancel();
            tickerTimer = null;
        }
    }

    private synchronized void tickerTick() {
        tickerOffset += TICKER_STEP_CHARS;
        tickerClock++;
        if (tickerClock >= 20) {
            // Cada ~12 s se comprueba que el canal siga existiendo (pudo
            // cerrarse con la cinta puesta); si no, se apaga sola.
            tickerClock = 0;
            if (!channelShown(tickerChannel)) {
                stopTicker();
                tickerChannel = "";
            }
        }
        repaint();
    }

    private boolean channelShown(String name) {
        if (name.length() == 0) {
            return false;
        }
        Vector ordered = client.getOrderedWindows();
        int i = 0;
        while (i < ordered.size()) {
            IrcWindow window = (IrcWindow) ordered.elementAt(i);
            if (window != null && window.isChannel() && IrcText.ircEquals(window.getName(), name)) {
                return true;
            }
            i++;
        }
        return false;
    }

    private final class TickerTask extends java.util.TimerTask {
        public void run() {
            try {
                tickerTick();
            } catch (Exception e) {
                // Nunca matar el hilo del Timer por una excepcion.
            }
        }
    }
}
