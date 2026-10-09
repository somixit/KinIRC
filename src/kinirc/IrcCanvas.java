package kinirc;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import java.util.Timer;
import java.util.TimerTask;

public final class IrcCanvas extends Canvas {
    // Teclas blandas Sony Ericsson en Canvas a pantalla completa.
    private static final int KEY_SOFT_LEFT = -6;
    private static final int KEY_SOFT_RIGHT = -7;
    private static final int KEY_BACK = -11;

    private static final int ACT_NICKS = 0;
    private static final int ACT_ACCIONES = 1;
    private static final int ACT_JOIN = 2;
    private static final int ACT_WINDOWS = 3;
    private static final int ACT_CLEAR = 4;
    private static final int ACT_CHANLIST = 5;
    private static final int ACT_CHANCTL = 6;
    private static final int ACT_INTERFACE = 7;
    private static final int ACT_EXIT = 10;
    private static final int ACT_CONNECT = 11;
    private static final int ACT_CLOSE = 12;
    private static final int ACT_WHOIS = 13;
    private static final int ACT_IGNORE = 14;
    private static final int ACT_MINIMIZE = 15;
    private static final int ACT_IGNORES = 16;

    private static final int BLINK_MS = 500;
    // Tras este tiempo parpadeando se deja el aviso fijo (subrayado) hasta
    // que se lea Status: sin mas Timer ni repintados, ahorra CPU/bateria.
    private static final long BLINK_MAX_MS = 30000;

    private final IrcMidlet midlet;
    private final IrcClient client;
    private final Display display;

    // Modo mencion: FIRE lo arma en un canal y cada click arriba/abajo
    // salta al mensaje anterior/siguiente con autor, pintando su nick en
    // negativo (blanco sobre azul). Izquierda o FIRE abren el editor con
    // "nick " puesto (una linea para pasarlo a "nick: " si se prefiere).
    private boolean mentionMode;
    private IrcMessage mentionMessage;
    private int lastTotalLines;
    // Ancla de lectura: ventana, mensaje y scroll de la ultima vez que se
    // pinto con scroll > 0. Mientras no se toque el scroll, la vista se
    // clava a ese mensaje aunque entre trafico (como el foco de nicks).
    private IrcWindow anchorWindow;
    private IrcMessage anchorMessage;
    private int anchorIntra;
    private int anchorScroll;

    // Gancho preparado para pintar el fondo de otro color en modo
    // seleccion. Apagado por ahora (false): no cambia nada visible.
    private static final int MENTION_BG = 0x1c2a1c;
    private static final boolean MENTION_BG_ON = false;

    public IrcCanvas(IrcMidlet midlet, IrcClient client) {
        this.midlet = midlet;
        this.client = client;
        display = Display.getDisplay(midlet);
        setFullScreenMode(true);
    }

    public IrcClient getClient() {
        return client;
    }

    protected void paint(Graphics graphics) {
        // Red de seguridad: en la KVM una excepcion que salga de paint
        // mata el MIDlet o deja la pantalla en blanco.
        try {
            client.processPendingEvents();
            updateStatusBlink();
            int width = getWidth();
            int height = getHeight();
            if (MENTION_BG_ON && mentionMode) {
                graphics.setColor(MENTION_BG);
            } else {
                graphics.setColor(client.getBgColor());
            }
            graphics.fillRect(0, 0, width, height);
            int strip = drawStrip(graphics, width);
            int soft = drawSoftBar(graphics, width, height);
            IrcWindow window = client.getActiveWindow();
            drawWindow(graphics, window, width, strip + 1, height - soft);
        } catch (Throwable fatal) {
            // Nunca dejar que paint propague.
        }
    }

