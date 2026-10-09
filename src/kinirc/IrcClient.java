package kinirc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.SocketConnection;

public final class IrcClient {
    public static final int DISCONNECTED = 0;
    public static final int CONNECTING = 1;
    public static final int CONNECTED = 2;

    private static final int EVENT_LIMIT = 180;
    private static final int STATE_EVENT = 1;
    private static final int SERVER_EVENT = 2;
    private static final int CHAT_EVENT = 3;
    private static final int NOTICE_EVENT = 4;
    private static final int JOIN_EVENT = 5;
    private static final int PART_EVENT = 6;
    private static final int QUIT_EVENT = 7;
    private static final int NICK_EVENT = 8;
    private static final int NAMES_EVENT = 9;
    private static final int TOPIC_EVENT = 10;
    private static final int KICK_EVENT = 11;
    private static final int MODE_EVENT = 12;
    private static final int LIST_EVENT = 13;
    private static final int LIST_END_EVENT = 14;
    private static final int WHOIS_EVENT = 15;
    private static final int BAN_EVENT = 16;
    private static final int BAN_END_EVENT = 17;

    // Foto de canales con joined al caer: solo "conectar desde el perfil"
    // entra en los preconfigurados. El resto (boton Conectar, auto) reentra
    // en la foto; foto vacia con sesion previa = solo conecta al servidor.
    // Sin ninguna ventana de canal (fresco) se usa el perfil igualmente.
    private Vector sessionChans = new Vector();
    private boolean profileJoin;
    private volatile IrcSettings settings;
    private volatile IrcCanvas canvas;
    private volatile IrcNickList nickList;
    private volatile IrcChanList chanList;
    private volatile IrcProfile connectProfile;
    private SocketConnection socket;
    private InputStream input;
    private OutputStream output;
    private volatile boolean running;
    private volatile boolean stopping;
    private int state = DISCONNECTED;
    private int connectionId;
    // Auto-reconnect con backoff en hilo aparte (no bloquea UI ni red).
    private int retryCount;
    static final int MAX_RETRIES = 10;
    // Watchdog de silencio: si el servidor no manda nada en este tiempo
    // (suelen mandar PING cada 1-2 min), la conexion esta muerta a medias.
    private volatile long lastTraffic;
    private boolean silenceDropped;
    private static final long SILENCE_LIMIT_MS = 240000;
    private String currentNick = "";
    private int nickAttempts;
    // Antes del 001 (registro) el cliente reintenta nicks solo; despues
    // nunca toca el nick por su cuenta. baseNick = nick del perfil para
    // generar alternativas; nickShort = el servidor dio 432 (recortar).
    private boolean welcomed;
    private boolean nickShort;
    private String baseNick = "";
    private static final int MAX_NICK_ATTEMPTS = 3;
    private static final java.util.Random NICK_RANDOM = new java.util.Random();
    private Vector windows = new Vector();
    private IrcWindow activeWindow;
    private IrcWindow serverWindow;
    private Vector pendingEvents = new Vector();
    private Vector chanEntries = new Vector();
    private int chanTotal;
    private String chanFilter = "";
    private volatile boolean listingChannels;
    private Vector banEntries = new Vector();
    private String banChannel = "";
    private volatile IrcBanList banList;
    private volatile IrcWinList winList;
    // Numericos IRC usados (antes literales sueltos por el codigo).
    private static final String RPL_WELCOME = "001";
    private static final String RPL_NAMREPLY = "353";
    private static final String ERR_NICKNAMEINUSE = "433";
    private static final String ERR_ERRONEUSNICK = "432";
    private static final String RPL_TOPIC = "332";
    private static final String RPL_CHANNELMODEIS = "324";
    private static final String RPL_NOTOPIC = "331";
    private static final String RPL_LISTSTART = "321";
    private static final String RPL_LIST = "322";
    private static final String RPL_LISTEND = "323";
    private static final String RPL_BANLIST = "367";
    private static final String RPL_ENDOFBANLIST = "368";
    private static final String RPL_ENDOFNAMES = "366";
    private static final String RPL_MOTDSTART = "375";
    private static final String RPL_MOTD = "372";
    private static final String RPL_ENDOFMOTD = "376";
    private static final String ERR_NOMOTD = "422";
    private static final String ERR_PASSWDMISMATCH = "464";

    private final Object sendLock = new Object();
    // Ultimo fallo de escritura: si la radio murio, el QUIT de disconnect
    // se omite para no colgar al llamador en un write sin retorno.
    private volatile long lastSendFail;

    public IrcClient(IrcSettings settings) {
        this.settings = settings;
        serverWindow = new IrcWindow(IrcWindow.STATUS, "Status", settings.getHistoryLimit());
        windows.addElement(serverWindow);
        activeWindow = serverWindow;
    }

    public void attachCanvas(IrcCanvas canvas) {
        this.canvas = canvas;
    }

    public void attachNickList(IrcNickList list) {
        nickList = list;
    }

    public void detachNickList(IrcNickList list) {
        if (nickList == list) {
            nickList = null;
        }
    }

    public void attachChanList(IrcChanList list) {
        chanList = list;
    }

    public void detachChanList(IrcChanList list) {
        if (chanList == list) {
            chanList = null;
        }
    }

    public void attachBanList(IrcBanList list) {
        banList = list;
    }

    public void detachBanList(IrcBanList list) {
        if (banList == list) {
            banList = null;
        }
    }

    public void attachWinList(IrcWinList list) {
        winList = list;
    }

    public void detachWinList(IrcWinList list) {
        if (winList == list) {
            winList = null;
        }
    }

    public synchronized int getBanCount() {
        return banEntries.size();
    }

    public synchronized String getBanAt(int index) {
        if (index < 0 || index >= banEntries.size()) {
            return "";
        }
        return (String) banEntries.elementAt(index);
    }

    public synchronized String getBanChannel() {
        return banChannel;
    }