    protected void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == UP) {
            if (mentionMode) {
                mentionMove(-1);
                startRepeat(1);
            } else {
                scrollUp();
                startRepeat(1);
            }
        } else if (action == DOWN) {
            if (mentionMode) {
                mentionMove(1);
                startRepeat(-1);
            } else {
                scrollDown();
                startRepeat(-1);
            }
        } else if (action == LEFT) {
            exitMentionMode();
            client.previousWindow();
        } else if (action == RIGHT) {
            exitMentionMode();
            client.nextWindow();
        } else if (action == FIRE || keyCode == KEY_SOFT_LEFT) {
            if (mentionMode) {
                confirmMention();
            } else {
                IrcWindow active = client.getActiveWindow();
                if (active != null && active.isChannel() && !active.isJoined()) {
                    client.requestJoin(active.getName());
                } else if (action == FIRE) {
                    startMentionMode();
                } else {
                    openInput();
                }
            }
        } else if (keyCode == KEY_SOFT_RIGHT) {
            exitMentionMode();
            openMenu();
        } else if (keyCode == KEY_STAR) {
            exitMentionMode();
            client.clearActive();
        } else if (keyCode == KEY_POUND) {
            if (mentionMode) {
                exitMentionMode();
            } else {
                client.closeWindow(client.getActiveWindow());
            }
        } else if (keyCode == KEY_BACK) {
            if (mentionMode) {
                // Primera pulsacion: solo sale del modo, manteniendo el
                // scroll donde estabas leyendo.
                exitMentionMode();
            } else {
                // Segunda pulsacion (modo normal): baja al fondo.
                IrcWindow here = client.getActiveWindow();
                if (here != null) {
                    here.setScroll(0);
                    repaint();
                }
            }
        } else if (keyCode == KEY_NUM0) {
            openWinList();
        } else if (keyCode == KEY_NUM1) {
            exitMentionMode();
            client.showStatus();
        } else if (keyCode == KEY_NUM3) {
            // Pulsacion larga: solo minimiza si se mantiene 200 ms.
            startMinimizeHold();
        } else if (keyCode == KEY_NUM7) {
            openMemory();
        } else if (keyCode == KEY_NUM5) {
            openMenu();
        }
    }

    protected void keyReleased(int keyCode) {
        if (keyCode == KEY_NUM3) {
            // Click corto: se cancela, no minimiza.
            cancelMinimizeHold();
        }
        int action = getGameAction(keyCode);
        if (action == UP || action == DOWN) {
            stopRepeat();
        }
    }

    public void playAlert() {
        midlet.playAlert();
    }

    public void showCanvas() {
        display.setCurrent(this);
        client.attachCanvas(this);
        repaint();
    }

    private volatile Timer statusTimer;
    private final Object blinkLock = new Object();
    private volatile boolean statusBlinkOn;
    private volatile long blinkStart;
    private volatile boolean blinkFrozen;

    // No crea Timer en cada paint sin control: un solo Timer bajo
    // blinkLock, volatile para verlo desde hideNotify/destroyApp y el
    // hilo del Timer. Se para cuando Status se lee o se oculta.
    private void updateStatusBlink() {
        IrcWindow status = client.getServerWindow();
        boolean needBlink = status != null && status.getUnread() > 0;
        if (!needBlink) {
            stopStatusBlink();
            return;
        }
        if (blinkFrozen) {
            // Congelado tras BLINK_MAX_MS: aviso fijo, sin recrear el Timer.
            statusBlinkOn = true;
            return;
        }
        synchronized (blinkLock) {
            if (statusTimer == null && !blinkFrozen) {
                blinkStart = System.currentTimeMillis();
                Timer created = new Timer();
                try {
                    created.scheduleAtFixedRate(new StatusBlinkTask(), 0, BLINK_MS);
                } catch (Exception e) {
                    return;
                }
                statusTimer = created;
            }
        }
    }

    // Detiene el parpadeo de Status: se usa al ocultar la pantalla y al salir.
    // Tambien limpia el congelado para que el proximo aviso parpadee de nuevo.
    public void stopStatusBlink() {
        synchronized (blinkLock) {
            if (statusTimer != null) {
                statusTimer.cancel();
                statusTimer = null;
            }
        }
        statusBlinkOn = false;
        blinkFrozen = false;
        blinkStart = 0;
    }

    // Al ocultarse el canvas no se toca la red de inmediato: los
    // overlays del sistema (p. ej. el menu de volumen, que mantiene este
    // canvas como current) dispararian falsos segundo plano con PART/JOIN.
    // El diferido de segundo plano vive en el cliente (comun a las 6
    // pantallas). Solo se programa si el Display sigue mostrando este
    // canvas (los menus internos tienen otro current y no programan nada).
    // La via explicita (tecla 3, menu "Minimizar") y MIDlet.pauseApp()
    // siguen siendo inmediatas.
    protected void hideNotify() {
        client.setCanvasVisible(false);
        stopRepeat();
        exitMentionMode();
        cancelMinimizeHold();
        cancelClockTick();
        if (repeatTimer != null) {
            repeatTimer.cancel();
            repeatTimer = null;
        }
        stopStatusBlink();
        client.noteHidden(display.getCurrent() == this);
    }

    protected void showNotify() {
        client.setCanvasVisible(true);
        statusBlinkOn = false;
        // Si el canvas reaparece antes del diferido (p. ej. menu de
        // volumen), se cancela y la red no se toca.
        client.noteShown();
        prewarmHelp();
        // Idempotente: solo reentra con JOIN si hubo un minimizado real
        // con PART previos; tras un simple menu no hace nada visible.
        client.restoreFromPause();
        repaint();
    }

    // Precalcula el ajuste de la ayuda en segundo plano una sola vez para
    // que su primera apertura sea instantanea. Sin guarda se lanzaba un
    // hilo nuevo en cada show aunque ya no hubiera nada que hacer.
    private void prewarmHelp() {
        final int width = getWidth();
        if (width <= 0 || !IrcHelp.needsPrewarm()) {
            return;
        }
        try {
            Thread warm = new Thread(new Runnable() {
                public void run() {
                    IrcHelp.prewarm(width);
                }
            });
            warm.start();
        } catch (Exception e) {
            // Sin hilos: la ayuda se ajustara al abrirse.
        }
    }

    private final class StatusBlinkTask extends TimerTask {
        public void run() {
            synchronized (blinkLock) {
                if (statusTimer == null) {
                    return;
                }
                if (System.currentTimeMillis() - blinkStart > BLINK_MAX_MS) {
                    // Fin del parpadeo: aviso fijo y Timer muerto (0 Hz).
                    statusTimer.cancel();
                    statusTimer = null;
                    statusBlinkOn = true;
                    blinkFrozen = true;
                    repaint();
                    return;
                }
            }
            statusBlinkOn = !statusBlinkOn;
            repaint();
        }
    }

    // Tira superior: punto, nombre, posicion y barras a la derecha.
    private int drawStrip(Graphics graphics, int width) {
        Font font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        int height = font.getHeight() + 4;
        graphics.setColor(IrcDraw.STRIP_BG);
        graphics.fillRect(0, 0, width, height);
        graphics.setFont(font);
        boolean connected = client.isConnected();
        graphics.setColor(connected ? IrcDraw.CONNECTED_DOT : IrcDraw.DISCONNECTED_DOT);
        graphics.fillArc(3, (height - 6) / 2, 6, 6, 0, 360);
        IrcWindow status = client.getServerWindow();
        if (status != null && status.getUnread() > 0 && statusBlinkOn) {
            graphics.setColor(IrcDraw.STRIP_TEXT);
            int y = (height - 6) / 2 + 7;
            graphics.drawLine(3, y, 9, y);
        }
        IrcWindow active = client.getActiveWindow();
        int count = client.getWindowCount();
        int index = client.getWindowIndex(active);
        if (index < 0) {
            index = 0;
        }
        String position = (index + 1) + "/" + count;
        int positionWidth = font.stringWidth(position);
        int barWidth = 4;
        int indicator = client.getWinIndicator();
        boolean showNumeral = indicator != IrcSettings.INDICATOR_BARS;
        boolean showBars = indicator != IrcSettings.INDICATOR_NUMERAL;
        int barsWidth = showBars ? count * barWidth : 0;
        // Bloque unico a la derecha: numeral y/o barras segun el indicador,
        // con el margen aplicado al bloque entero.
        int margin = client.getBarMargin();
        // Hueco numeral-barras solo si ambos se muestran; si solo hay
        // numeral no se aplica para que el margen quede exacto.
        int gap = (showNumeral && showBars) ? 3 : 0;
        int blockWidth = barsWidth + (showNumeral ? positionWidth + gap : 0);
        int rightWidth = margin + 2 + blockWidth;
        String name = active == null ? "IRC" : active.getName();
        if (active != null && active.getUnread() > 0) {
            name = name + " (" + active.getUnread() + ")";
        }
        int nameWidth = width - 13 - rightWidth;
        if (nameWidth < 8) {
            nameWidth = 8;
        }
        int titleColor = IrcDraw.STRIP_TEXT;
        if (active != null && active.isChannel()) {
            if (active.isClosing()) {
                titleColor = IrcDraw.CLOSING_TITLE;
            } else if (!active.isJoined()) {
                titleColor = IrcMessage.COLOR_ERROR;
            }
        }
        graphics.setColor(titleColor);
        graphics.drawString(IrcDraw.fitText(name, font, nameWidth), 12, 2, Graphics.LEFT | Graphics.TOP);
        graphics.setColor(IrcDraw.STRIP_POS);
        int blockRight = width - 2 - margin;
        int barX = blockRight - barsWidth;
        if (showNumeral) {
            int positionX = barX - gap - positionWidth;
            graphics.drawString(position, positionX, 2, Graphics.LEFT | Graphics.TOP);
        }
        int i = 0;
        while (showBars && i < count && barX + i * barWidth + 3 <= width) {
            IrcWindow window = client.getWindowAt(i);
            if (i == index) {
                graphics.setColor(IrcDraw.BAR_ACTIVE);
            } else if (window != null && window.getUnread() > 0) {
                graphics.setColor(IrcDraw.BAR_UNREAD);
            } else {
                graphics.setColor(IrcDraw.BAR_IDLE);
            }
            int barHeight = i == index ? height - 5 : height - 8;
            graphics.fillRect(barX + i * barWidth, height - 2 - barHeight, 3, barHeight);
            i++;
        }
        return height;
    }

    // Reloj cacheado HH:MM (solo se reconstruye al cambiar de minuto) y
    // timer de un disparo al siguiente minuto para que no se congele en
    // canales callados. Un despertar por minuto con el chat visible.
    private String clockText;
    private long clockMinute = -1;
    private java.util.Timer clockTimer;
    private java.util.TimerTask clockTask;

    private String clockNow() {
        long minute = System.currentTimeMillis() / 60000;
        if (clockText == null || minute != clockMinute) {
            clockMinute = minute;
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.setTime(new java.util.Date(System.currentTimeMillis()));
            int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
            int minuteOfHour = calendar.get(java.util.Calendar.MINUTE);
            StringBuffer text = new StringBuffer();
            if (hour < 10) {
                text.append('0');
            }
            text.append(hour);
            text.append(':');
            if (minuteOfHour < 10) {
                text.append('0');
            }
            text.append(minuteOfHour);
            clockText = text.toString();
        }
        return clockText;
    }

    private synchronized void scheduleClockTick() {
        if (clockTask != null) {
            return;
        }
        long delay = 60000 - (System.currentTimeMillis() % 60000) + 500;
        try {
            clockTimer = new java.util.Timer();
            clockTask = new ClockTask();
            clockTimer.schedule(clockTask, delay);
        } catch (Exception e) {
            clockTimer = null;
            clockTask = null;
        }
    }

    private synchronized void cancelClockTick() {
        if (clockTask != null) {
            clockTask.cancel();
            clockTask = null;
        }
        if (clockTimer != null) {
            clockTimer.cancel();
            clockTimer = null;
        }
    }

    private final class ClockTask extends java.util.TimerTask {
        public void run() {
            try {
                synchronized (IrcCanvas.this) {
                    clockTask = null;
                    if (clockTimer != null) {
                        clockTimer.cancel();
                        clockTimer = null;
                    }
                }
                repaint();
            } catch (Exception e) {
                // Nunca matar el hilo del Timer por una excepcion.
            }
        }
    }

    // Iconos de 12 px dibujados con primitivas (cero memoria): lapiz para
    // escribir y hamburguesa para menu. "Entrar" sigue siendo texto.
    private static final int ICON_SIZE = 12;

    private static void drawPencilIcon(Graphics graphics, int x, int y) {
        graphics.drawLine(x + 2, y + 8, x + 8, y + 2);
        graphics.drawLine(x + 3, y + 9, x + 9, y + 3);
        graphics.fillRect(x + 1, y + 9, 2, 2);
        graphics.fillRect(x + 8, y + 1, 3, 2);
    }

    private static void drawMenuIcon(Graphics graphics, int x, int y) {
        graphics.drawLine(x + 1, y + 2, x + 10, y + 2);
        graphics.drawLine(x + 1, y + 6, x + 10, y + 6);
        graphics.drawLine(x + 1, y + 10, x + 10, y + 10);
    }

    // Barra de botones siempre visible con las dos acciones directas y el
    // reloj centrado entre ambas (se omite si no cabe).
    private int drawSoftBar(Graphics graphics, int width, int height) {
        Font font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        int barHeight = font.getHeight() + 4;
        int y = height - barHeight;
        graphics.setColor(IrcDraw.STRIP_BG);
        graphics.fillRect(0, y, width, barHeight);
        graphics.setFont(font);
        graphics.setColor(IrcDraw.STRIP_TEXT);
        IrcWindow active = client.getActiveWindow();
        int iconY = y + (barHeight - ICON_SIZE) / 2;
        int leftEnd;
        if (active != null && active.isChannel() && !active.isJoined()) {
            String fittedLeft = IrcDraw.fitText(IrcStrings.get(IrcStrings.HINT_JOIN_CHAN), font, width / 2 - 6);
            graphics.drawString(fittedLeft, 3, y + 2, Graphics.LEFT | Graphics.TOP);
            leftEnd = 3 + font.stringWidth(fittedLeft) + 2;
        } else {
            drawPencilIcon(graphics, 3, iconY);
            leftEnd = 3 + ICON_SIZE + 2;
        }
        drawMenuIcon(graphics, width - 3 - ICON_SIZE, iconY);
        int rightStart = width - 3 - ICON_SIZE - 2;
        String clock = clockNow();
        int clockWidth = font.stringWidth(clock);
        if (rightStart - leftEnd >= clockWidth) {
            graphics.setColor(IrcDraw.STRIP_POS);
            graphics.drawString(clock, leftEnd + (rightStart - leftEnd - clockWidth) / 2,
                y + 2, Graphics.LEFT | Graphics.TOP);
        }
        scheduleClockTick();
        return barHeight;
    }

    private Font chatFont(int style) {
        return Font.getFont(Font.FACE_SYSTEM, style, client.getFontSizeConstant());
    }

    // En Status cada mensaje lleva guion + hora ("-" y "[HH:MM]") antes
    // del texto. Solo dibujo: no se guarda nada ni consume limite/unread.
    private void drawWindow(Graphics graphics, IrcWindow window, int width, int top, int bottom) {
        Font normal = chatFont(Font.STYLE_PLAIN);
        Font bold = chatFont(Font.STYLE_BOLD);
        int fontSize = client.getFontSizeConstant();
        int lineWidth = width - 4;
        int lineHeight = normal.getHeight() + (client.isCompactLeading() ? 0 : 1);
        int availableHeight = bottom - top;
        if (availableHeight < lineHeight || window == null) {
            return;
        }
        // Foto atomica: evita NPE/carreras si red llama a clear/setCapacity
        // mientras se pinta (antes size()+get(i) no atomico).
        IrcMessage[] snapshot = window.snapshot();
        int count = snapshot.length;
        if (count == 0) {
            graphics.setFont(normal);
            graphics.setColor(IrcDraw.EMPTY_TEXT);
            graphics.drawString(IrcStrings.get(IrcStrings.EMPTY_CHAT), 3, top, Graphics.LEFT | Graphics.TOP);
            return;
        }
        // Solo lecturas de cache (calculada al insertar): ni substringWidth
        // ni concatenaciones en el frame. El miss (cambio de letra/ancho)
        // se recalcula una sola vez dentro de getWrappedCount.
        int[] messageLines = new int[count];
        boolean stamps = window.isServer();
        int totalLines = 0;
        int i = 0;
        while (i < count) {
            IrcMessage current = snapshot[i];
            if (current == null || current.isSingleLine()) {
                messageLines[i] = 1;
            } else {
                messageLines[i] = current.getWrappedCount(lineWidth, fontSize, client.isNickBold());
            }
            totalLines += messageLines[i];
            if (stamps && current != null) {
                totalLines += 2;
            }
            i++;
        }
        int visibleLines = availableHeight / lineHeight;
        int maxScroll = totalLines - visibleLines;
        if (maxScroll < 0) {
            maxScroll = 0;
        }
        int firstLine;
        int scroll;
        if (mentionMode) {
            // Vista clavada al seleccionado: se busca su offset actual y
            // se pone primero visible (sin pasar del final). Asi ni el
            // trafico nuevo ni la rotacion del buffer lo mueven de
            // pantalla; si ya salio del buffer, se congela por delta.
            int pinned = mentionPinFirstLine(window, snapshot, lineWidth, fontSize);
            if (pinned >= 0) {
                firstLine = pinned;
                if (firstLine > maxScroll) {
                    firstLine = maxScroll;
                }
            } else {
                scroll = window.getScroll() + totalLines - lastTotalLines;
                if (scroll < 0) {
                    scroll = 0;
                }
                if (scroll > maxScroll) {
                    scroll = maxScroll;
                }
                firstLine = totalLines - visibleLines - scroll;
            }
            if (firstLine < 0) {
                firstLine = 0;
            }
            scroll = totalLines - visibleLines - firstLine;
            if (scroll < 0) {
                scroll = 0;
            }
            if (scroll > maxScroll) {
                scroll = maxScroll;
            }
            window.setScroll(scroll);
        } else {
            scroll = window.getScroll();
            if (scroll > maxScroll) {
                scroll = maxScroll;
                window.setScroll(scroll);
            }
            if (scroll > 0 && scroll == anchorScroll && anchorWindow == window
                && anchorMessage != null) {
                // Leyendo historial sin tocar el scroll: clavar la linea
                // exacta anclada (mensaje + desplazamiento interno) para
                // que el trafico nuevo no la desplace, aunque el mensaje
                // ocupe varias lineas.
                int acc = 0;
                int pinned = -1;
                int k = 0;
                while (k < count) {
                    if (snapshot[k] == anchorMessage) {
                        int block = messageLines[k];
                        if (stamps && snapshot[k] != null) {
                            block += 2;
                        }
                        if (block < 1) {
                            block = 1;
                        }
                        int intra = anchorIntra;
                        if (intra > block - 1) {
                            intra = block - 1;
                        }
                        if (intra < 0) {
                            intra = 0;
                        }
                        pinned = acc + intra;
                        break;
                    }
                    acc += messageLines[k];
                    if (stamps && snapshot[k] != null) {
                        acc += 2;
                    }
                    k++;
                }
                if (pinned >= 0) {
                    firstLine = pinned;
                    if (firstLine > maxScroll) {
                        firstLine = maxScroll;
                    }
                    if (firstLine < 0) {
                        firstLine = 0;
                    }
                    scroll = totalLines - visibleLines - firstLine;
                    if (scroll < 0) {
                        scroll = 0;
                    }
                    if (scroll > maxScroll) {
                        scroll = maxScroll;
                    }
                    window.setScroll(scroll);
                    anchorScroll = scroll;
                } else {
                    // El ancla salio del buffer (rotacion): ir directo al
                    // techo para mostrar la marca roja en vez de quedarse
                    // a medias con una vista congelada sin referencia.
                    scroll = maxScroll;
                    window.setScroll(scroll);
                    firstLine = 0;
                    anchorWindow = window;
                    anchorAtLine(snapshot, messageLines, stamps, count, firstLine);
                    anchorScroll = scroll;
                }
            } else {
                firstLine = totalLines - visibleLines - scroll;
                if (firstLine < 0) {
                    firstLine = 0;
                }
                if (scroll > 0) {
                    anchorWindow = window;
                    anchorAtLine(snapshot, messageLines, stamps, count, firstLine);
                    anchorScroll = scroll;
                } else {
                    anchorWindow = null;
                    anchorMessage = null;
                    anchorIntra = 0;
                    anchorScroll = 0;
                }
            }
        }
        lastTotalLines = totalLines;
        int line = 0;
        int y = top;
        // Tope del historico: si la primera linea visible es la mas vieja
        // del buffer (y hay scroll recorrido), se marca en rojo para no
        // seguir subiendo en vacio. Consume una fila, como una linea mas.
        if (maxScroll > 0 && firstLine == 0) {
            graphics.setColor(IrcDraw.HISTORY_TOP_MARK);
            graphics.drawLine(2, y + 1, width - 3, y + 1);
            graphics.drawLine(2, y + 2, width - 3, y + 2);
            y += lineHeight;
        }
        i = 0;
        while (i < count && y < bottom) {
            IrcMessage current = snapshot[i];
            if (stamps && current != null) {
                if (line >= firstLine && y < bottom) {
                    graphics.setFont(normal);
                    graphics.setColor(IrcDraw.EMPTY_TEXT);
                    graphics.drawString("-", 2, y, Graphics.LEFT | Graphics.TOP);
                    y += lineHeight;
                }
                line++;
                if (line >= firstLine && y < bottom) {
                    graphics.setFont(normal);
                    graphics.setColor(IrcDraw.EMPTY_TEXT);
                    graphics.drawString(current.getClockText(), 2, y, Graphics.LEFT | Graphics.TOP);
                    y += lineHeight;
                }
                line++;
            }
            IrcMessage message = snapshot[i];
            if (message == null) {
                line += messageLines[i];
                i++;
                continue;
            }
            int messageStart = line;
            int localLine = 0;
            while (localLine < messageLines[i] && y < bottom) {
                int currentLine = messageStart + localLine;
                if (currentLine >= firstLine) {
                    drawMessageLine(graphics, message, localLine, normal, bold, 2, y,
                        lineWidth, fontSize);
                    y += lineHeight;
                }
                localLine++;
            }
            line += messageLines[i];
            i++;
        }
        if (client.getScrollBar()) {
            IrcDraw.drawScrollBar(graphics, top, bottom, width - 1, visibleLines, totalLines,
                maxScroll - scroll, IrcDraw.SCROLL_TRACK, IrcDraw.STRIP_POS);
        }
    }

    // Nick en negativo (blanco sobre azul) cuando es el seleccionado en
    // modo mencion. Solo primera linea de CHAT; el resto sigue igual.
    private void drawNickPrefix(Graphics graphics, IrcMessage message, Font nickFont,
        String text, int x, int y) {
        if (mentionMode && message == mentionMessage) {
            int w = nickFont.stringWidth(text);
            graphics.setColor(IrcDraw.MENTION_NICK_BG);
            graphics.fillRect(x, y, w, nickFont.getHeight());
            graphics.setFont(nickFont);
            graphics.setColor(0xffffff);
        } else {
            graphics.setFont(nickFont);
            graphics.setColor(message.getNickColor());
        }
        graphics.drawString(text, x, y, Graphics.LEFT | Graphics.TOP);
    }

    // Trozo de nick a mitad de palabra (nick mas largo que la linea):
    // toda la linea son caracteres del nick.
    private static boolean isLongNickChunk(IrcMessage message, String line) {
        String nick = message.getNick();
        return nick.length() > 0 && line.length() < nick.length() && nick.startsWith(line);
    }

    private void drawNickAndRest(Graphics graphics, IrcMessage message, Font nickFont, Font normal,
        String prefix, String rest, int x, int y, int width, int restColor) {
        drawNickPrefix(graphics, message, nickFont, prefix, x, y);
        int restWidth = width - nickFont.stringWidth(prefix);
        if (restWidth <= 0) {
            rest = "";
        } else if (normal.stringWidth(rest) > restWidth) {
            rest = IrcDraw.fitText(rest, normal, restWidth);
        }
        graphics.setFont(normal);
        graphics.setColor(restColor);
        graphics.drawString(rest, x + nickFont.stringWidth(prefix), y,
            Graphics.LEFT | Graphics.TOP);
    }

    private void drawMessageLine(Graphics graphics, IrcMessage message, int lineIndex,
                                   Font normal, Font bold, int x, int y, int width, int fontSize) {
        if (message == null) {
            return;
        }
        String display = message.getDisplayText();
        boolean nickBold = client.isNickBold();
        int lineCount = message.getWrappedCount(width, fontSize, nickBold);
        if (lineCount == 0) {
            return;
        }
        String line = message.getWrappedLine(width, fontSize, nickBold, lineIndex);
        if (line == null) {
            return;
        }
        int color = message.getColor();
        graphics.setFont(normal);
        graphics.setColor(color);
        if (message.isMentioned()) {
            // Solo el texto sale naranja; el nick conserva su color.
            if (lineIndex == 0 && message.getType() == IrcMessage.CHAT) {
                String prefix = message.getNick() + ": ";
                Font nickFont = client.isNickBold() ? bold : normal;
                if (line.startsWith(prefix)) {
                    drawNickAndRest(graphics, message, nickFont, normal, prefix,
                        line.substring(prefix.length()), x, y, width, IrcMessage.COLOR_MENTION);
                    return;
                }
                if (line.equals(message.getNick() + ":")) {
                    drawNickPrefix(graphics, message, nickFont, line, x, y);
                    return;
                }
                if (isLongNickChunk(message, line)) {
                    drawNickPrefix(graphics, message, nickFont, line, x, y);
                    return;
                }
            }
            if (lineIndex == 1 && message.getType() == IrcMessage.CHAT) {
                String line0 = message.getWrappedLine(width, fontSize, nickBold, 0);
                if (line0 != null && isLongNickChunk(message, line0)) {
                    String head = message.getNick().substring(line0.length()) + ":";
                    if (line.startsWith(head)) {
                        String tail = line.substring(head.length());
                        String shown = head;
                        if (tail.startsWith(" ")) {
                            shown = head + " ";
                            tail = tail.substring(1);
                        }
                        Font nickFont = client.isNickBold() ? bold : normal;
                        drawNickAndRest(graphics, message, nickFont, normal, shown, tail, x, y,
                            width, IrcMessage.COLOR_MENTION);
                        return;
                    }
                }
            }
            graphics.setFont(normal);
            graphics.setColor(IrcMessage.COLOR_MENTION);
            graphics.drawString(IrcDraw.fitText(line, normal, width), x, y, Graphics.LEFT | Graphics.TOP);
            return;
        }
        if (lineIndex == 0 && message.getType() == IrcMessage.CHAT) {
            String prefix = message.getNick() + ": ";
            Font nickFont = client.isNickBold() ? bold : normal;
            if (line.startsWith(prefix)) {
                drawNickAndRest(graphics, message, nickFont, normal, prefix,
                    line.substring(prefix.length()), x, y, width, IrcMessage.COLOR_TEXT);
                return;
            }
            if (line.equals(message.getNick() + ":")) {
                drawNickPrefix(graphics, message, nickFont, line, x, y);
                return;
            }
            if (isLongNickChunk(message, line)) {
                drawNickPrefix(graphics, message, nickFont, line, x, y);
                return;
            }
        }
        if (lineIndex == 1 && message.getType() == IrcMessage.CHAT) {
            String line0 = message.getWrappedLine(width, fontSize, nickBold, 0);
            if (line0 != null && isLongNickChunk(message, line0)) {
                String head = message.getNick().substring(line0.length()) + ":";
                if (line.startsWith(head)) {
                    String tail = line.substring(head.length());
                    String shown = head;
                    if (tail.startsWith(" ")) {
                        shown = head + " ";
                        tail = tail.substring(1);
                    }
                    Font nickFont = client.isNickBold() ? bold : normal;
                    drawNickAndRest(graphics, message, nickFont, normal, shown, tail, x, y,
                        width, IrcMessage.COLOR_TEXT);
                    return;
                }
            }
        }
        if (message.isSingleLine()) {
            Font drawFont = message.isBold() ? bold : normal;
            graphics.setFont(drawFont);
            graphics.setColor(color);
            graphics.drawString(IrcDraw.fitText(display, drawFont, width), x, y, Graphics.LEFT | Graphics.TOP);
            return;
        }
        if (lineIndex == 0 && message.getType() == IrcMessage.KICK) {
            int markerLength = 2;
            if (line.length() > markerLength) {
                String marker = line.substring(0, markerLength);
                String rest = line.substring(markerLength);
                String prefix = message.getNick();
                if (rest.startsWith(prefix)) {
                    if (message.isBold()) {
                        graphics.setFont(bold);
                        graphics.setColor(color);
                        graphics.drawString(marker + prefix, x, y, Graphics.LEFT | Graphics.TOP);
                        graphics.setFont(normal);
                        graphics.drawString(rest.substring(prefix.length()), x + bold.stringWidth(marker + prefix), y,
                            Graphics.LEFT | Graphics.TOP);
                    } else {
                        graphics.drawString(IrcDraw.fitText(line, normal, width), x, y, Graphics.LEFT | Graphics.TOP);
                    }
                    return;
                }
            }
        }
        graphics.drawString(IrcDraw.fitText(line, normal, width), x, y, Graphics.LEFT | Graphics.TOP);
    }

    // Repeticion al mantener pulsado: paso fijo de 1 linea (suave) y lo
    // que acelera es el ritmo entre pasos. Algunos moviles no mandan
    // auto-repeat, asi que se usa Timer + keyReleased propio.
    private Timer repeatTimer;
    private TimerTask repeatTask;
    private int repeatDir;
    private int repeatMoves;
    private long repeatLast;

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
                repeatTimer = new Timer();
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

    // Minimizar con pulsacion larga: la tecla 3 solo baja la app si se
    // mantiene 200 ms; un click accidental (soltar antes) la cancela.
    private static final int MINIMIZE_HOLD_MS = 200;
    private Timer minimizeTimer;
    private TimerTask minimizeTask;

    private synchronized void startMinimizeHold() {
        cancelMinimizeHold();
        if (minimizeTimer == null) {
            try {
                minimizeTimer = new Timer();
            } catch (Exception e) {
                return;
            }
        }
        try {
            minimizeTask = new MinimizeTask();
            minimizeTimer.schedule(minimizeTask, MINIMIZE_HOLD_MS);
        } catch (Exception e) {
            minimizeTask = null;
        }
    }

    // Al cancelar se mata tambien el Timer: es de un solo uso y su hilo
    // no debe quedar ocioso para siempre (cada pila cuenta en la KVM).
    private synchronized void cancelMinimizeHold() {
        if (minimizeTask != null) {
            minimizeTask.cancel();
            minimizeTask = null;
        }
        if (minimizeTimer != null) {
            minimizeTimer.cancel();
            minimizeTimer = null;
        }
    }

    private final class MinimizeTask extends TimerTask {
        public void run() {
            cancelMinimizeHold();
            try {
                midlet.minimizeApp();
            } catch (Exception e) {
                // Nunca matar el hilo del Timer por una excepcion.
            }
        }
    }

    // Un paso por tick cuando toca segun el periodo (cada vez mas rapido).
    private synchronized void repeatTick() {
        if (repeatDir == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - repeatLast >= IrcDraw.repeatPeriod(repeatMoves)) {
            repeatLast = now;
            repeatMoves++;
            if (mentionMode) {
                mentionMove(repeatDir > 0 ? -1 : 1);
            } else if (repeatDir > 0) {
                scrollLine(1);
            } else {
                scrollLine(-1);
            }
        }
    }

    private final class RepeatTask extends TimerTask {
        public void run() {
            try {
                repeatTick();
            } catch (Exception e) {
                // Nunca matar el hilo del Timer por una excepcion.
            }
        }
    }

    private void scrollUp() {
        scrollLine(1);
    }

    private void scrollDown() {
        scrollLine(-1);
    }

    // Paso siempre de 1 linea para un scroll suave.
    private void scrollLine(int dir) {
        IrcWindow window = client.getActiveWindow();
        if (window == null) {
            return;
        }
        window.setScroll(window.getScroll() + dir);
        repaint();
    }

    // Memoria del heap (Runtime CLDC): gc previo para lectura estable.
    // Pico maximo desde el arranque para ver si el techo sigue subiendo.
    private static long peakUsed;

    private void openMemory() {
        Runtime runtime = Runtime.getRuntime();
        try {
            runtime.gc();
        } catch (Exception e) {
            // Sin gc la lectura baila unos KB: se muestra igual.
        }
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long used = total - free;
        if (used < 0) {
            used = 0;
        }
        if (used > peakUsed) {
            peakUsed = used;
        }
        int threads = 0;
        try {
            threads = Thread.activeCount();
        } catch (Exception e) {
            // Sin conteo: se muestra igual.
        }
        // Compacto en 4 lineas para pantallas pequenas: todo en KB, la
        // libre se calcula (total - usada). T=threads, W=ventanas.
        long percent = total > 0 ? (used * 100) / total : 0;
        String text = IrcStrings.get(IrcStrings.MEM_TOTAL) + (total / 1024) + " kb (" + percent + "%)" + IrcStrings.get(IrcStrings.MEM_USED)
            + (used / 1024) + IrcStrings.get(IrcStrings.MEM_MAX) + (peakUsed / 1024);
        if (client != null) {
            try {
                int[] stats = client.getBufferStats();
                text += IrcStrings.get(IrcStrings.MEM_T) + threads + IrcStrings.get(IrcStrings.MEM_W) + stats[0] + IrcStrings.get(IrcStrings.MEM_MSG) + stats[1]
                    + IrcStrings.get(IrcStrings.MEM_NICKS) + stats[2];
            } catch (Exception e) {
                text += IrcStrings.get(IrcStrings.MEM_THREADS) + threads;
            }
        } else {
            text += IrcStrings.get(IrcStrings.MEM_THREADS) + threads;
        }
        // Sin AlertType: con INFO el telefono emite su propio aviso
        // (sonido+vibracion del sistema) al mostrar el modal.
        Alert alert = new Alert(IrcStrings.get(IrcStrings.MEM_TITLE), text, null, null);
        alert.setTimeout(Alert.FOREVER);
        alert.addCommand(new Command(IrcStrings.get(IrcStrings.CMD_BACK), Command.BACK, 1));
        alert.setCommandListener(new MemoryListener());
        display.setCurrent(alert);
    }

    private final class MemoryListener implements CommandListener {
        public void commandAction(Command command, Displayable source) {
            showCanvas();
        }
    }

    private void startMentionMode() {
        IrcWindow active = client.getActiveWindow();
        if (active == null || !active.isChannel()) {
            openInput();
            return;
        }
        IrcMessage last = lastWritable(active.snapshot());
        if (last == null) {
            openInput();
            return;
        }
        mentionMode = true;
        mentionMessage = last;
        // El pintado lo clava visible el solo; se parte del final para
        // ver lo recien escrito al entrar.
        active.setScroll(0);
        repaint();
    }

    private void exitMentionMode() {
        if (!mentionMode) {
            return;
        }
        mentionMode = false;
        mentionMessage = null;
        anchorWindow = null;
        anchorMessage = null;
        anchorScroll = 0;
        repaint();
    }

    // Solo cuenta lo escrito por gente (chat y /me con autor). Las
    // flechas y uniones no se pueden contestar.
    private static boolean isWritable(IrcMessage message) {
        int type = message.getType();
        return type == IrcMessage.CHAT || type == IrcMessage.ACTION;
    }

    private static IrcMessage lastWritable(IrcMessage[] snapshot) {
        IrcMessage found = null;
        int i = 0;
        while (i < snapshot.length) {
            IrcMessage message = snapshot[i];
            if (message != null && isWritable(message) && message.getNick().length() > 0) {
                found = message;
            }
            i++;
        }
        return found;
    }

    // Ancla la linea exacta firstLine: guarda el mensaje que la contiene
    // y el desplazamiento interno dentro de su bloque. Misma cuenta que
    // el bucle principal (incluidos sellos). Sin asignar nada.
    private void anchorAtLine(IrcMessage[] snapshot, int[] messageLines, boolean stamps, int count,
        int firstLine) {
        anchorMessage = null;
        anchorIntra = 0;
        int off = 0;
        int i = 0;
        while (i < count) {
            int lines = messageLines[i];
            if (stamps && snapshot[i] != null) {
                lines += 2;
            }
            if (lines < 1) {
                lines = 1;
            }
            if (off + lines > firstLine && snapshot[i] != null) {
                anchorMessage = snapshot[i];
                anchorIntra = firstLine - off;
                if (anchorIntra < 0) {
                    anchorIntra = 0;
                }
                return;
            }
            off += lines;
            i++;
        }
    }

    // Offset del seleccionado para clavarlo primero visible, o -1 si ya
    // salio del buffer. Misma cuenta de lineas que el bucle principal
    // (incluidos sellos) para no desviar ni una linea.
    private int mentionPinFirstLine(IrcWindow window, IrcMessage[] snapshot, int lineWidth,
        int fontSize) {
        if (!mentionMode || mentionMessage == null) {
            return -1;
        }
        boolean stamps = window.isServer();
        int offset = 0;
        int i = 0;
        while (i < snapshot.length) {
            IrcMessage current = snapshot[i];
            if (current == mentionMessage) {
                return offset;
            }
            if (current == null || current.isSingleLine()) {
                offset++;
            } else {
                offset += current.getWrappedCount(lineWidth, fontSize, client.isNickBold());
            }
            if (stamps && current != null) {
                offset += 2;
            }
            i++;
        }
        return -1;
    }

    private void mentionMove(int dir) {
        IrcWindow window = client.getActiveWindow();
        if (!mentionMode || window == null || !window.isChannel()) {
            exitMentionMode();
            return;
        }
        IrcMessage[] snapshot = window.snapshot();
        int total = 0;
        int current = -1;
        int k = 0;
        while (k < snapshot.length) {
            IrcMessage message = snapshot[k];
            if (message != null && isWritable(message) && message.getNick().length() > 0) {
                if (message == mentionMessage) {
                    current = total;
                }
                total++;
            }
            k++;
        }
        if (total <= 0) {
            exitMentionMode();
            repaint();
            return;
        }
        int wanted = current < 0 ? (dir > 0 ? 0 : total - 1) : (current + dir + total) % total;
        int seen = 0;
        k = 0;
        while (k < snapshot.length) {
            IrcMessage message = snapshot[k];
            if (message != null && isWritable(message) && message.getNick().length() > 0) {
                if (seen == wanted) {
                    mentionMessage = message;
                    repaint();
                    return;
                }
                seen++;
            }
            k++;
        }
    }

    private void confirmMention() {
        IrcMessage selected = mentionMessage;
        exitMentionMode();
        if (selected != null && selected.getNick().length() > 0) {
            openInput(selected.getNick() + ' ');
        } else {
            openInput();
        }
    }

    private void openInput() {
        openInput("");
    }

    private void openInput(String initial) {
        TextBox input = new TextBox(IrcStrings.get(IrcStrings.TITLE_MESSAGE), initial, 256, TextField.ANY);
        IrcDraw.lowerCaseInput(input);
        Command send = new Command(IrcStrings.get(IrcStrings.CMD_SEND), Command.OK, 1);
        input.addCommand(send);
        input.addCommand(new Command(IrcStrings.get(IrcStrings.CMD_CANCEL), Command.BACK, 2));
        input.setCommandListener(new InputListener(send));
        display.setCurrent(input);
    }

    private void openJoin() {
        TextBox input = new TextBox(IrcStrings.get(IrcStrings.TITLE_JOINKEY), "#", 80, TextField.ANY);
        IrcDraw.lowerCaseInput(input);
        Command join = new Command(IrcStrings.get(IrcStrings.CMD_JOIN_ENTER), Command.OK, 1);
        input.addCommand(join);
        input.addCommand(new Command(IrcStrings.get(IrcStrings.CMD_CANCEL), Command.BACK, 2));
        input.setCommandListener(new JoinListener(join));
        display.setCurrent(input);
    }

    private void openNickList() {
        IrcWindow window = client.getActiveWindow();
        if (window == null || !window.isChannel()) {
            midlet.showNotice(IrcStrings.get(IrcStrings.NOTICE_NICKS_CHANNEL));
            return;
        }
        client.requestNames();
        display.setCurrent(new IrcNickList(midlet, client, window, IrcNickList.PICK_QUERY));
    }

    private void openMenu() {
        String[] labels = new String[18];
        int[] actions = new int[18];
        int count = 0;
        IrcWindow active = client.getActiveWindow();
        boolean inQuery = active != null && active.isQuery();
        boolean inChannel = active != null && active.isChannel();
        if (inQuery) {
            labels[count] = IrcStrings.get(IrcStrings.MENU_WHOIS_PRE) + active.getName();
            actions[count++] = ACT_WHOIS;
            if (client.isIgnored(active.getName())) {
                labels[count] = IrcStrings.get(IrcStrings.MENU_UNIGNORE);
            } else {
                labels[count] = IrcStrings.get(IrcStrings.MENU_IGNORE);
            }
            actions[count++] = ACT_IGNORE;
        }
        if (inChannel) {
            labels[count] = IrcStrings.get(IrcStrings.MENU_NICKS);
            actions[count++] = ACT_NICKS;
        }
        labels[count] = IrcStrings.get(IrcStrings.MENU_ACTIONS);
        actions[count++] = ACT_ACCIONES;
        labels[count] = IrcStrings.get(IrcStrings.MENU_JOIN);
        actions[count++] = ACT_JOIN;
        labels[count] = IrcStrings.get(IrcStrings.MENU_WINDOWS);
        actions[count++] = ACT_WINDOWS;
        labels[count] = IrcStrings.get(IrcStrings.MENU_CLEAR);
        actions[count++] = ACT_CLEAR;
        labels[count] = IrcStrings.get(IrcStrings.MENU_CHANLIST);
        actions[count++] = ACT_CHANLIST;
        if (inChannel) {
            labels[count] = IrcStrings.get(IrcStrings.MENU_CHANCTL);
            actions[count++] = ACT_CHANCTL;
        }
        labels[count] = IrcStrings.get(IrcStrings.MENU_INTERFACE);
        actions[count++] = ACT_INTERFACE;
        labels[count] = IrcStrings.get(IrcStrings.MENU_IGNORES);
        actions[count++] = ACT_IGNORES;
        labels[count] = IrcStrings.get(IrcStrings.MENU_EXIT);
        actions[count++] = ACT_EXIT;
        labels[count] = client.isConnected() ? IrcStrings.get(IrcStrings.MENU_DISCONNECT) : IrcStrings.get(IrcStrings.MENU_CONNECT);
        actions[count++] = ACT_CONNECT;
        labels[count] = IrcStrings.get(IrcStrings.MENU_MINIMIZE);
        actions[count++] = ACT_MINIMIZE;
        labels[count] = IrcStrings.get(IrcStrings.MENU_CLOSE);
        actions[count++] = ACT_CLOSE;
        final List menu = new List(client.getMenuTitle(), List.IMPLICIT);
        int i = 0;
        while (i < count) {
            menu.append(labels[i], null);
            i++;
        }
        Command back = new Command(IrcStrings.get(IrcStrings.CMD_BACK), Command.BACK, 1);
        menu.addCommand(back);
        menu.setCommandListener(new MenuListener(actions, back));
        display.setCurrent(menu);
    }

    public void openChanList() {
        display.setCurrent(new IrcChanList(midlet, client));
    }

    private void openChanCtl() {
        IrcWindow active = client.getActiveWindow();
        if (active == null || !active.isChannel()) {
            showCanvas();
            return;
        }
        if (!client.isConnected()) {
            showCanvas();
            midlet.showNotice(IrcStrings.get(IrcStrings.NOTICE_OFFLINE), this);
            return;
        }
        String[] labels = new String[] {
            IrcStrings.get(IrcStrings.CC_TOPIC), IrcStrings.get(IrcStrings.CC_KICK), IrcStrings.get(IrcStrings.CC_BAN), IrcStrings.get(IrcStrings.CC_UNBAN),
            IrcStrings.get(IrcStrings.CC_OP), IrcStrings.get(IrcStrings.CC_DEOP), IrcStrings.get(IrcStrings.CC_VOICE), IrcStrings.get(IrcStrings.CC_DEVOICE),
            IrcStrings.get(IrcStrings.CC_PRIVATE), IrcStrings.get(IrcStrings.CC_PUBLIC), IrcStrings.get(IrcStrings.CC_INVITE), IrcStrings.get(IrcStrings.CC_UNINVITE),
            IrcStrings.get(IrcStrings.CC_MOD), IrcStrings.get(IrcStrings.CC_UNMOD), IrcStrings.get(IrcStrings.CC_SETKEY), IrcStrings.get(IrcStrings.CC_UNSETKEY)
        };
        int[] ops = new int[] {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
        };
        final List list = new List(IrcStrings.get(IrcStrings.MENU_CHANCTL), List.IMPLICIT);
        int i = 0;
        while (i < labels.length) {
            list.append(labels[i], null);
            i++;
        }
        Command back = new Command(IrcStrings.get(IrcStrings.CMD_BACK), Command.BACK, 1);
        list.addCommand(back);
        list.setCommandListener(new ChanCtlListener(ops, back));
        display.setCurrent(list);
    }

    private void openTopicPrompt(String channel) {
        TextBox input = new TextBox(IrcStrings.get(IrcStrings.TITLE_TOPIC), "", 128, TextField.ANY);
        IrcDraw.lowerCaseInput(input);
        Command set = new Command(IrcStrings.get(IrcStrings.CMD_SET), Command.OK, 1);
        input.addCommand(set);
        input.addCommand(new Command(IrcStrings.get(IrcStrings.CMD_CANCEL), Command.BACK, 2));
        input.setCommandListener(new TopicListener(channel, set));
        display.setCurrent(input);
    }

    private void openKeyPrompt(String channel) {
        TextBox input = new TextBox(IrcStrings.get(IrcStrings.TITLE_KEY), "", 32, TextField.ANY);
        IrcDraw.lowerCaseInput(input);
        Command set = new Command(IrcStrings.get(IrcStrings.CMD_SET), Command.OK, 1);
        input.addCommand(set);
        input.addCommand(new Command(IrcStrings.get(IrcStrings.CMD_CANCEL), Command.BACK, 2));
        input.setCommandListener(new KeyListener(channel, set));
        display.setCurrent(input);
    }

    public void openWinList() {
        display.setCurrent(new IrcWinList(midlet, client));
    }


    private final class InputListener implements CommandListener {
        private final Command send;

        InputListener(Command value) {
            send = value;
        }

        public void commandAction(Command command, Displayable source) {
            if (command == send) {
                String value = ((TextBox) source).getString();
                client.submitText(value);
            }
            // Al volver del editor (enviado o cancelado) se baja al final
            // para ver los mensajes nuevos.
            IrcWindow back = client.getActiveWindow();
            showCanvas();
            if (back != null) {
                back.setScroll(0);
            }
        }
    }

    private final class JoinListener implements CommandListener {
        private final Command join;

        JoinListener(Command value) {
            join = value;
        }

        public void commandAction(Command command, Displayable source) {
            if (command == join) {
                client.requestJoin(((TextBox) source).getString());
                showCanvas();
            } else {
                showCanvas();
            }
        }
    }

    private final class MenuListener implements CommandListener {
        private final int[] actions;
        private final Command back;

        MenuListener(int[] value, Command backCommand) {
            actions = value;
            back = backCommand;
        }

        public void commandAction(Command command, Displayable source) {
            if (command == back) {
                showCanvas();
                return;
            }
            int selected = ((List) source).getSelectedIndex();
            if (selected < 0 || selected >= actions.length) {
                showCanvas();
                return;
            }
            int action = actions[selected];
            if (action == ACT_WHOIS) {
                showCanvas();
                IrcWindow query = client.getActiveWindow();
                if (query != null && query.isQuery()) {
                    client.send("WHOIS " + IrcText.clean(query.getName()));
                }
            } else if (action == ACT_IGNORE) {
                showCanvas();
                IrcWindow query = client.getActiveWindow();
                if (query != null && query.isQuery()) {
                    client.toggleIgnore(query.getName());
                }
            } else if (action == ACT_NICKS) {
                showCanvas();
                openNickList();
            } else if (action == ACT_JOIN) {
                showCanvas();
                openJoin();
            } else if (action == ACT_CHANLIST) {
                showCanvas();
                client.requestChanList();
                openChanList();
            } else if (action == ACT_WINDOWS) {
                showCanvas();
                openWinList();
            } else if (action == ACT_CLOSE) {
                client.closeWindow(client.getActiveWindow());
                showCanvas();
            } else if (action == ACT_CHANCTL) {
                openChanCtl();
            } else if (action == ACT_CONNECT) {
                showCanvas();
                if (client.isConnected()) {
                    client.disconnect();
                } else if (!client.hasUsableProfile()) {
                    midlet.showProfilesAfterNotice(IrcStrings.get(IrcStrings.NOTICE_NO_PROFILE));
                } else {
                    client.connect();
                }
            } else if (action == ACT_ACCIONES) {
                showCanvas();
                midlet.showActions();
            } else if (action == ACT_INTERFACE) {
                showCanvas();
                midlet.showPrefs(true);
            } else if (action == ACT_IGNORES) {
                showCanvas();
                display.setCurrent(new IrcIgnoreList(midlet, client));
            } else if (action == ACT_CLEAR) {
                showCanvas();
                client.clearActive();
            } else if (action == ACT_EXIT) {
                midlet.exit();
            } else if (action == ACT_MINIMIZE) {
                // Sin showCanvas previo: minimizeApp ya deja todo en
                // Status y la AMS oculta la app.
                midlet.minimizeApp();
            } else {
                showCanvas();
            }
        }
    }

    private final class ChanCtlListener implements CommandListener {
        private final int[] ops;
        private final Command back;

        ChanCtlListener(int[] value, Command backCommand) {
            ops = value;
            back = backCommand;
        }

        public void commandAction(Command command, Displayable source) {
            if (command == back) {
                showCanvas();
                return;
            }
            int selected = ((List) source).getSelectedIndex();
            if (selected < 0 || selected >= ops.length) {
                showCanvas();
                return;
            }
            IrcWindow window = client.getActiveWindow();
            if (window == null || !window.isChannel()) {
                showCanvas();
                return;
            }
            String channel = window.getName();
            int op = ops[selected];
            if (op == 0) {
                openTopicPrompt(channel);
            } else if (op == 1) {
                openNickPicker(window, IrcNickList.PICK_KICK);
            } else if (op == 2) {
                openNickPicker(window, IrcNickList.PICK_BAN);
            } else if (op == 3) {
                client.requestBanList(channel);
                display.setCurrent(new IrcBanList(midlet, client));
            } else if (op >= 4 && op <= 7) {
                int[] picks = new int[] {
                    IrcNickList.PICK_OP, IrcNickList.PICK_DEOP,
                    IrcNickList.PICK_VOICE, IrcNickList.PICK_DEVOICE
                };
                openNickPicker(window, picks[op - 4]);
            } else if (op == 8) {
                client.send("MODE " + channel + " +p");
                showCanvas();
            } else if (op == 9) {
                client.send("MODE " + channel + " -p");
                showCanvas();
            } else if (op == 10) {
                client.send("MODE " + channel + " +i");
                showCanvas();
            } else if (op == 11) {
                client.send("MODE " + channel + " -i");
                showCanvas();
            } else if (op == 12) {
                client.send("MODE " + channel + " +m");
                showCanvas();
            } else if (op == 13) {
                client.send("MODE " + channel + " -m");
                showCanvas();
            } else if (op == 14) {
                openKeyPrompt(channel);
            } else if (op == 15) {
                client.send("MODE " + channel + " -k");
                showCanvas();
            } else {
                showCanvas();
            }
        }
    }

    private final class TopicListener implements CommandListener {
        private final String channel;
        private final Command set;

        TopicListener(String value, Command setCommand) {
            channel = value;
            set = setCommand;
        }

        public void commandAction(Command command, Displayable source) {
            if (command == set) {
                String topic = ((TextBox) source).getString();
                client.send(IrcClient.topicLine(IrcText.clean(channel), IrcText.clean(topic)));
            }
            showCanvas();
        }
    }

    private final class KeyListener implements CommandListener {
        private final String channel;
        private final Command set;

        KeyListener(String value, Command setCommand) {
            channel = value;
            set = setCommand;
        }

        public void commandAction(Command command, Displayable source) {
            if (command == set) {
                String key = IrcText.trim(((TextBox) source).getString());
                if (key.length() > 0) {
                    client.send("MODE " + IrcText.clean(channel) + " +k " + IrcText.clean(key));
                }
            }
            showCanvas();
        }
    }

    private void openNickPicker(IrcWindow window, int pickOp) {
        showCanvas();
        display.setCurrent(new IrcNickList(midlet, client, window, pickOp));
    }
}