    public void requestBanList(String channel) {
        String chan = IrcText.clean(channel);
        synchronized (this) {
            banEntries.removeAllElements();
            banChannel = chan;
        }
        if (!send("MODE " + chan + " +b")) {
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_LIST_OFFLINE)));
        }
        repaint();
    }

    // Unban por mascara (la que se veia en pantalla), no por indice.
    public void unban(String mask) {
        String target = IrcText.trim(mask);
        if (target.length() == 0) {
            return;
        }
        String chan;
        boolean found;
        synchronized (this) {
            int index = banEntries.indexOf(target);
            chan = banChannel;
            found = index >= 0;
            if (found) {
                banEntries.removeElementAt(index);
            }
        }
        if (!found) {
            return;
        }
        if (send(unbanLine(IrcText.clean(chan), IrcText.clean(target)))) {
            addServerMessage(IrcMessage.system(IrcStrings.get(IrcStrings.SYS_UNBANNED_PRE)
                + target));
        } else {
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_SEND_OFFLINE)));
        }
        repaint();
    }

    public void unbanAt(int index) {
        String mask;
        String chan;
        synchronized (this) {
            if (index < 0 || index >= banEntries.size()) {
                return;
            }
            mask = (String) banEntries.elementAt(index);
            chan = banChannel;
            banEntries.removeElementAt(index);
        }
        if (send(unbanLine(IrcText.clean(chan), IrcText.clean(mask)))) {
            addServerMessage(IrcMessage.system(IrcStrings.get(IrcStrings.SYS_UNBANNED_PRE) + mask));
        } else {
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_SEND_OFFLINE)));
        }
        repaint();
    }

    public void chanOp(IrcWindow window, int op, String nick) {
        boolean known;
        boolean channel;
        synchronized (this) {
            known = window != null && windows.indexOf(window) >= 0;
            channel = known && window.isChannel();
        }
        String target = IrcText.trim(nick);
        if (!known || !channel || target.length() == 0) {
            return;
        }
        String chan;
        synchronized (this) {
            chan = window.getName();
        }
        chan = IrcText.clean(chan);
        target = IrcText.clean(target);
        String line = null;
        if (op == IrcNickList.PICK_KICK) {
            line = "KICK " + chan + " " + target;
        } else if (op == IrcNickList.PICK_BAN) {
            line = banLine(chan, banMask(target));
        } else if (op == IrcNickList.PICK_OP) {
            line = "MODE " + chan + " +o " + target;
        } else if (op == IrcNickList.PICK_DEOP) {
            line = "MODE " + chan + " -o " + target;
        } else if (op == IrcNickList.PICK_VOICE) {
            line = "MODE " + chan + " +v " + target;
        } else if (op == IrcNickList.PICK_DEVOICE) {
            line = "MODE " + chan + " -v " + target;
        }
        if (line != null && !send(line)) {
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_SEND_OFFLINE)));
        }
    }

    // No sincronizado completo: antes mantenia el monitor de IrcClient
    // durante send() (I/O bloqueante) y congelaba paint/getActiveWindow.
    public void updateSettings(IrcSettings value) {
        synchronized (this) {
            settings = value;
            updateCapacities();
        }
    }

    // Aplica el nick del perfil en vivo, solo cuando el usuario lo cambia
    // en el formulario de perfil. Interfaz y resto de ajustes no tocan
    // el nick: nada de cambios fantasma al guardar.
    public void applyProfileNick() {
        String wantNick;
        boolean connected;
        synchronized (this) {
            IrcProfile profile = settings.getActiveProfile();
            wantNick = profile == null ? "" : profile.getNick();
            connected = running;
        }
        if (connected && wantNick.length() > 0) {
            String current;
            synchronized (this) {
                current = currentNick;
            }
            if (!IrcText.ircEquals(current, wantNick)) {
                send("NICK " + IrcText.clean(wantNick));
            }
        }
    }

    public synchronized int getState() {
        return state;
    }

    public String getStateText() {
        int value = getState();
        if (value == CONNECTING) {
            return IrcStrings.get(IrcStrings.STATE_CONNECTING);
        }
        if (value == CONNECTED) {
            synchronized (this) {
                return IrcStrings.get(IrcStrings.STATE_CONNECTED_PRE) + currentNick;
            }
        }
        return IrcStrings.get(IrcStrings.STATE_DISCONNECTED);
    }

    public synchronized IrcWindow getActiveWindow() {
        return activeWindow;
    }

    private synchronized String activeChannelName() {
        if (activeWindow != null && activeWindow.isChannel()) {
            return activeWindow.getName();
        }
        return "";
    }

    private static int compareNames(String first, String second) {
        return IrcText.ircLower(first).compareTo(IrcText.ircLower(second));
    }

    // Ordena canales por usuarios de mas a menos.
    static void sortChanEntries(Vector entries) {
        quickSortChan(entries, 0, entries.size() - 1);
    }

    private static void quickSortChan(Vector entries, int low, int high) {
        if (low >= high) {
            return;
        }
        int left = low;
        int right = high;
        int pivot = chanUsers((String[]) entries.elementAt((low + high) / 2));
        while (left <= right) {
            while (chanUsers((String[]) entries.elementAt(left)) > pivot) {
                left++;
            }
            while (chanUsers((String[]) entries.elementAt(right)) < pivot) {
                right--;
            }
            if (left <= right) {
                Object swap = entries.elementAt(left);
                entries.setElementAt(entries.elementAt(right), left);
                entries.setElementAt(swap, right);
                left++;
                right--;
            }
        }
        if (low < right) {
            quickSortChan(entries, low, right);
        }
        if (left < high) {
            quickSortChan(entries, left, high);
        }
    }

    static int chanUsers(String[] entry) {
        if (entry == null || entry.length < 2) {
            return 0;
        }
        try {
            return Integer.parseInt(IrcText.trim(entry[1]));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    // Copia ordenada compartida de solo lectura: el orden solo cambia al
    // anadir o borrar ventanas (tipo y nombre son finales), asi que se
    // reconstruye entonces y los frames la leen sin ordenar ni asignar.
    private Vector cachedOrderedWindows;

    public synchronized java.util.Vector getOrderedWindows() {
        if (cachedOrderedWindows == null) {
            rebuildOrderedWindows();
        }
        return cachedOrderedWindows;
    }

    private void rebuildOrderedWindows() {
        Vector ordered = new Vector(windows.size());
        int i = 0;
        while (i < windows.size()) {
            IrcWindow window = (IrcWindow) windows.elementAt(i);
            if (window.isServer()) {
                ordered.addElement(window);
            }
            i++;
        }
        appendSortedFrom(ordered, false);
        appendSortedFrom(ordered, true);
        cachedOrderedWindows = ordered;
    }

    private void appendSortedFrom(Vector ordered, boolean queries) {
        int i = 0;
        while (i < windows.size()) {
            IrcWindow window = (IrcWindow) windows.elementAt(i);
            if (!window.isServer() && window.isQuery() == queries) {
                int pos = ordered.size();
                while (pos > 0) {
                    IrcWindow other = (IrcWindow) ordered.elementAt(pos - 1);
                    if (other.isServer() || other.isQuery() != queries
                        || compareNames(other.getName(), window.getName()) <= 0) {
                        break;
                    }
                    pos--;
                }
                ordered.insertElementAt(window, pos);
            }
            i++;
        }
    }

    public synchronized IrcWindow getServerWindow() {
        return serverWindow;
    }

    public synchronized int getWindowCount() {
        return windows.size();
    }

    private synchronized boolean hasChannelWindow() {
        int i = 0;
        while (i < windows.size()) {
            if (((IrcWindow) windows.elementAt(i)).isChannel()) {
                return true;
            }
            i++;
        }
        return false;
    }

    // Desglose para el monitor de memoria (tecla 7): ventanas abiertas,
    // mensajes guardados en total y nicks guardados en total.
    public synchronized int[] getBufferStats() {
        int windowCount = 0;
        int messageCount = 0;
        int nickCount = 0;
        int i = 0;
        while (i < windows.size()) {
            IrcWindow window = (IrcWindow) windows.elementAt(i);
            windowCount++;
            messageCount += window.size();
            nickCount += window.getNickCount();
            i++;
        }
        return new int[] { windowCount, messageCount, nickCount };
    }

    public synchronized int getWindowIndex(IrcWindow window) {
        if (window == null) {
            return -1;
        }
        return windows.indexOf(window);
    }

    public String getProfileName() {
        IrcProfile profile = connectProfile;
        if (profile == null) {
            profile = settings.getActiveProfile();
        }
        return profile == null ? "" : profile.getLabel();
    }

    public String getMenuTitle() {
        String nick;
        synchronized (this) {
            nick = currentNick;
        }
        if (nick.length() == 0) {
            IrcProfile profile = settings.getActiveProfile();
            nick = profile == null ? "" : profile.getNick();
        }
        if (nick.length() == 0) {
            return "IRC";
        }
        return IrcStrings.get(IrcStrings.NICK_LABEL_PRE) + nick;
    }

    private boolean shouldAlert(IrcWindow window, Event event) {
        if (settings.getAlertMode() == IrcSettings.ALERT_OFF) {
            return false;
        }
        if (!isConnected()) {
            return false;
        }
        // Si la app no esta visible (minimizada o en otro formulario),
        // no estas leyendo nada aunque la ventana siga siendo la activa.
        boolean active = isForeground() && window == getActiveWindow();
        if (window.isQuery()) {
            return wantsAlert(active, true, false);
        }
        if (window.isChannel()) {
            return wantsAlert(active, false, mentions(event.text, currentNick));
        }
        return false;
    }

    // Solo avisa si la ventana no esta en primer plano (ni minimizado
    // hay ventana activa visible, asi que tambien avisa).
    static boolean wantsAlert(boolean activeWindow, boolean isQuery, boolean mentioned) {
        if (activeWindow) {
            return false;
        }
        return isQuery || mentioned;
    }

    // Mencion solo si el nick aparece como palabra completa: "music" no
    // salta con "musico". Los caracteres de valido a los lados son los
    // mismos de IRC, asi que [-_[]\^] tampoco cuenta como borde.
    static boolean mentions(String text, String nick) {
        if (text == null || nick == null || nick.length() == 0) {
            return false;
        }
        String haystack = IrcText.ircLower(text);
        String needle = IrcText.ircLower(nick);
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0 || !isNickChar(haystack.charAt(at - 1));
            int end = at + needle.length();
            boolean rightOk = end == haystack.length() || !isNickChar(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    static boolean isNickChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9') || c == '-' || c == '_'
            || c == '[' || c == ']' || c == '\\' || c == '^';
    }

    public boolean isIgnored(String nick) {
        return settings.isIgnored(nick);
    }

    public void toggleIgnore(String nick) {
        String clean = IrcText.trim(nick);
        if (clean.length() == 0) {
            return;
        }
        if (settings.isIgnored(clean)) {
            settings.removeIgnore(clean);
            settings.save();
            addServerMessage(IrcMessage.system(clean + " " + IrcStrings.get(IrcStrings.SUFFIX_UNIGNORED)));
        } else {
            settings.addIgnore(clean);
            settings.save();
            addServerMessage(IrcMessage.system(clean + " " + IrcStrings.get(IrcStrings.SUFFIX_IGNORED)));
        }
    }

    public synchronized int getIgnoreCount() {
        return settings.getIgnoreCount();
    }

    public synchronized String getIgnoreAt(int index) {
        return settings.getIgnoreAt(index);
    }

    // Desbloquea por posicion (pantalla de ignorados): solo local, sin red.
    // El save() con I/O va fuera del monitor para no congelar red y paint.
    public void unignoreAt(int index) {
        String nick;
        synchronized (this) {
            if (index < 0 || index >= settings.getIgnoreCount()) {
                return;
            }
            nick = settings.getIgnoreAt(index);
            settings.removeIgnore(nick);
        }
        settings.save();
        addServerMessage(IrcMessage.system(nick + " " + IrcStrings.get(IrcStrings.SUFFIX_UNIGNORED)));
        repaint();
    }

    public synchronized IrcWindow getWindowAt(int index) {
        if (index < 0 || index >= windows.size()) {
            return null;
        }
        return (IrcWindow) windows.elementAt(index);
    }

    public synchronized String getCurrentNick() {
        return currentNick;
    }

    public synchronized void nextWindow() {
        if (windows.size() < 2) {
            return;
        }
        int index = windows.indexOf(activeWindow);
        activeWindow = (IrcWindow) windows.elementAt((index + 1) % windows.size());
        activeWindow.markRead();
        repaint();
    }

    public synchronized void previousWindow() {
        if (windows.size() < 2) {
            return;
        }
        int index = windows.indexOf(activeWindow);
        activeWindow = (IrcWindow) windows.elementAt((index - 1 + windows.size()) % windows.size());
        activeWindow.markRead();
        repaint();
    }

    public synchronized void setActiveWindow(IrcWindow window) {
        if (window != null && windows.indexOf(window) >= 0) {
            activeWindow = window;
            activeWindow.markRead();
            repaint();
        }
    }

    // Atajo a Status (tecla 1).
    public void showStatus() {
        setActiveWindow(getServerWindow());
    }

    // La app se minimiza: se guarda la ventana y se pasa a Status para
    // que los avisos suenen (si siguiera el canal activo, wantsAlert
    // creeria que lo estas leyendo). Al maximizar se restaura.
    // Ahorro Energia: ademas se envia PART por cada canal abierto (las
    // ventanas se conservan y quedan marcadas como desconectadas por
    // isJoined=false, que el canvas pinta en rojo) y al restaurar se
    // reentra con JOIN en rafaga. Mantener Activo: el socket sigue
    // acumulando lineas en el buffer y no se envia PART ni JOIN.
    // En ambos modos el pintado queda a 0Hz (ver repaint/doRepaint).
    // Los send() van fuera del monitor: hacen I/O bloqueante y no
    // deben congelar paint/getActiveWindow.
    // Se llama desde pauseApp() y desde hideNotify() (muchos moviles no
    // invocan pauseApp al minimizar): backgrounded evita rafagas PART /
    // JOIN duplicadas cuando llegan las dos senales a la vez.
    private IrcWindow pausedWindow;
    private volatile boolean backgrounded;

    public void minimizeToStatus() {
        Vector partTargets = null;
        synchronized (this) {
            if (activeWindow != null && activeWindow != serverWindow) {
                pausedWindow = activeWindow;
                activeWindow = serverWindow;
                serverWindow.markRead();
            }
            if (settings.getBackgroundMode() == IrcSettings.BG_POWER_SAVE
                && isConnected()) {
                partTargets = new Vector();
                int i = 0;
                while (i < windows.size()) {
                    IrcWindow window = (IrcWindow) windows.elementAt(i);
                    if (window.isChannel() && window.isJoined()) {
                        partTargets.addElement(window.getName());
                    }
                    i++;
                }
            }
            backgrounded = true;
            repaintScheduled = false;
        }
        // Los PART van en hilo aparte: enviar 29 lineas con write+flush
        // en serie dentro del hilo de eventos cuelga la app si la radio
        // va a medias (mismo patron que disconnect(String)).
        final Vector parts_for_thread = partTargets;
        if (parts_for_thread != null) {
            try {
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        int parts = 0;
                        int i = 0;
                        while (i < parts_for_thread.size()) {
                            String channel = (String) parts_for_thread.elementAt(i);
                            if (send("PART " + IrcText.clean(channel))) {
                                parts++;
                                synchronized (IrcClient.this) {
                                    IrcWindow window = findWindow(IrcWindow.CHANNEL, channel);
                                    if (window != null) {
                                        window.setJoined(false);
                                        window.clearNicks();
                                    }
                                }
                            }
                            i++;
                        }
                        if (parts > 0) {
                            addServerMessage(IrcMessage.system(
                                IrcStrings.get(IrcStrings.SYS_PAUSED_PRE) + chanCountText(parts)));
                        }
                    }
                });
                t.start();
            } catch (Exception ignored) {
                // Sin hilos: se envian ahora (como antes).
                int parts = 0;
                int i = 0;
                while (i < parts_for_thread.size()) {
                    String channel = (String) parts_for_thread.elementAt(i);
                    if (send("PART " + IrcText.clean(channel))) {
                        parts++;
                        synchronized (IrcClient.this) {
                            IrcWindow window = findWindow(IrcWindow.CHANNEL, channel);
                            if (window != null) {
                                window.setJoined(false);
                                window.clearNicks();
                            }
                        }
                    }
                    i++;
                }
            }
        }
    }

    public void restoreFromPause() {
        Vector joinTargets = null;
        boolean resumed = false;
        synchronized (this) {
            resumed = backgrounded;
            backgrounded = false;
            if (resumed && settings.getBackgroundMode() == IrcSettings.BG_POWER_SAVE
                && isConnected()) {
                joinTargets = new Vector();
                int i = 0;
                while (i < windows.size()) {
                    IrcWindow window = (IrcWindow) windows.elementAt(i);
                    // Las ventanas en cierre no reentran: siguen saliendo.
                    if (window.isChannel() && !window.isJoined() && !window.isClosing()) {
                        joinTargets.addElement(window.getName());
                    }
                    i++;
                }
            }
            if (pausedWindow != null) {
                if (windows.indexOf(pausedWindow) >= 0) {
                    activeWindow = pausedWindow;
                    activeWindow.markRead();
                }
                pausedWindow = null;
            }
            // Reactiva el throttling configurado mostrando ya el texto
            // acumulado en segundo plano.
            lastRepaintTime = 0;
            repaintScheduled = false;
        }
        if (joinTargets != null && joinTargets.size() > 0) {
            int i = 0;
            while (i < joinTargets.size()) {
                send("JOIN " + IrcText.clean((String) joinTargets.elementAt(i)));
                i++;
            }
            addServerMessage(IrcMessage.system(IrcStrings.get(IrcStrings.SYS_RESUMED_PRE)
                + chanCountText(joinTargets.size())));
        }
        repaint();
    }

    // Modo solo texto: oculta los eventos de canal (join/part/quit/nick/
    // kick/mode/topic) dejando solo el texto. Solo filtra el dibujado: el
    // estado (nicks, flags, union) se actualiza siempre igual.
    private boolean isTextOnly() {
        return settings.isTextOnlyMode();
    }

    private static String chanCountText(int count) {
        if (count == 1) {
            return IrcStrings.get(IrcStrings.CHAN_ONE);
        }
        return count + IrcStrings.get(IrcStrings.CHAN_MANY_SUF);
    }

    // Segundo plano diferido compartido por las 6 pantallas: una pantalla
    // oculta no distingue un overlay del sistema (volumen) de un
    // minimizado real; la unica diferencia observable es la duracion. Si
    // a los BG_DELAY_MS sigue oculta, se aplica minimizeToStatus.
    // noteHidden solo programa cuando el current sigue siendo la propia
    // pantalla (al navegar entre pantallas de la app no se programa nada);
    // noteShown cancela. Idempotente con las vias explicita y pauseApp.
    private static final int BG_DELAY_MS = 30000;
    private java.util.Timer bgTimer;
    private java.util.TimerTask bgTask;

    public void noteHidden(boolean currentIsSelf) {
        if (!currentIsSelf) {
            return;
        }
        scheduleBackgroundCheck();
    }

    public void noteShown() {
        cancelBackgroundCheck();
    }

    private synchronized void scheduleBackgroundCheck() {
        cancelBackgroundCheck();
        if (bgTimer == null) {
            try {
                bgTimer = new java.util.Timer();
            } catch (Exception e) {
                return;
            }
        }
        try {
            bgTask = new BackgroundTask();
            bgTimer.schedule(bgTask, BG_DELAY_MS);
        } catch (Exception e) {
            bgTask = null;
        }
    }

    // Como minimizeTimer: Timer de un solo uso, se mata para no dejar su
    // hilo ocioso para siempre.
    private synchronized void cancelBackgroundCheck() {
        if (bgTask != null) {
            bgTask.cancel();
            bgTask = null;
        }
        if (bgTimer != null) {
            bgTimer.cancel();
            bgTimer = null;
        }
    }

    private final class BackgroundTask extends java.util.TimerTask {
        public void run() {
            cancelBackgroundCheck();
            try {
                // Seguro extra por si el terminal omite showNotify: con
                // alguna pantalla ya visible no hay nada que minimizar.
                if (isAnyScreenShown()) {
                    return;
                }
                minimizeToStatus();
            } catch (Exception e) {
                // Nunca matar el hilo del Timer por una excepcion.
            }
        }
    }

    private boolean isAnyScreenShown() {
        if (isShownQuiet(canvas)) {
            return true;
        }
        if (isShownQuiet(chanList)) {
            return true;
        }
        if (isShownQuiet(nickList)) {
            return true;
        }
        if (isShownQuiet(banList)) {
            return true;
        }
        if (isShownQuiet(winList)) {
            return true;
        }
        return false;
    }

    private static boolean isShownQuiet(javax.microedition.lcdui.Displayable screen) {
        if (screen == null) {
            return false;
        }
        try {
            return screen.isShown();
        } catch (Exception e) {
            return false;
        }
    }

    // Visibilidad real: canvas visible y MIDlet no pausado. El canvas se
    // oculta tambien al abrir menus/formularios, y pauseApp no siempre
    // se llama al minimizar segun el telefono; con las dos señales el
    // beep funciona en ambos casos.
    private volatile boolean canvasVisible;
    private volatile boolean appPaused;

    public void setCanvasVisible(boolean value) {
        canvasVisible = value;
    }

    public void setAppPaused(boolean value) {
        appPaused = value;
    }

    public boolean isForeground() {
        return !appPaused && canvasVisible;
    }

    public void connect() {
        synchronized (this) {
            profileJoin = false;
        }
        connectInternal(true);
    }

    // Conexion originada en el perfil (lista o guardar+conectar): entra en
    // los preconfigurados. Unica via con esa regla.
    public void connectFromProfile() {
        synchronized (this) {
            profileJoin = true;
        }
        connectInternal(true);
    }

    // Espera entre reintentos: 5s, 10s, 20s, 30s, 60s... (hasta 10 intentos
    // son unos 7 min). Estatico para poder probarlo sin red.
    static int retryDelayMs(int attempt) {
        if (attempt <= 1) {
            return 5000;
        }
        if (attempt == 2) {
            return 10000;
        }
        if (attempt == 3) {
            return 20000;
        }
        if (attempt == 4) {
            return 30000;
        }
        return 60000;
    }

    private void connectInternal(boolean manual) {
        final int id;
        synchronized (this) {
            if (state == CONNECTING || running) {
                return;
            }
            connectionId++;
            id = connectionId;
            state = CONNECTING;
            stopping = false;
            silenceDropped = false;
            if (manual) {
                retryCount = 0;
            }
            nickAttempts = 0;
            welcomed = false;
            nickShort = false;
            IrcWindow.resetServerPrefixes();
            IrcClient.resetChanModes();
            IrcProfile profile = settings.getActiveProfile();
            if (profile == null || !profile.isUsable()) {
                state = DISCONNECTED;
                postState(IrcStrings.get(IrcStrings.NOTICE_NO_PROFILE_CFG));
                return;
            }
            connectProfile = profile;
            currentNick = profile.getNick();
            if (currentNick.length() == 0) {
                currentNick = "Kin" + (System.currentTimeMillis() % 10000);
            }
            baseNick = currentNick;
            sessionChans.removeAllElements();
            if (!manual) {
                profileJoin = false;
            }
            // Foto siempre (manual y auto): la sesion viva manda, salvo
            // que el llamador marcara entrada desde el perfil.
            int j = 1;
            while (j < windows.size()) {
                IrcWindow window = (IrcWindow) windows.elementAt(j);
                if (window.isChannel() && window.isJoined()) {
                    sessionChans.addElement(window.getName());
                }
                j++;
            }
            int i = 1;
            while (i < windows.size()) {
                IrcWindow window = (IrcWindow) windows.elementAt(i);
                window.setJoined(false);
                window.clearNicks();
                i++;
            }
            updateCapacities();
            synchronized (pendingEvents) {
                pendingEvents.removeAllElements();
            }
        }
        postState(IrcStrings.get(IrcStrings.STATUS_CONNECTING_PRE) + connectProfile.getHost() + ":" + connectProfile.getPort());
        Thread newWorker = new Thread(new Runnable() {
            public void run() {
                runConnection(id);
            }
        });
        boolean start;
        synchronized (this) {
            start = id == connectionId;
        }
        if (start) {
            newWorker.start();
        }
    }

    public void disconnect() {
        disconnect(null);
    }

    // Unico camino para salir (menu Desconectar y /quit): marca stopping
    // antes de cerrar, asi el cierre del servidor no se toma por caida y
    // no salta el auto-reconnect. Motivo vacio = mensaje por defecto.
    // Unico camino para salir (menu Desconectar y /quit): marca stopping
    // antes de cerrar, asi el cierre del servidor no se toma por caida y
    // no salta el auto-reconnect. Motivo vacio = mensaje por defecto.
    public void disconnect(String reason) {
        String motive = IrcText.trim(reason == null ? "" : reason);
        if (motive.length() == 0) {
            motive = IrcStrings.get(IrcStrings.QUIT_MESSAGE);
        }
        boolean wasRunning;
        synchronized (this) {
            connectionId++;
            wasRunning = running;
            stopping = true;
            state = DISCONNECTED;
        }
        if (wasRunning && System.currentTimeMillis() - lastSendFail > 5000) {
            // Una sola linea: escribirla aqui (como siempre) no cuelga la
            // UI, y asi llega antes de que closeStreams() cierre el socket.
            sendRaw("QUIT :" + IrcText.clean(motive));
        }
        synchronized (this) {
            running = false;
        }
        closeStreams();
        synchronized (pendingEvents) {
            pendingEvents.removeAllElements();
        }
        postState(IrcStrings.get(IrcStrings.STATE_DISCONNECTED));
    }

    public synchronized boolean isConnected() {
        return running && state == CONNECTED;
    }

    public boolean hasUsableProfile() {
        IrcProfile profile = settings.getActiveProfile();
        return profile != null && profile.isUsable();
    }

    public boolean send(String line) {
        return sendRaw(IrcText.clean(line));
    }

    // Lanza una accion guardada: comando si empieza por '/', texto si no.
    public void runAction(String value) {
        String text = IrcText.trim(value);
        if (text.length() == 0) {
            return;
        }
        if (text.charAt(0) == '/') {
            processCommand(text.substring(1));
        } else {
            submitText(text);
        }
    }

    public void submitText(String value) {
        String text = IrcText.trim(value);
        if (text.length() == 0) {
            return;
        }
        if (text.charAt(0) == '/') {
            processCommand(text.substring(1));
            return;
        }
        IrcWindow window;
        synchronized (this) {
            window = activeWindow;
        }
        if (window == null || window.isServer()) {
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_NO_WINDOW)));
            return;
        }
        if (window.isChannel() && !window.isJoined()) {
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_NOT_IN_CHANNEL)));
            return;
        }
        if (send("PRIVMSG " + window.getName() + " :" + IrcText.clean(text))) {
            addToWindow(window, IrcMessage.chat(getCurrentNick(), text,
                nickColorFor(nickStatusIn(window, getCurrentNick()))));
        } else {
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_SEND_OFFLINE)));
        }
    }

    public void requestJoin(String value) {
        String[] parts = IrcText.split(value, ' ');
        String channel = normalizeChannel(parts.length > 0 ? parts[0] : "");
        String key = parts.length > 1 ? IrcText.clean(parts[1]) : "";
        if (channel.length() == 0) {
            return;
        }
        boolean refused = false;
        IrcWindow window;
        synchronized (this) {
            window = findWindow(IrcWindow.CHANNEL, channel);
            if (window == null) {
                IrcWindow created = new IrcWindow(IrcWindow.CHANNEL, channel,
                    settings.getHistoryLimit());
                if (addWindow(created)) {
                    window = created;
                } else {
                    refused = true;
                    window = null;
                }
            }
            if (window != null) {
                activeWindow = window;
                window.markRead();
                // Reentrada explicita: cancela un cierre en curso para que
                // el eco del PART antiguo no la borre tras el nuevo JOIN.
                window.setClosing(false);
            }
        }
        if (refused) {
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_MAX_WINDOWS)));
            repaint();
            return;
        }
        if (!send("JOIN " + IrcText.clean(channel)
            + (key.length() > 0 ? " " + key : ""))) {
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_JOIN_OFFLINE)));
        }
        repaint();
    }

    // Topic y modos cacheados de un canal (marquesina de ventanas).
    public synchronized String getTopicOf(String channel) {
        IrcWindow window = findWindow(IrcWindow.CHANNEL, channel);
        return window == null ? "" : window.getTopic();
    }

    public synchronized String getModesOf(String channel) {
        IrcWindow window = findWindow(IrcWindow.CHANNEL, channel);
        return window == null ? "" : window.getModes();
    }

    // Pide topic y modos frescos al servidor (responde 332/331 y 324).
    public void requestTopicModes(String channel) {
        String chan = IrcText.clean(channel);
        if (chan.length() == 0) {
            return;
        }
        send("MODE " + chan);
        send("TOPIC " + chan);
    }

    public void requestNames() {
        IrcWindow window = getActiveWindow();
        if (window == null || !window.isChannel() || !window.isJoined()) {
            return;
        }
        // Si ya hay nicks, no se recarga: la lista se mantiene al dia
        // sola con los eventos JOIN/PART/QUIT/NICK/MODE en directo. Solo
        // se pide entera cuando esta vacia (primer JOIN, reconexion).
        if (window.getNickCount() > 0) {
            return;
        }
        window.clearNicks();
        if (!send("NAMES " + IrcText.clean(window.getName()))) {
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_NAMES_OFFLINE)));
        }
    }

    private IrcWindow openTarget(String value) {
        String target = IrcText.trim(value);
        if (target.length() > 0 && (target.charAt(0) == '#' || target.charAt(0) == '&')) {
            boolean refused = false;
            IrcWindow window;
            synchronized (this) {
                window = findWindow(IrcWindow.CHANNEL, target);
                if (window == null) {
                    IrcWindow created = new IrcWindow(IrcWindow.CHANNEL, target,
                        settings.getHistoryLimit());
                    if (addWindow(created)) {
                        window = created;
                    } else {
                        refused = true;
                        window = null;
                    }
                }
                if (window != null) {
                    activeWindow = window;
                    window.markRead();
                    repaint();
                }
            }
            if (refused) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_MAX_WINDOWS)));
                return null;
            }
            return window;
        }
        return openQuery(target);
    }

    public IrcWindow openQuery(String value) {
        String nick = stripPrefixes(IrcText.trim(value));
        if (nick.length() == 0) {
            return null;
        }
        boolean refused = false;
        IrcWindow window;
        synchronized (this) {
            window = findWindow(IrcWindow.QUERY, nick);
            if (window == null) {
                IrcWindow created = new IrcWindow(IrcWindow.QUERY, nick,
                    settings.getHistoryLimit());
                if (addWindow(created)) {
                    window = created;
                } else {
                    refused = true;
                    window = null;
                }
            }
            if (window != null) {
                activeWindow = window;
                window.markRead();
                repaint();
            }
        }
        if (refused) {
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_MAX_WINDOWS)));
            return null;
        }
        return window;
    }

    public void clearActive() {
        IrcWindow window = getActiveWindow();
        if (window != null) {
            window.clearMessages();
            repaint();
        }
    }

    // Cierra una ventana. Si es un canal del que aun formas parte, manda
    // PART primero pero NO borra todavia: la ventana queda en amarillo
    // (saliendo) y se borra al llegar el eco del PART del servidor. Asi
    // los mensajes rezagados caen en ella de forma normal y no hay
    // efecto zombi. La ventana del servidor no se puede cerrar.
    public void closeWindow(IrcWindow window) {
        closeWindow(window, null);
    }

    public void closeWindow(IrcWindow window, String reason) {
        boolean known;
        boolean server;
        boolean channel;
        boolean joined;
        String name;
        synchronized (this) {
            known = window != null && windows.indexOf(window) >= 0;
            server = known && window.isServer();
            channel = known && window.isChannel();
            joined = known && window.isJoined();
            name = known ? window.getName() : "";
        }
        if (!known) {
            return;
        }
        if (server) {
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.NOTICE_STATUS_CLOSE)));
            return;
        }
        if (channel && joined) {
            String partLine = "PART " + IrcText.clean(name);
            String motive = IrcText.trim(reason == null ? "" : reason);
            if (motive.length() > 0) {
                partLine = partLine + " :" + IrcText.clean(motive);
            }
            if (send(partLine)) {
                synchronized (this) {
                    window.setJoined(false);
                    window.setClosing(true);
                }
                scheduleCloseFallback(window);
                repaint();
                return;
            }
            // Sin conexion no hay eco que esperar: borrado inmediato.
        }
        synchronized (this) {
            window.setClosing(false);
            int closedIndex = windows.indexOf(window);
            windows.removeElement(window);
            if (activeWindow == window) {
                // Foco a la inmediata izquierda (orden de la tira y de las
                // teclas Izquierda/Derecha); Status si era la primera.
                IrcWindow left = null;
                if (closedIndex > 0 && closedIndex - 1 < windows.size()) {
                    left = (IrcWindow) windows.elementAt(closedIndex - 1);
                }
                activeWindow = left != null ? left : serverWindow;
                activeWindow.markRead();
            }
            updateCapacities();
            rebuildOrderedWindows();
            repaint();
        }
    }

    // Si el eco del PART se pierde (conexion muerta justo al cerrar), la
    // ventana no se queda amarilla para siempre: se borra a los 20 s.
    private void scheduleCloseFallback(final IrcWindow window) {
        try {
            if (repaintTimer == null) {
                repaintTimer = new java.util.Timer();
            }
            repaintTimer.schedule(new CloseFallback(window), 20000);
        } catch (Exception e) {
            // Sin temporizador: el eco o un segundo cierre la borrara.
        }
    }

    private final class CloseFallback extends java.util.TimerTask {
        private final IrcWindow window;

        CloseFallback(IrcWindow value) {
            window = value;
        }

        public void run() {
            try {
                boolean pending;
                synchronized (IrcClient.this) {
                    pending = windows.indexOf(window) >= 0 && window.isClosing();
                }
                if (pending) {
                    // joined=false: borrado inmediato sin reenviar PART.
                    closeWindow(window);
                }
            } catch (Exception e) {
                // Nunca matar el hilo del Timer por una excepcion.
            }
        }
    }

    public int getJoinMode() {
        return settings.getJoinMode();
    }

    public int getFontSizeConstant() {
        return settings.getFontSizeConstant();
    }

    public boolean isCompactLeading() {
        return settings.isCompactLeading();
    }

    public boolean isNickBold() {
        return settings.isNickBold();
    }

    public int getBarMargin() {
        return settings.getBarMargin();
    }

    public int getFontSize() {
        return settings.getFontSize();
    }

    public int getWinIndicator() {
        return settings.getWinIndicator();
    }

    // Scroll siempre visible (opcion eliminada).
    public boolean getScrollBar() {
        return true;
    }

    // Fondo siempre negro (opcion eliminada).
    public int getBgColor() {
        return IrcSettings.BG_RGB_BLACK;
    }

    // Siempre individual: cada ventana (canal, Status, privados) guarda
    // hasta el limite completo. Sin reparto compartido.
    private synchronized void updateCapacities() {
        int limit = settings.getHistoryLimit();
        int i = 0;
        while (i < windows.size()) {
            ((IrcWindow) windows.elementAt(i)).setCapacity(limit);
            i++;
        }
    }

    public synchronized int getChanEntryCount() {
        return chanEntries.size();
    }

    public synchronized String[] getChanEntryAt(int index) {
        if (index < 0 || index >= chanEntries.size()) {
            return null;
        }
        return (String[]) chanEntries.elementAt(index);
    }

    public boolean isListingChannels() {
        return listingChannels;
    }

    public synchronized int getChanTotalCount() {
        return chanTotal;
    }

    public int getChanLimit() {
        return settings.getChanLimit();
    }

    // Al cerrar la pantalla se sueltan las entradas (~30 KB): la lista
    // siempre se vuelve a pedir antes de abrirse, nunca se muestra vacia.
    public void requestChanList() {
        requestChanList("");
    }

    // Con filtro (p. ej. "/list linux") se pide la lista completa y se
    // filtra en el movil por subcadena: muchos servidores (UnrealIRCd)
    // no aceptan mascaras y responden 403 sin lista, dejando la pantalla
    // clavada en "Buscando...". Sin filtro, lista completa.
    public void requestChanList(String filter) {
        synchronized (this) {
            chanEntries.removeAllElements();
            chanTotal = 0;
            listingChannels = true;
            chanFilter = IrcText.ircLower(IrcText.trim(filter));
        }
        if (!send("LIST")) {
            synchronized (this) {
                listingChannels = false;
            }
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_LIST_OFFLINE)));
        } else {
            scheduleListWatchdog();
        }
        repaint();
    }

    // Si el servidor silencia el LIST (antiflood por pedirlos seguidos),
    // desclava la pantalla en vez de dejar "Buscando..." para siempre.
    private void scheduleListWatchdog() {
        try {
            if (repaintTimer == null) {
                repaintTimer = new java.util.Timer();
            }
            repaintTimer.schedule(new ListWatchdog(), 30000);
        } catch (Exception e) {
            // Sin temporizador: la pantalla se actualiza al tocar teclas.
        }
    }

    private final class ListWatchdog extends java.util.TimerTask {
        public void run() {
            try {
                boolean stuck;
                synchronized (IrcClient.this) {
                    stuck = listingChannels && chanTotal == 0;
                    if (stuck) {
                        listingChannels = false;
                    }
                }
                if (stuck) {
                    addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_LIST_NORESPONSE)));
                    doRepaint();
                }
            } catch (Exception e) {
                // Nunca matar el hilo del Timer por una excepcion.
            }
        }
    }

    // Los NOTICE dirigidos a una persona (servicios, avisos del servidor,
    // respuestas CTCP) van a la ventana del servidor, no abren privados.
    // Solo los NOTICE a un canal van a ese canal (ver handleEvent).
    // Guarda el top por usuarios sin pasar del limite: si esta lleno y
    // el nuevo supera al minimo guardado, lo sustituye. Sin topics:
    // solo nombre y usuarios para ir mas rapido y gastar menos.
    private synchronized void insertChanEntry(String channel, String users) {
        if (chanFilter.length() > 0
            && IrcText.ircLower(channel).indexOf(chanFilter) < 0) {
            return;
        }
        insertChanTop(chanEntries, new String[] { channel, users }, settings.getChanLimit());
    }

    static void insertChanTop(Vector entries, String[] entry, int limit) {
        if (entries.size() >= limit) {
            return;
        }
        sortedInsertChan(entries, entry);
    }

    static void sortedInsertChan(Vector entries, String[] entry) {
        int users = chanUsers(entry);
        int low = 0;
        int high = entries.size();
        while (low < high) {
            int mid = (low + high) / 2;
            if (chanUsers((String[]) entries.elementAt(mid)) < users) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        entries.insertElementAt(entry, low);
    }

    // Solo un hilo drena a la vez (red o paint): el otro vuelve y lo
    // coge en la siguiente pasada. Asi los lotes salen en orden FIFO
    // y un mensaje no se pinta antes que su JOIN.
    private boolean drainingEvents;

    public void processPendingEvents() {
        Event[] events;
        synchronized (pendingEvents) {
            if (drainingEvents || pendingEvents.size() == 0) {
                return;
            }
            drainingEvents = true;
            events = new Event[pendingEvents.size()];
            int i = 0;
            while (i < pendingEvents.size()) {
                events[i] = (Event) pendingEvents.elementAt(i);
                i++;
            }
            pendingEvents.removeAllElements();
        }
        try {
            int i = 0;
            while (i < events.length) {
                // Por evento: si uno lanza se sigue con los siguientes y
                // no se pierde el lote entero.
                try {
                    handleEvent(events[i]);
                } catch (Throwable bad) {
                }
                i++;
            }
        } finally {
            synchronized (pendingEvents) {
                drainingEvents = false;
            }
        }
    }

    private void runConnection(int id) {
        final int fid = id;
        SocketConnection localSocket = null;
        String failure = null;
        IrcProfile profile = connectProfile;
        if (profile == null) {
            return;
        }
        try {
            localSocket = (SocketConnection) Connector.open("socket://" + profile.getHost() + ":"
                + profile.getPort(), Connector.READ_WRITE, true);
            try {
                localSocket.setSocketOption(SocketConnection.KEEPALIVE, 1);
            } catch (Exception ignored) {
                // No todos los moviles lo soportan: se ignora.
            }
            synchronized (this) {
                if (stopping || id != connectionId) {
                    localSocket.close();
                    return;
                }
                socket = localSocket;
                input = localSocket.openInputStream();
                output = localSocket.openOutputStream();
                running = true;
                state = CONNECTED;
                lastTraffic = System.currentTimeMillis();
            }
            postState(IrcStrings.get(IrcStrings.STATE_CONNECTED_PRE) + currentNick);
            Thread watch = new Thread(new Runnable() {
                public void run() {
                    watchSilence(fid);
                }
            });
            watch.start();
            String password = profile.getPassword();
            if (password.length() > 0) {
                send("PASS " + IrcText.clean(password));
            }
            send("NICK " + IrcText.clean(currentNick));
            send("USER " + IrcText.clean(profile.getUser()) + " 0 * :"
                + IrcText.clean(profile.getRealName()));
            readLoop(input, id);
        } catch (Exception exception) {
            boolean current;
            boolean silent;
            synchronized (this) {
                current = id == connectionId;
                silent = silenceDropped;
                silenceDropped = false;
            }
            if (current && !isStopping()) {
                failure = silent ? IrcStrings.get(IrcStrings.ERR_NO_RESPONSE) : friendlyError(exception);
            }
        } finally {
            boolean current;
            boolean expected;
            synchronized (this) {
                current = id == connectionId;
                expected = current && stopping;
                if (current) {
                    running = false;
                    if (!expected) {
                        state = DISCONNECTED;
                    }
                }
            }
            if (current) {
                closeStreams();
            } else if (localSocket != null) {
                try {
                    localSocket.close();
                } catch (IOException exception) {
                    // Socket obsoleto (otra conexion gano): cierre silencioso.
                }
            }
            if (current && !expected) {
                boolean wantRetry;
                synchronized (this) {
                    wantRetry = settings.getReconnectMode() == IrcSettings.RECONNECT_ON
                        && retryCount < MAX_RETRIES;
                }
                if (wantRetry) {
                    addServerMessage(IrcMessage.error(failure == null ? IrcStrings.get(IrcStrings.ERR_CONN_LOST) : failure));
                    startRetryThread();
                } else {
                    synchronized (this) {
                        retryCount = 0;
                    }
                    addServerMessage(IrcMessage.error(failure == null ? IrcStrings.get(IrcStrings.ERR_CONN_LOST) : failure));
                    postState(IrcStrings.get(IrcStrings.STATE_DISCONNECTED));
                }
            }
        }
    }

    // Arranca la espera del siguiente reintento en un hilo aparte para
    // no bloquear ni la UI ni el hilo de red (que ya termino).
    private void startRetryThread() {
        final int attempt;
        final int gen;
        synchronized (this) {
            retryCount++;
            attempt = retryCount;
            gen = connectionId;
        }
        addServerMessage(IrcMessage.system(IrcStrings.get(IrcStrings.REC_PREFIX) + attempt + "/" + MAX_RETRIES + IrcStrings.get(IrcStrings.REC_SUFFIX)));
        Thread retry = new Thread(new Runnable() {
            public void run() {
                retryWaitAndConnect(gen, attempt);
            }
        });
        retry.start();
    }

    private void retryWaitAndConnect(int gen, int attempt) {
        int delay = retryDelayMs(attempt);
        int waited = 0;
        while (waited < delay) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            waited += 1000;
            synchronized (this) {
                if (gen != connectionId || stopping) {
                    return;
                }
            }
        }
        boolean go;
        synchronized (this) {
            go = gen == connectionId && !stopping
                && settings.getReconnectMode() == IrcSettings.RECONNECT_ON
                && hasUsableProfile();
        }
        if (!go) {
            return;
        }
        connectInternal(false);
    }

    // Vigila que el servidor hable (PINGs): si lleva demasiado callado,
    // la conexion murio a medias y el read() no se enteraria nunca.
    private void watchSilence(int id) {
        while (true) {
            int slices = 0;
            while (slices < 6) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                slices++;
                synchronized (this) {
                    if (id != connectionId || stopping || !running) {
                        return;
                    }
                }
            }

            long msecsSinceTraffic;
            synchronized (this) {
                msecsSinceTraffic = System.currentTimeMillis() - lastTraffic;
            }

            // --- REFUERZO GPRS: KEEP-ALIVE ACTIVO (fuera del monitor:
            // sendRaw hace I/O bloqueante y no debe congelar la UI) ---
            if (msecsSinceTraffic > 90000 && msecsSinceTraffic < SILENCE_LIMIT_MS) {
                boolean sendPing;
                String nick;
                synchronized (this) {
                    sendPing = running && !stopping;
                    nick = currentNick;
                }
                if (sendPing) {
                    sendRaw("PING " + nick);
                }
            }

            // --- DISPARADOR DE CAÍDA POR SILENCIO ---
            boolean silent;
            synchronized (this) {
                silent = id == connectionId && running && !stopping
                    && System.currentTimeMillis() - lastTraffic > SILENCE_LIMIT_MS;
                if (silent) {
                    silenceDropped = true;
                }
            }
            if (silent) {
                // Rompe el read() bloqueado: el finally hara el resto.
                closeStreams();
                return;
            }
        }
    }

    private void readLoop(InputStream stream, int id) throws IOException {
        byte[] chunk = new byte[512];
        byte[] line = new byte[1024];
        int lineLength = 0;
        while (running && isCurrentConnection(id)) {
            int count = stream.read(chunk);
            if (count < 0) {
                return;
            }
            lastTraffic = System.currentTimeMillis();
            int i = 0;
            while (i < count) {
                int value = chunk[i] & 255;
                if (value == 10) {
                    if (lineLength > 0 && isCurrentConnection(id)) {
                        handleLine(IrcText.decode(line, 0, lineLength));
                        // Procesar aqui tambien para que los avisos suenen
                        // aunque la aplicacion este minimizada sin repintar.
                        processPendingEvents();
                    }
                    lineLength = 0;
                } else if (value != 13) {
                    if (lineLength < line.length) {
                        line[lineLength++] = (byte) value;
                    } else {
                        lineLength = 0;
                        addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_LINE_LONG)));
                    }
                }
                i++;
            }
        }
    }

    private void handleLine(String line) {
        IrcProtocol message = IrcProtocol.parse(line);
        if (message == null) {
            return;
        }
        if (message.is("PING")) {
            String token = message.getText();
            if (token == null || token.length() == 0) {
                token = message.getParam(0);
            }
            if (token.length() > 0) {
                if (line.indexOf(" :") >= 0) {
                    sendRaw("PONG :" + IrcText.clean(token));
                } else {
                    sendRaw("PONG " + IrcText.clean(token));
                }
            } else {
                sendRaw("PONG");
            }
            return;
        }
        if (message.is("PRIVMSG")) {
            if (isIgnored(message.getNick())) {
                return;
            }
            handleIncomingText(message, false);
            return;
        }
        if (message.is("NOTICE")) {
            if (isIgnored(message.getNick())) {
                return;
            }
            handleIncomingText(message, true);
            return;
        }
        if (message.is("JOIN")) {
            postEvent(JOIN_EVENT, joinChannelOf(message), message.getNick(), message.getText(), false);
            return;
        }
        if (message.is("PART")) {
            postEvent(PART_EVENT, joinChannelOf(message), message.getNick(), message.getText(), false);
            return;
        }
        if (message.is("QUIT")) {
            postEvent(QUIT_EVENT, "", message.getNick(), message.getText(), false);
            return;
        }
        if (message.is("NICK")) {
            postEvent(NICK_EVENT, "", message.getNick(), nickChangeOf(message), false);
            return;
        }
        if (message.is("KICK")) {
            Event event = createEvent(KICK_EVENT);
            event.first = message.getParam(0);
            event.second = message.getParam(1);
            event.text = message.getText();
            event.source = message.getNick();
            postEvent(event);
            return;
        }
        if (message.is("TOPIC")) {
            postEvent(TOPIC_EVENT, message.getParam(0), message.getNick(), message.getText(), false);
            return;
        }
        if (message.is("MODE")) {
            postEvent(MODE_EVENT, message.getParam(0), message.getNick(), modeFlagsOf(message),
                false);
            return;
        }
        if (message.isNumeric()) {
            handleNumeric(message);
            return;
        }
        if (message.is("ERROR")) {
            addServerMessage(IrcMessage.error(message.getText()));
        }
    }

    private void handleIncomingText(IrcProtocol message, boolean notice) {
        String target = message.getParam(0);
        String source = message.getNick();
        String text = message.getText();
        if (target.length() == 0 || text.length() == 0) {
            return;
        }
        if (!notice && text.charAt(0) == 1 && !IrcText.isAction(text)) {
            addServerMessage(IrcMessage.notice(source, "CTCP " + stripCtcp(text)));
            return;
        }
        Event event = createEvent(notice ? NOTICE_EVENT : CHAT_EVENT);
        event.first = target;
        event.source = source;
        event.text = IrcText.isAction(text) ? IrcText.actionText(text) : IrcText.clean(text);
        event.flag = IrcText.isAction(text);
        postEvent(event);
    }

    private void handleNumeric(IrcProtocol message) {
        String code = message.getCommand();
        if (code.equals(RPL_WELCOME)) {
            String welcomeNick = message.getParam(0);
            synchronized (this) {
                if (welcomeNick.length() > 0) {
                    currentNick = welcomeNick;
                }
                // Registro completado: desde aqui el nick solo cambia por
                // peticion del usuario (eco NICK del servidor).
                welcomed = true;
                nickAttempts = 0;
                // Racha superada: la proxima caida reintenta desde cero.
                retryCount = 0;
            }
            postState(IrcStrings.get(IrcStrings.STATE_CONNECTED_PRE) + currentNick);
            Vector autoJoin = new Vector();
            synchronized (this) {
                boolean fromProfile = profileJoin;
                profileJoin = false;
                if (!fromProfile) {
                    int i = 0;
                    while (i < sessionChans.size()) {
                        autoJoin.addElement(sessionChans.elementAt(i));
                        i++;
                    }
                    if (autoJoin.size() == 0 && !hasChannelWindow()) {
                        // Fresco sin sesion: como si viniera del perfil.
                        fromProfile = true;
                    }
                }
                if (fromProfile) {
                    IrcProfile autoProfile = connectProfile;
                    String[] channels = autoProfile == null ? new String[0]
                        : autoProfile.getChannelArray();
                    int i = 0;
                    while (i < channels.length) {
                        autoJoin.addElement(channels[i]);
                        i++;
                    }
                }
            }
            int i = 0;
            while (i < autoJoin.size()) {
                send("JOIN " + normalizeChannel((String) autoJoin.elementAt(i)));
                i++;
            }
            return;
        }
        if (code.equals(RPL_NAMREPLY)) {
            Event event = createEvent(NAMES_EVENT);
            event.first = message.getParam(2);
            event.text = message.getText();
            if (event.text.length() == 0) {
                event.text = message.getParam(3);
            }
            postEvent(event);
            return;
        }
        if (code.equals(ERR_NICKNAMEINUSE) || code.equals(ERR_ERRONEUSNICK)) {
            boolean invalid = code.equals(ERR_ERRONEUSNICK);
            // "433 <tu nick|*> <nick pedido> :..." -> param 1 es el pedido.
            String wanted = message.getParam(1);
            String retryNick = null;
            boolean disconnectAfter = false;
            boolean registered;
            synchronized (this) {
                registered = welcomed;
                if (!registered) {
                    if (invalid) {
                        nickShort = true;
                    }
                    if (nickAttempts < MAX_NICK_ATTEMPTS) {
                        nickAttempts++;
                        // Aun sin 001: currentNick es solo el intento en
                        // curso; el 001 fijara el nick real asignado.
                        currentNick = nickCandidate(baseNick, nickAttempts, nickShort,
                            NICK_RANDOM);
                        retryNick = currentNick;
                    } else {
                        disconnectAfter = true;
                    }
                }
            }
            if (registered) {
                // Ya conectado: no se toca el nick ni se reintenta nada.
                if (wanted.length() == 0) {
                    wanted = "?";
                }
                addServerMessage(IrcMessage.error(IrcStrings.get(invalid
                    ? IrcStrings.ERR_NICK_INVALID_PRE : IrcStrings.ERR_NICK_IN_USE_PRE) + wanted));
                return;
            }
            // Fuera del monitor se manda NICK (send() hace I/O bloqueante).
            if (retryNick != null) {
                send("NICK " + IrcText.clean(retryNick));
            }
            if (disconnectAfter) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_NICK_TAKEN)));
                disconnect();
            }
            return;
        }
        if (code.equals(RPL_TOPIC)) {
            postEvent(TOPIC_EVENT, message.getParam(1), "", message.getText(), false);
            return;
        }
        if (code.equals(RPL_CHANNELMODEIS)) {
            // "324 nick #canal +modos": cache para la marquesina.
            IrcWindow window;
            synchronized (this) {
                window = findWindow(IrcWindow.CHANNEL, message.getParam(1));
                if (window != null) {
                    window.setModes(message.getParam(2));
                }
            }
            repaint();
            return;
        }
        if (code.equals(RPL_NOTOPIC)) {
            IrcWindow window;
            synchronized (this) {
                window = findWindow(IrcWindow.CHANNEL, message.getParam(1));
                if (window != null) {
                    window.setTopic("");
                }
            }
            repaint();
            return;
        }
        if (code.equals(RPL_LISTSTART)) {
            synchronized (this) {
                chanEntries.removeAllElements();
                chanTotal = 0;
                listingChannels = true;
            }
            repaint();
            return;
        }
        if (code.equals(RPL_LIST)) {
            // Contador vivo: el total cuenta todo lo recibido (aunque se
            // descarte) para dar feedback de que el socket sigue ocupado.
            // Solo se guarda hasta el limite para no saturar el movil.
            boolean store;
            synchronized (this) {
                chanTotal++;
                store = chanEntries.size() < settings.getChanLimit();
            }
            if (store) {
                Event event = createEvent(LIST_EVENT);
                event.first = message.getParam(1);
                event.source = message.getParam(2);
                postEvent(event);
            } else {
                repaint();
            }
            return;
        }
        if (code.equals(RPL_LISTEND)) {
            postEvent(createEvent(LIST_END_EVENT));
            return;
        }
        if (code.equals("311") || code.equals("312") || code.equals("313")
            || code.equals("317") || code.equals("318") || code.equals("319")) {
            Event event = createEvent(WHOIS_EVENT);
            event.first = message.getParam(1);
            event.text = whoisText(code, message);
            postEvent(event);
            return;
        }
        if (code.equals(RPL_BANLIST)) {
            Event event = createEvent(BAN_EVENT);
            event.first = message.getParam(1);
            event.text = message.getParam(2);
            postEvent(event);
            return;
        }
        if (code.equals(RPL_ENDOFBANLIST)) {
            postEvent(createEvent(BAN_END_EVENT));
            return;
        }
        if (code.equals(RPL_ENDOFNAMES) || code.equals(RPL_MOTDSTART) || code.equals(RPL_MOTD)) {
            return;
        }
        if (code.equals(RPL_ENDOFMOTD) || code.equals(ERR_NOMOTD) || code.equals(ERR_PASSWDMISMATCH)) {
            if (message.getText().length() > 0) {
                addServerMessage(IrcMessage.notice(IrcStrings.get(IrcStrings.NOTICE_SERVER), message.getText()));
            }
            return;
        }
        // Errores de JOIN: cierra la ventana del canal si no se entro.
        if (isJoinError(code)) {
            String channel = message.getParam(1);
            if (channel.length() > 0) {
                closeFailedJoin(normalizeChannel(channel));
            }
            String detail = buildNumericInfo(message);
            addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_CANNOT_JOIN_PRE)
                + (channel.length() > 0 ? channel : "?") + (detail.length() > 0 ? ": " + detail : "")));
            return;
        }
        String detail = buildNumericInfo(message);
        if (code.equals("005")) {
            parse005(message);
        }
        if (detail.length() > 0) {
            if (code.equals("329") || code.equals("333")) {
                detail = withReadableDate(detail);
            }
            String title = numericTitle(code);
            if (title.length() > 0) {
                addServerMessage(IrcMessage.notice(code + "] [" + title, detail));
            } else {
                addServerMessage(IrcMessage.notice(code, detail));
            }
        }
    }

    // Sustituye un epoch final (segundos) por fecha "17 Feb 2021" (mes en
    // ingles, legible para todos). Solo 329/333; si no hay epoch valido
    // se deja el texto intacto.
    static String withReadableDate(String detail) {
        int end = detail.length();
        int start = end;
        while (start > 0 && detail.charAt(start - 1) >= '0' && detail.charAt(start - 1) <= '9') {
            start--;
        }
        if (end - start < 9 || end - start > 10 || (start > 0 && detail.charAt(start - 1) != ' ')) {
            return detail;
        }
        String date = epochToDate(detail.substring(start, end));
        if (date.length() == 0) {
            return detail;
        }
        return detail.substring(0, start) + date;
    }

    static String epochToDate(String seconds) {
        long value = 0;
        int i = 0;
        while (i < seconds.length()) {
            char c = seconds.charAt(i);
            if (c < '0' || c > '9') {
                return "";
            }
            value = value * 10 + (c - '0');
            if (value > 4102444800L) {
                return "";
            }
            i++;
        }
        if (value <= 0) {
            return "";
        }
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTime(new java.util.Date(value * 1000));
        int day = calendar.get(java.util.Calendar.DAY_OF_MONTH);
        int month = calendar.get(java.util.Calendar.MONTH);
        int year = calendar.get(java.util.Calendar.YEAR);
        String[] months = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
        if (month < 0 || month > 11) {
            return "";
        }
        return day + " " + months[month] + " " + year;
    }

    static boolean isJoinError(String code) {
        return code.equals("403") || code.equals("404") || code.equals("410")
            || code.equals("437") || code.equals("465") || code.equals("471")
            || code.equals("473") || code.equals("474") || code.equals("475")
            || code.equals("476") || code.equals("477") || code.equals("479");
    }

    // Traduccion corta de los numericos mas vistos (unos 800 B en
    // literales). Solo se consulta en la rama generica (rara): coste cero
    // en caliente. Lo no listado sigue en crudo como antes.
    static String numericTitle(String code) {
        if (code.equals("002")) {
            return IrcStrings.get(IrcStrings.T_002);
        }
        if (code.equals("003")) {
            return IrcStrings.get(IrcStrings.T_003);
        }
        if (code.equals("004")) {
            return IrcStrings.get(IrcStrings.T_004);
        }
        if (code.equals("005")) {
            return IrcStrings.get(IrcStrings.T_005);
        }
        if (code.equals("251")) {
            return IrcStrings.get(IrcStrings.T_251);
        }
        if (code.equals("252")) {
            return IrcStrings.get(IrcStrings.T_252);
        }
        if (code.equals("253")) {
            return IrcStrings.get(IrcStrings.T_253);
        }
        if (code.equals("254")) {
            return IrcStrings.get(IrcStrings.T_254);
        }
        if (code.equals("255")) {
            return IrcStrings.get(IrcStrings.T_255);
        }
        if (code.equals("265")) {
            return IrcStrings.get(IrcStrings.T_265);
        }
        if (code.equals("266")) {
            return IrcStrings.get(IrcStrings.T_266);
        }
        if (code.equals("329")) {
            return IrcStrings.get(IrcStrings.T_329);
        }
        if (code.equals("333")) {
            return IrcStrings.get(IrcStrings.T_333);
        }
        if (code.equals("341")) {
            return IrcStrings.get(IrcStrings.T_341);
        }
        if (code.equals("352")) {
            return IrcStrings.get(IrcStrings.T_352);
        }
        if (code.equals("315")) {
            return IrcStrings.get(IrcStrings.T_315);
        }
        if (code.equals("401")) {
            return IrcStrings.get(IrcStrings.T_401);
        }
        if (code.equals("402")) {
            return IrcStrings.get(IrcStrings.T_402);
        }
        if (code.equals("405")) {
            return IrcStrings.get(IrcStrings.T_405);
        }
        if (code.equals("406")) {
            return IrcStrings.get(IrcStrings.T_406);
        }
        if (code.equals("421")) {
            return IrcStrings.get(IrcStrings.T_421);
        }
        if (code.equals("441")) {
            return IrcStrings.get(IrcStrings.T_441);
        }
        if (code.equals("442")) {
            return IrcStrings.get(IrcStrings.T_442);
        }
        if (code.equals("472")) {
            return IrcStrings.get(IrcStrings.T_472);
        }
        if (code.equals("474")) {
            return IrcStrings.get(IrcStrings.T_474);
        }
        if (code.equals("478")) {
            return IrcStrings.get(IrcStrings.T_478);
        }
        if (code.equals("481")) {
            return IrcStrings.get(IrcStrings.T_481);
        }
        return "";
    }

    private String buildNumericInfo(IrcProtocol message) {
        StringBuffer info = new StringBuffer();
        int i = 1;
        while (i < message.getParamCount()) {
            if (info.length() > 0) {
                info.append(' ');
            }
            info.append(message.getParam(i));
            i++;
        }
        if (message.getText().length() > 0) {
            if (info.length() > 0) {
                info.append(' ');
            }
            info.append(message.getText());
        }
        return info.toString();
    }

    private void closeFailedJoin(String channel) {
        IrcWindow window = findWindow(IrcWindow.CHANNEL, channel);
        if (window != null && !window.isJoined()) {
            closeWindow(window);
        }
    }

    private synchronized void handleEvent(Event event) {
        if (event.type == STATE_EVENT) {
            addToWindow(serverWindow, IrcMessage.system(event.text));
            return;
        }
        if (event.type == SERVER_EVENT) {
            addToWindow(serverWindow, event.message);
            return;
        }
        if (event.type == CHAT_EVENT || event.type == NOTICE_EVENT) {
            boolean toChannel = event.first.length() > 0
                && (event.first.charAt(0) == '#' || event.first.charAt(0) == '&');
            if (event.type == NOTICE_EVENT && !toChannel) {
                addToWindow(serverWindow, IrcMessage.notice(event.source, event.text));
                return;
            }
            IrcWindow window;
            if (toChannel) {
                window = findWindow(IrcWindow.CHANNEL, event.first);
                if (window == null) {
                    // Canal huerfano (p. ej. mensajes cruzados tras cerrar
                    // con /part): no se recrea la ventana para evitar el
                    // efecto zombi; el texto cae a Status como el flood.
                    addToWindow(serverWindow,
                        IrcMessage.notice(event.first + ":" + event.source, event.text));
                    return;
                }
            } else {
                window = findWindow(IrcWindow.QUERY, event.source);
                if (window == null) {
                    window = new IrcWindow(IrcWindow.QUERY, event.source, settings.getHistoryLimit());
                    if (!addWindow(window)) {
                        window = null;
                    }
                }
            }
            if (window == null) {
                // Anti-flood: al tope de ventanas el texto cae a Status y,
                // junto al mensaje, sale el error en cada caso para que se
                // entienda por que aparece ahi.
                addToWindow(serverWindow, IrcMessage.notice(event.source, event.text));
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_MAX_WINDOWS)));
                return;
            }
            IrcMessage message;
            if (event.type == NOTICE_EVENT) {
                message = IrcMessage.notice(event.source, event.text);
            } else if (event.flag) {
                message = IrcMessage.action(event.source, event.text);
            } else if (mentions(event.text, currentNick)) {
                message = IrcMessage.mention(event.source, event.text,
                    nickColorFor(nickStatusIn(window, event.source)));
            } else {
                message = IrcMessage.chat(event.source, event.text,
                    nickColorFor(nickStatusIn(window, event.source)));
            }
            addToWindow(window, message);
            if (event.type == CHAT_EVENT && shouldAlert(window, event)) {
                IrcCanvas target = canvas;
                if (target != null) {
                    target.playAlert();
                }
            }
            return;
        }
        if (event.type == JOIN_EVENT) {
            IrcWindow window = findWindow(IrcWindow.CHANNEL, event.first);
            if (window == null) {
                window = new IrcWindow(IrcWindow.CHANNEL, event.first, settings.getHistoryLimit());
                if (!addWindow(window)) {
                    return;
                }
            }
            window.addNick(event.source);
            boolean self = IrcText.ircEquals(event.source, currentNick);
            if (self) {
                window.setJoined(true);
                // Entrada confirmada: ya no hay cierre en curso (cubre
                // reentradas por cualquier via, incluido /raw JOIN) y los
                // modos cacheados quedan obsoletos hasta pedirlos.
                window.setClosing(false);
                window.setModes("");
                activeWindow = window;
            }
            if (!isTextOnly() && settings.getJoinMode() != IrcSettings.JOIN_HIDDEN) {
                addToWindow(window, joinMessage(event.source, event.first));
            }
            return;
        }
        if (event.type == PART_EVENT) {
            IrcWindow window = findWindow(IrcWindow.CHANNEL, event.first);
            if (window == null) {
                return;
            }
            boolean self = IrcText.ircEquals(event.source, currentNick);
            window.removeNick(event.source);
            if (self) {
                if (window.isClosing()) {
                    // Confirmada la salida: se borra la ventana amarilla.
                    // closeWindow no reenvia PART (joined ya es false).
                    closeWindow(window);
                } else {
                    window.setJoined(false);
                }
            }
            if (!isTextOnly() && settings.getJoinMode() != IrcSettings.JOIN_HIDDEN) {
                addToWindow(window, partMessage(event.source, event.first, event.text));
            }
            return;
        }
        if (event.type == QUIT_EVENT) {
            int i = 0;
            while (i < windows.size()) {
                IrcWindow window = (IrcWindow) windows.elementAt(i);
                if (window.isChannel() && window.removeNick(event.source)
                    && !isTextOnly() && settings.getJoinMode() != IrcSettings.JOIN_HIDDEN) {
                    addToWindow(window, partMessage(event.source, window.getName(), event.text));
                }
                i++;
            }
            return;
        }
        if (event.type == NICK_EVENT) {
            if (IrcText.ircEquals(event.source, currentNick)) {
                currentNick = event.text;
            }
            int i = 0;
            while (i < windows.size()) {
                IrcWindow window = (IrcWindow) windows.elementAt(i);
                if (window.isChannel() && window.renameNick(event.source, event.text)
                    && !isTextOnly() && settings.getJoinMode() != IrcSettings.JOIN_HIDDEN) {
                    addToWindow(window, nickMessage(event.source, event.text));
                }
                i++;
            }
            return;
        }
        if (event.type == NAMES_EVENT) {
            IrcWindow window = findWindow(IrcWindow.CHANNEL, event.first);
            if (window == null) {
                window = new IrcWindow(IrcWindow.CHANNEL, event.first, settings.getHistoryLimit());
                if (!addWindow(window)) {
                    return;
                }
            }
            window.addNicksBulk(IrcText.split(event.text, ' '));
            return;
        }
        if (event.type == TOPIC_EVENT) {
            IrcWindow window = findWindow(IrcWindow.CHANNEL, event.first);
            if (window == null) {
                return;
            }
            // El texto se cachea siempre (marquesina) sin codigos de
            // formato mIRC (colores, negritas): ocupan y ensucian la
            // cinta. Solo se dibuja si no hay modo solo texto.
            window.setTopic(IrcText.stripCodes(event.text));
            if (!isTextOnly()) {
                addToWindow(window, IrcMessage.topic(event.source, event.text,
                    IrcMessage.COLOR_ACTION));
            }
            return;
        }
        if (event.type == KICK_EVENT) {
            IrcWindow window = findWindow(IrcWindow.CHANNEL, event.first);
            if (window == null) {
                return;
            }
            window.removeNick(event.second);
            if (IrcText.ircEquals(event.second, currentNick)) {
                window.setJoined(false);
            }
            if (!isTextOnly() && settings.getJoinMode() != IrcSettings.JOIN_HIDDEN) {
                addToWindow(window, IrcMessage.kick(event.second, event.text));
            }
            return;
        }
        if (event.type == MODE_EVENT) {
            if (event.first.length() > 0
                && (event.first.charAt(0) == '#' || event.first.charAt(0) == '&')) {
                IrcWindow window = findWindow(IrcWindow.CHANNEL, event.first);
                if (window != null) {
                    applyModeFlags(window, event.text);
                    if (!isTextOnly()) {
                        addToWindow(window, IrcMessage.system(event.source + " " + event.text,
                            IrcMessage.COLOR_ACTION));
                    }
                    return;
                }
            }
            addServerMessage(IrcMessage.system(event.first + " " + event.source + " "
                + event.text, IrcMessage.COLOR_ACTION));
            return;
        }
        if (event.type == LIST_EVENT) {
            if (event.first.length() > 0) {
                insertChanEntry(event.first, event.source);
            }
            return;
        }
        if (event.type == LIST_END_EVENT) {
            listingChannels = false;
            sortChanEntries(chanEntries);
            if (chanList == null) {
                addServerMessage(IrcMessage.system(IrcStrings.get(IrcStrings.CH_LIST_PRE) + chanEntries.size()
                    + IrcStrings.get(IrcStrings.CH_LIST_POST)));
            }
            return;
        }
        if (event.type == WHOIS_EVENT) {
            IrcWindow window = findWindow(IrcWindow.QUERY, event.first);
            if (window == null) {
                addToWindow(serverWindow, IrcMessage.notice(IrcStrings.get(IrcStrings.NOTICE_WHOIS), event.text));
            } else {
                addToWindow(window, IrcMessage.notice(IrcStrings.get(IrcStrings.NOTICE_WHOIS), event.text));
            }
            return;
        }
        if (event.type == BAN_EVENT) {
            synchronized (this) {
                if (event.text.length() > 0 && banEntries.size() < settings.getChanLimit()) {
                    banEntries.addElement(event.text);
                }
            }
            return;
        }
        if (event.type == BAN_END_EVENT) {
            if (banList == null && banEntries.size() > 0) {
                addServerMessage(IrcMessage.system(IrcStrings.get(IrcStrings.BANS_PRE) + banEntries.size()));
            }
        }
    }

    private synchronized void addToWindow(IrcWindow window, IrcMessage message) {
        // Precalculo del wrap solo si se va a pintar ya (ventana activa con
        // canvas visible): el resto lo calcula paint() al mostrarse, que ya
        // tiene ese fallback perezoso. Ahorra mediciones en el hilo de red.
        if (message != null && window == activeWindow && isForeground()) {
            IrcCanvas c = canvas;
            if (c != null) {
                int w = c.getWidth();
                if (w > 4) {
                    message.ensureWrap(w - 4, settings.getFontSizeConstant(),
                        settings.isNickBold());
                }
            }
        }
        window.add(message);
        if (window == activeWindow) {
            window.markRead();
        }
    }

    // Anti-flood configurable (Interfaz > Max. Ventanas, por defecto 10):
    // al llegar al tope no se desaloja nada, se rechaza y el llamante
    // avisa ("Aumenta el máximo de ventanas permitido"). Incluye Status.
    private synchronized boolean addWindow(IrcWindow window) {
        int limit = settings.getMaxWindows();
        if (limit < 1) {
            limit = 1;
        }
        if (windows.size() >= limit) {
            return false;
        }
        insertWindowSorted(window);
        updateCapacities();
        rebuildOrderedWindows();
        return true;
    }

    // El vector vive siempre ordenado igual que la lista de ventanas:
    // Status, canales a-z y privados a-z. Asi la tira, las flechas, las
    // barras y la tecla 0 muestran el mismo orden (antes era el de
    // creacion). Mismo criterio que getOrderedWindows.
    private void insertWindowSorted(IrcWindow window) {
        int rank = windowRank(window);
        int pos = windows.size();
        int i = 0;
        while (i < windows.size()) {
            IrcWindow other = (IrcWindow) windows.elementAt(i);
            int otherRank = windowRank(other);
            if (otherRank > rank
                || (otherRank == rank && compareNames(other.getName(), window.getName()) > 0)) {
                pos = i;
                break;
            }
            i++;
        }
        windows.insertElementAt(window, pos);
    }

    private static int windowRank(IrcWindow window) {
        if (window.isServer()) {
            return 0;
        }
        if (window.isChannel()) {
            return 1;
        }
        return 2;
    }

    private synchronized IrcWindow findWindow(int type, String name) {
        int i = 0;
        while (i < windows.size()) {
            IrcWindow window = (IrcWindow) windows.elementAt(i);
            if (window.getType() == type && IrcText.ircEquals(window.getName(), name)) {
                return window;
            }
            i++;
        }
        return null;
    }

    private void processCommand(String value) {
        String commandLine = IrcText.trim(value);
        int space = commandLine.indexOf(' ');
        String name = IrcText.upper(space < 0 ? commandLine : commandLine.substring(0, space));
        String args = space < 0 ? "" : IrcText.trim(commandLine.substring(space + 1));
        if (name.equals("JOIN")) {
            requestJoin(firstToken(args));
        } else if (name.equals("PART") || name.equals("LEAVE")) {
            IrcWindow window = getActiveWindow();
            if (window != null && (window.isChannel() || window.isQuery())) {
                // Mismo cierre diferido que la tecla # (con motivo).
                closeWindow(window, args);
            }
        } else if (name.equals("CLOSE")) {
            closeWindow(getActiveWindow());
        } else if (name.equals("MSG") || name.equals("QUERY")) {
            String target = firstToken(args);
            String text = removeFirstToken(args);
            if (target.length() == 0) {
                return;
            }
            IrcWindow window = openTarget(target);
            if (window != null && text.length() > 0 && window.isChannel() && !window.isJoined()) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_NOT_IN_CHANNEL)));
            } else if (window != null && text.length() > 0
                && send("PRIVMSG " + window.getName() + " :" + IrcText.clean(text))) {
                addToWindow(window, IrcMessage.chat(getCurrentNick(), text,
                    nickColorFor(nickStatusIn(window, getCurrentNick()))));
            }
        } else if (name.equals("ME")) {
            IrcWindow window = getActiveWindow();
            if (window != null && window.isChannel() && !window.isJoined()) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_NOT_IN_CHANNEL)));
            } else if (window != null && !window.isServer() && args.length() > 0
                && send("PRIVMSG " + window.getName() + " :\u0001ACTION " + IrcText.clean(args) + "\u0001")) {
                addToWindow(window, IrcMessage.action(getCurrentNick(), args));
            }
        } else if (name.equals("NAMES")) {
            if (args.length() > 0) {
                send("NAMES " + IrcText.clean(firstToken(args)));
            } else {
                requestNames();
            }
        } else if (name.equals("LIST")) {
            requestChanList(args);
            // Igual que el menu: abrir la pantalla de la lista, si no los
            // datos llegan pero no se ven en ningun sitio.
            IrcCanvas listScreen = canvas;
            if (listScreen != null) {
                listScreen.openChanList();
            }
        } else if (name.equals("WHOIS") || name.equals("WHO")) {
            String target = firstToken(args);
            if (target.length() == 0) {
                IrcWindow active = getActiveWindow();
                if (active != null && active.isQuery()) {
                    target = active.getName();
                }
            }
            if (target.length() == 0) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.USAGE_WHOIS)));
            } else {
                send("WHOIS " + IrcText.clean(target));
            }
        } else if (name.equals("TOPIC")) {
            String first = firstToken(args);
            String channel;
            String topic;
            if (isChannelName(first)) {
                channel = normalizeChannel(first);
                topic = removeFirstToken(args);
            } else {
                channel = activeChannelName();
                topic = args;
            }
            if (channel.length() == 0) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.USAGE_TOPIC)));
            } else if (!send(topicLine(IrcText.clean(channel), IrcText.clean(topic)))) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_SEND_OFFLINE)));
            }
        } else if (name.equals("KICK")) {
            String channel = activeChannelName();
            String nick = firstToken(args);
            if (channel.length() == 0 || nick.length() == 0) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.USAGE_KICK)));
            } else if (!send(kickLine(IrcText.clean(channel), IrcText.clean(nick),
                IrcText.clean(removeFirstToken(args))))) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_SEND_OFFLINE)));
            }
        } else if (name.equals("BAN")) {
            String[] parts = IrcText.split(args, ' ');
            String channel = activeChannelName();
            if (parts.length > 1 && isChannelName(parts[1])) {
                channel = normalizeChannel(parts[1]);
            }
            if (parts.length == 0 || channel.length() == 0) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.USAGE_BAN)));
            } else if (!send(banLine(IrcText.clean(channel),
                IrcText.clean(banMask(parts[0]))))) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_SEND_OFFLINE)));
            }
        } else if (name.equals("INVITE")) {
            String[] parts = IrcText.split(args, ' ');
            String channel = activeChannelName();
            if (parts.length > 1 && isChannelName(parts[1])) {
                channel = normalizeChannel(parts[1]);
            }
            if (parts.length == 0 || channel.length() == 0) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.USAGE_INVITE)));
            } else if (!send(inviteLine(IrcText.clean(parts[0]), IrcText.clean(channel)))) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_SEND_OFFLINE)));
            }
        } else if (name.equals("MODE")) {
            if (args.length() == 0) {
                String channel = activeChannelName();
                if (channel.length() == 0) {
                    addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.USAGE_MODE)));
                } else if (!send(modeLine(IrcText.clean(channel)))) {
                    addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_SEND_OFFLINE)));
                }
            } else if (!send(modeLine(IrcText.clean(args)))) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_SEND_OFFLINE)));
            }
        } else if (name.equals("NICK")) {
            if (args.length() > 0) {
                send("NICK " + IrcText.clean(firstToken(args)));
            }
        } else if (name.equals("QUIT")) {
            disconnect(args);
        } else if (name.equals("RAW")) {
            if (args.length() > 0) {
                send(args);
            }
        } else if (name.equals("CLEAR")) {
            clearActive();
        } else if (name.equals("CONNECT")) {
            connect();
        } else if (name.equals("DISCONNECT")) {
            disconnect();
        } else if (name.equals("HELP")) {
            addServerMessage(IrcMessage.system(IrcStrings.get(IrcStrings.HELP_L1)));
            addServerMessage(IrcMessage.system(IrcStrings.get(IrcStrings.HELP_L2)));
            addServerMessage(IrcMessage.system(IrcStrings.get(IrcStrings.HELP_L3)));
            addServerMessage(IrcMessage.system(IrcStrings.get(IrcStrings.HELP_L4)));
            addServerMessage(IrcMessage.system(IrcStrings.get(IrcStrings.HELP_L5)));
        } else {
            // Cualquier otro /comando se manda tal cual al servidor sin
            // programarlo uno por uno (oper, wallops, motd, admin...).
            // El servidor responde o devuelve su propio error numerico.
            if (commandLine.length() == 0) {
                return;
            }
            if (!send(commandLine)) {
                addServerMessage(IrcMessage.error(IrcStrings.get(IrcStrings.ERR_SEND_OFFLINE)));
            }
        }
    }

    private void addServerMessage(IrcMessage message) {
        Event event = createEvent(SERVER_EVENT);
        event.message = message;
        postEvent(event);
    }

    private void postState(String value) {
        Event event = createEvent(STATE_EVENT);
        event.text = value;
        postEvent(event);
    }

    private void postEvent(int type, String first, String second, String text, boolean flag) {
        Event event = createEvent(type);
        event.first = first;
        event.source = second;
        event.text = text;
        event.flag = flag;
        postEvent(event);
    }

    private void postEvent(Event event) {
        synchronized (pendingEvents) {
            if (pendingEvents.size() >= EVENT_LIMIT) {
                pendingEvents.removeElementAt(0);
            }
            pendingEvents.addElement(event);
        }
        repaint();
    }

    private Event createEvent(int type) {
        Event event = new Event();
        event.type = type;
        event.first = "";
        event.second = "";
        event.source = "";
        event.text = "";
        return event;
    }

    // Mensajes tecnicos/ingleses del socket -> espanol entendible.
    // Sin firmas ni permisos el operador lanza SecurityException.
    // Estatico de paquete para poder probarlo sin red.
    static String friendlyError(Exception exception) {
        String raw = exception == null ? "" : exception.getMessage();
        if (raw == null) {
            raw = "";
        }
        String low = IrcText.lower(raw);
        if (exception instanceof SecurityException
            || low.indexOf("denied") >= 0 || low.indexOf("permission") >= 0) {
            return IrcStrings.get(IrcStrings.ERR_NOPERM);
        }
        if (low.indexOf("timeout") >= 0 || low.indexOf("timed out") >= 0) {
            return IrcStrings.get(IrcStrings.ERR_TIMEOUT);
        }
        if (low.indexOf("refused") >= 0) {
            return IrcStrings.get(IrcStrings.ERR_REFUSED);
        }
        if (low.indexOf("unreachable") >= 0 || low.indexOf("host") >= 0) {
            return IrcStrings.get(IrcStrings.ERR_UNREACH);
        }
        // La pila del movil cuando se cae la radio o el operador tira el
        // TCP: "couldn't read form socket" (con su errata) y variantes.
        if (low.indexOf("socket") >= 0
            && (low.indexOf("read") >= 0 || low.indexOf("couldn") >= 0)) {
            return IrcStrings.get(IrcStrings.ERR_SOCKREAD);
        }
        if (low.indexOf("reset") >= 0 || low.indexOf("closed") >= 0
            || low.indexOf("eof") >= 0 || low.indexOf("broken") >= 0) {
            return IrcStrings.get(IrcStrings.ERR_CONN_LOST);
        }
        if (raw.length() == 0) {
            return IrcStrings.get(IrcStrings.ERR_CONN_FAIL);
        }
        if (raw.length() > 120) {
            raw = raw.substring(0, 120);
        }
        return raw;
    }

    private boolean sendRaw(String line) {
        synchronized (sendLock) {
            if (output == null) {
                return false;
            }
            try {
                // Linea entera en un solo paquete (texto + CRLF): si no viaja
                // entera no se corta por la mitad. Nagle se queda activo,
                // asique sigue ahorrando viajes de radio.
                byte[] body = IrcText.encode(IrcText.limitUtf8(line, 510));
                byte[] data = new byte[body.length + 2];
                System.arraycopy(body, 0, data, 0, body.length);
                data[body.length] = 13;
                data[body.length + 1] = 10;
                output.write(data);
                output.flush();
                return true;
            } catch (IOException exception) {
                // Escritura rota = conexion muerta: se cierran los streams
                // para desbloquear el read() y que entre el reconnect.
                lastSendFail = System.currentTimeMillis();
                closeStreams();
                return false;
            }
        }
    }

    private void closeStreams() {
        SocketConnection localSocket;
        InputStream localInput;
        OutputStream localOutput;
        synchronized (sendLock) {
            localSocket = socket;
            localInput = input;
            localOutput = output;
            socket = null;
            input = null;
            output = null;
        }
        try {
            if (localInput != null) {
                localInput.close();
            }
        } catch (IOException exception) {
            // Cierre best-effort durante disconnect.
        }
        try {
            if (localOutput != null) {
                localOutput.close();
            }
        } catch (IOException exception) {
            // Cierre best-effort durante disconnect.
        }
        try {
            if (localSocket != null) {
                localSocket.close();
            }
        } catch (IOException exception) {
            // Cierre best-effort durante disconnect.
        }
    }

    private synchronized boolean isCurrentConnection(int id) {
        return id == connectionId;
    }

    private boolean isStopping() {
        return stopping;
    }

    // Throttling de repintados: en canales muy activos el hilo de red
    // encolaba un repaint() por linea y la CPU se saturaba redibujando.
    // El intervalo minimo es configurable en caliente (Interfaz >
    // Refresco Pantalla, 100-1000 ms, por defecto 350 ms): las
    // peticiones intermedias se acumulan (los eventos ya estan encolados)
    // y se sirve un unico repintado diferido para no perder nunca el
    // ultimo estado. Solo con la app minimizada de verdad (flag
    // backgrounded de minimizeToStatus) el pintado se anula por completo
    // (0Hz) en ambos modos: el socket sigue acumulando lineas en el
    // buffer hasta el limite y se muestran al restaurar. Con pantallas
    // internas abiertas (lista de canales, nicks, menus) el canvas queda
    // oculto pero la app sigue visible y el repintado en vivo se mantiene.
    // El scroll por tecla llama a Canvas.repaint() directo y no se toca.
    private long lastRepaintTime;
    private boolean repaintScheduled;
    private java.util.Timer repaintTimer;

    private synchronized void repaint() {
        if (backgrounded) {
            repaintScheduled = false;
            return;
        }
        long minMs = settings.getRepaintMinMs();
        long now = System.currentTimeMillis();
        if (now - lastRepaintTime >= minMs) {
            lastRepaintTime = now;
            repaintScheduled = false;
            doRepaint();
            return;
        }
        if (repaintScheduled) {
            return;
        }
        repaintScheduled = true;
        scheduleDeferredRepaint(minMs - (now - lastRepaintTime));
    }

    private void scheduleDeferredRepaint(long delay) {
        try {
            if (repaintTimer == null) {
                repaintTimer = new java.util.Timer();
            }
            repaintTimer.schedule(new DeferredRepaint(), delay);
        } catch (Exception e) {
            // Sin temporizador: pintar ya para no perder el estado.
            synchronized (this) {
                lastRepaintTime = System.currentTimeMillis();
                repaintScheduled = false;
            }
            doRepaint();
        }
    }

    private final class DeferredRepaint extends java.util.TimerTask {
        public void run() {
            try {
                boolean allowed;
                synchronized (IrcClient.this) {
                    allowed = !backgrounded;
                    if (allowed) {
                        lastRepaintTime = System.currentTimeMillis();
                    }
                    repaintScheduled = false;
                }
                if (allowed) {
                    doRepaint();
                }
            } catch (Exception e) {
                // Nunca matar el hilo del Timer por una excepcion.
            }
        }
    }

    private void doRepaint() {
        if (backgrounded) {
            return;
        }
        IrcCanvas targetCanvas = canvas;
        if (targetCanvas != null) {
            targetCanvas.repaint();
        }
        IrcNickList targetList = nickList;
        if (targetList != null) {
            targetList.repaint();
        }
        IrcChanList targetChanList = chanList;
        if (targetChanList != null) {
            targetChanList.repaint();
        }
        IrcBanList targetBanList = banList;
        if (targetBanList != null) {
            targetBanList.repaint();
        }
        IrcWinList targetWinList = winList;
        if (targetWinList != null) {
            targetWinList.repaint();
        }
    }

    // Algunos servidores mandan JOIN/PART/NICK con el canal o nick nuevo
    // como texto final (":#canal") en vez de parametro. Se aceptan ambas.
    static String joinChannelOf(IrcProtocol message) {
        String channel = message.getParam(0);
        if (channel.length() == 0) {
            channel = message.getText();
        }
        return channel;
    }

    static String nickChangeOf(IrcProtocol message) {
        String nick = message.getParam(0);
        if (nick.length() == 0) {
            nick = message.getText();
        }
        return nick;
    }

    static String modeFlagsOf(IrcProtocol message) {
        StringBuffer flags = new StringBuffer();
        int i = 1;
        while (i < message.getParamCount()) {
            if (flags.length() > 0) {
                flags.append(' ');
            }
            flags.append(message.getParam(i));
            i++;
        }
        if (message.getText().length() > 0) {
            if (flags.length() > 0) {
                flags.append(' ');
            }
            flags.append(message.getText());
        }
        return flags.toString();
    }

    // Aplica cambios de modo con varias victimas ("+oo n1 n2",
    // "+ov n1 n2", "+m-o n"): recorre letra a letra consumiendo argumento
    // solo cuando la letra lo pide (o/v siempre nick; k/l solo al poner;
    // b siempre mascara). Todo acotado: un MODE raro nunca tumba nada.
    // Grupos CHANMODES del servidor (005): A y B siempre llevan
    // parametro, C solo al poner, D nunca. Por defecto b,k,l.
    private static String chanModeList = "b";
    private static String chanModeParam = "k";
    private static String chanModeParamSet = "l";

    static void parseChanModes(String value) {
        if (value == null) {
            return;
        }
        String[] groups = IrcText.split(value, ',');
        if (groups.length > 0 && groups[0].length() > 0) {
            chanModeList = groups[0];
        }
        if (groups.length > 1 && groups[1].length() > 0) {
            chanModeParam = groups[1];
        }
        if (groups.length > 2 && groups[2].length() > 0) {
            chanModeParamSet = groups[2];
        }
    }

    static void resetChanModes() {
        chanModeList = "b";
        chanModeParam = "k";
        chanModeParamSet = "l";
    }

    // Lee PREFIX y CHANMODES del 005 para que los MODEs con h/q/a/e/I
    // no desplacen parametros ni marquen op al nick equivocado.
    private void parse005(IrcProtocol message) {
        int i = 0;
        while (i < message.getParamCount()) {
            String param = message.getParam(i);
            if (param.startsWith("PREFIX=")) {
                String value = param.substring(7);
                if (value.length() > 0 && value.charAt(0) == '(') {
                    int close = value.indexOf(')');
                    if (close > 1) {
                        IrcWindow.setServerPrefixes(value.substring(1, close),
                            value.substring(close + 1));
                    }
                }
            } else if (param.startsWith("CHANMODES=")) {
                parseChanModes(param.substring(10));
            }
            i++;
        }
    }

    private static void applyModeFlags(IrcWindow window, String text) {
        String flags = firstToken(text);
        if (flags.length() == 0) {
            return;
        }
        String[] victims = IrcText.split(removeFirstToken(text), ' ');
        int arg = 0;
        boolean add = true;
        int i = 0;
        while (i < flags.length()) {
            char c = flags.charAt(i);
            if (c == '+') {
                add = true;
            } else if (c == '-') {
                add = false;
            } else if (IrcWindow.isPrefixMode(c) || c == 'o' || c == 'v') {
                if (arg < victims.length) {
                    String nick = victims[arg++];
                    if (nick.length() > 0) {
                        window.setNickFlag(nick,
                            add ? IrcWindow.prefixFlag(c) : IrcWindow.NICK_NORMAL);
                    }
                }
            } else if (chanModeList.indexOf(c) >= 0 || chanModeParam.indexOf(c) >= 0) {
                if (arg < victims.length) {
                    arg++;
                }
            } else if (chanModeParamSet.indexOf(c) >= 0 && add) {
                if (arg < victims.length) {
                    arg++;
                }
            }
            i++;
        }
    }

    static int nickColorFor(int status) {
        if (status == IrcWindow.NICK_OP) {
            return IrcMessage.COLOR_JOIN;
        }
        if (status == IrcWindow.NICK_VOICE) {
            return IrcMessage.COLOR_NOTICE;
        }
        return IrcMessage.COLOR_NICK;
    }

    private static int nickStatusIn(IrcWindow window, String nick) {
        if (window == null || !window.isChannel()) {
            return IrcWindow.NICK_NORMAL;
        }
        return window.getNickFlag(nick);
    }

    // Alternativas de nick durante el registro, distintas de verdad:
    // 1) base_  2) ultimo caracter -> digito  3) base + 3 digitos. Solo se
    // recorta (a 9, limite clasico) si el servidor ya dio 432.
    static String nickCandidate(String base, int attempt, boolean shortNick,
        java.util.Random random) {
        String b = base == null ? "" : base;
        if (b.length() == 0) {
            b = "Kin";
        }
        int max = shortNick ? 9 : Integer.MAX_VALUE;
        if (attempt <= 1) {
            return fitNick(b, "_", max);
        }
        if (attempt == 2) {
            char last = b.charAt(b.length() - 1);
            char digit = (char) ('0' + random.nextInt(10));
            if (digit == last) {
                digit = (char) ('0' + ((digit - '0' + 1) % 10));
            }
            String head = b.substring(0, b.length() - 1);
            if (head.length() == 0) {
                return fitNick(b, String.valueOf(digit), max);
            }
            return fitNick(head, String.valueOf(digit), max);
        }
        return fitNick(b, String.valueOf(100 + random.nextInt(900)), max);
    }

    private static String fitNick(String head, String tail, int max) {
        if (head.length() + tail.length() > max) {
            int keep = max - tail.length();
            if (keep < 1) {
                keep = 1;
            }
            if (keep < head.length()) {
                head = head.substring(0, keep);
            }
        }
        return head + tail;
    }

    static boolean isChannelName(String value) {
        return value.length() > 0 && (value.charAt(0) == '#' || value.charAt(0) == '&');
    }

    static String topicLine(String channel, String topic) {
        if (topic.length() == 0) {
            return "TOPIC " + channel;
        }
        return "TOPIC " + channel + " :" + topic;
    }

    static String kickLine(String channel, String nick, String reason) {
        if (reason.length() == 0) {
            return "KICK " + channel + " " + nick;
        }
        return "KICK " + channel + " " + nick + " :" + reason;
    }

    static String banMask(String value) {
        if (value.indexOf('!') >= 0 || value.indexOf('@') >= 0) {
            return value;
        }
        return value + "!*@*";
    }

    static String banLine(String channel, String mask) {
        return "MODE " + channel + " +b " + mask;
    }

    static String unbanLine(String channel, String mask) {
        return "MODE " + channel + " -b " + mask;
    }

    static String inviteLine(String nick, String channel) {
        return "INVITE " + nick + " " + channel;
    }

    static String modeLine(String args) {
        return "MODE " + args;
    }

    static String whoisText(String code, IrcProtocol message) {
        String nick = message.getParam(1);
        if (code.equals("311")) {
            return nick + ": " + message.getParam(2) + "@" + message.getParam(3)
                + " (" + message.getText() + ")";
        }
        if (code.equals("312")) {
            return nick + IrcStrings.get(IrcStrings.WHO_USES) + message.getParam(2) + " (" + message.getText() + ")";
        }
        if (code.equals("313")) {
            return nick + ": " + message.getText();
        }
        if (code.equals("317")) {
            return nick + IrcStrings.get(IrcStrings.WHO_IDLE_PRE) + message.getParam(2) + IrcStrings.get(IrcStrings.WHO_IDLE_POST);
        }
        if (code.equals("319")) {
            return nick + IrcStrings.get(IrcStrings.WHO_ON) + message.getText();
        }
        return nick + IrcStrings.get(IrcStrings.WHO_END);
    }

    private static String stripCtcp(String value) {
        StringBuffer result = new StringBuffer(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c != 1) {
                result.append(c);
            }
            i++;
        }
        return IrcText.trim(result.toString());
    }

    private static String normalizeChannel(String value) {
        String channel = IrcText.trim(value);
        if (channel.length() > 0 && channel.charAt(0) != '#' && channel.charAt(0) != '&') {
            channel = "#" + channel;
        }
        return IrcText.clean(channel);
    }

    private static String firstToken(String value) {
        String text = IrcText.trim(value);
        int space = text.indexOf(' ');
        if (space < 0) {
            return text;
        }
        return text.substring(0, space);
    }

    private static String removeFirstToken(String value) {
        String text = IrcText.trim(value);
        int space = text.indexOf(' ');
        if (space < 0) {
            return "";
        }
        return IrcText.trim(text.substring(space + 1));
    }

    private static String stripPrefixes(String value) {
        String result = value;
        while (result.length() > 0) {
            char c = result.charAt(0);
            if (c == '@' || c == '+' || c == '~' || c == '%' || c == '&') {
                result = result.substring(1);
            } else {
                break;
            }
        }
        return result;
    }

    // Cambios de nick: negrita solo en Compact Bold, como los joins.
    private IrcMessage nickMessage(String oldNick, String newNick) {
        if (settings.getJoinMode() == IrcSettings.JOIN_COMPACT) {
            return IrcMessage.nick(oldNick, newNick);
        }
        return IrcMessage.nickFine(oldNick, newNick);
    }

    private IrcMessage joinMessage(String nick, String channel) {
        if (settings.getJoinMode() == IrcSettings.JOIN_COMPACT_FINE) {
            return IrcMessage.joinArrowFine(nick);
        }
        if (settings.getJoinMode() == IrcSettings.JOIN_NORMAL) {
            return IrcMessage.system("-> " + nick + IrcStrings.get(IrcStrings.JOIN_IN_PRE) + channel,
                IrcMessage.COLOR_JOIN);
        }
        return IrcMessage.joinArrow(nick);
    }

    private IrcMessage partMessage(String nick, String channel, String reason) {
        if (settings.getJoinMode() == IrcSettings.JOIN_COMPACT_FINE) {
            return IrcMessage.partArrowFine(nick);
        }
        if (settings.getJoinMode() == IrcSettings.JOIN_NORMAL) {
            String text = "<- " + nick + IrcStrings.get(IrcStrings.PART_LEFT_PRE) + channel;
            if (reason != null && reason.length() > 0) {
                text = text + " (" + reason + ")";
            }
            return IrcMessage.system(text, IrcMessage.COLOR_PART);
        }
        return IrcMessage.partArrow(nick);
    }

    private static final class Event {
        int type;
        String first;
        String second;
        String source;
        String text;
        IrcMessage message;
        boolean flag;
    }
}
