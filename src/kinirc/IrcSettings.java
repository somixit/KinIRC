package kinirc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Vector;
import javax.microedition.lcdui.Font;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

public final class IrcSettings {
    public static final int JOIN_COMPACT_FINE = 0;
    public static final int JOIN_COMPACT = 1;
    public static final int JOIN_NORMAL = 2;
    public static final int JOIN_HIDDEN = 3;
    public static final int FONT_MINI = 0;
    public static final int FONT_SMALL = 1;
    public static final int FONT_NORMAL = 2;
    public static final int FONT_LARGE = 3;
    public static final int ALERT_OFF = 0;
    public static final int ALERT_SOUND = 1;
    public static final int ALERT_VIBRATE = 2;
    public static final int BG_POWER_SAVE = 0;
    public static final int BG_KEEP_ACTIVE = 1;
    public static final int BG_COLOR_BLACK = 1;
    public static final int BG_RGB_BLACK = 0x000000;
    public static final int MIN_CHAN_LIMIT = 20;
    public static final int MAX_CHAN_LIMIT = 5000;
    public static final int DEFAULT_CHAN_LIMIT = 300;
    public static final int MIN_WINDOW_LIMIT = 2;
    public static final int MAX_WINDOW_LIMIT = 30;
    public static final int DEFAULT_WINDOW_LIMIT = 10;
    public static final int MIN_REPAINT_MS = 100;
    public static final int MAX_REPAINT_MS = 1000;
    public static final int DEFAULT_REPAINT_MS = 350;
    public static final int RECONNECT_OFF = 0;
    public static final int RECONNECT_ON = 1;
    public static final int MIN_BAR_MARGIN = 0;
    public static final int MAX_BAR_MARGIN = 40;
    public static final int DEFAULT_BAR_MARGIN = 15;
    public static final int INDICATOR_NUMERAL = 0;
    public static final int INDICATOR_BARS = 1;
    public static final int INDICATOR_BOTH = 2;
    public static final String DEFAULT_HOST = "";
    public static final int DEFAULT_PORT = 6667;
    public static final int MAX_PROFILES = 8;
    public static final int MIN_HISTORY = 1;
    public static final int MAX_HISTORY = 100;

    private static final String STORE_NAME = "kinirc-profiles";
    private static final int RECORD_ID = 1;
    private static final int FORMAT_VERSION = 16;
    public static final int LANG_ES = 0;
    public static final int LANG_EN = 1;

    private Vector profiles = new Vector();
    private int active;
    private int historyLimit = 10;
    // Opciones eliminadas, valores fijos: sin reparto (siempre individual),
    // minimo 1, fondo negro, scroll siempre, segundo plano activo. Sus
    // ints se siguen leyendo y guardando solo por compatibilidad de formato.
    private int joinMode = JOIN_COMPACT;
    private int fontSize = FONT_NORMAL;
    private int alertMode = ALERT_SOUND;
    private int backgroundMode = BG_POWER_SAVE;
    private int chanLimit = DEFAULT_CHAN_LIMIT;
    private Vector ignores = new Vector();
    private int winIndicator = INDICATOR_NUMERAL;
    private boolean nickBold = true;
    private int barMargin = DEFAULT_BAR_MARGIN;
    private int maxWindows = DEFAULT_WINDOW_LIMIT;
    private int reconnectMode = RECONNECT_ON;
    private int repaintMinMs = DEFAULT_REPAINT_MS;
    private boolean textOnlyMode = true;
    private boolean showClock = true;
    private boolean configured;
    // Idioma de la interfaz (-1 = sin elegir aun): ES por defecto.
    private int language = -1;

    public synchronized void load() {
        if (loadProfiles()) {
            return;
        }
        configured = false;
    }

    // Foto de los escalares: si el parseo falla a mitad se restaura, para
    // no dejar una config hibrida que el siguiente save() consolidaria.
    private int savedHistoryLimit;
    private int savedJoinMode;
    private int savedFontSize;
    private int savedAlertMode;
    private int savedBackgroundMode;
    private int savedChanLimit;
    private int savedWinIndicator;
    private boolean savedNickBold;
    private int savedBarMargin;
    private int savedMaxWindows;
    private int savedReconnectMode;
    private int savedRepaintMinMs;
    private boolean savedTextOnlyMode;
    private boolean savedShowClock;
    private int savedLanguage;
    private Vector savedIgnores;
    private boolean lastSaveOk = true;

    // True si el ultimo save() llego a escribir en RMS; la UI avisa con
    // false para no dar por guardado algo que no esta.
    public synchronized boolean isLastSaveOk() {
        return lastSaveOk;
    }

    private void snapshotSettings() {
        savedHistoryLimit = historyLimit;
        savedJoinMode = joinMode;
        savedFontSize = fontSize;
        savedAlertMode = alertMode;
        savedBackgroundMode = backgroundMode;
        savedChanLimit = chanLimit;
        savedWinIndicator = winIndicator;
        savedNickBold = nickBold;
        savedBarMargin = barMargin;
        savedMaxWindows = maxWindows;
        savedReconnectMode = reconnectMode;
        savedRepaintMinMs = repaintMinMs;
        savedTextOnlyMode = textOnlyMode;
        savedShowClock = showClock;
        savedLanguage = language;
        // Copia manual de los ignorados (CLDC no tiene Vector.clone()):
        // el parseo va añadiendo a ignores y hay que poder deshacerlo.
        savedIgnores = ignores;
        Vector copy = new Vector(ignores.size());
        int i = 0;
        while (i < ignores.size()) {
            copy.addElement(ignores.elementAt(i));
            i++;
        }
        ignores = copy;
    }

    private void restoreSettings() {
        historyLimit = savedHistoryLimit;
        joinMode = savedJoinMode;
        fontSize = savedFontSize;
        alertMode = savedAlertMode;
        backgroundMode = savedBackgroundMode;
        chanLimit = savedChanLimit;
        winIndicator = savedWinIndicator;
        nickBold = savedNickBold;
        barMargin = savedBarMargin;
        maxWindows = savedMaxWindows;
        reconnectMode = savedReconnectMode;
        repaintMinMs = savedRepaintMinMs;
        textOnlyMode = savedTextOnlyMode;
        showClock = savedShowClock;
        language = savedLanguage;
        ignores = savedIgnores;
    }

    private boolean loadProfiles() {
        RecordStore store = null;
        snapshotSettings();
        try {
            store = RecordStore.openRecordStore(STORE_NAME, false);
            byte[] data = store.getRecord(RECORD_ID);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
            int version = input.readInt();
            if (version < 1 || version > FORMAT_VERSION) {
                return false;
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_PROFILES) {
                return false;
            }
            Vector loaded = new Vector();
            int i = 0;
            while (i < count) {
                IrcProfile profile = new IrcProfile();
                profile.setName(input.readUTF());
                profile.setHost(input.readUTF());
                profile.setPort(input.readInt());
                profile.setNick(input.readUTF());
                profile.setUser(input.readUTF());
                profile.setRealName(input.readUTF());
                profile.setPassword(input.readUTF());
                profile.setChannels(input.readUTF());
                loaded.addElement(profile);
                i++;
            }
            int savedActive = input.readInt();
            historyLimit = clamp(input.readInt(), MIN_HISTORY, MAX_HISTORY);
            int rawJoinMode = input.readInt();
            if (version < 7) {
                joinMode = clamp(rawJoinMode, 0, 2) + 1;
            } else {
                joinMode = clamp(rawJoinMode, JOIN_COMPACT_FINE, JOIN_HIDDEN);
            }
            if (version >= 2) {
                fontSize = clamp(input.readInt(), FONT_MINI, FONT_LARGE);
            } else {
                fontSize = FONT_NORMAL;
            }
            if (version >= 3) {
                input.readInt(); // reservado: reparto siempre individual
                alertMode = clamp(input.readInt(), ALERT_OFF, ALERT_VIBRATE);
            } else {
                alertMode = ALERT_SOUND;
            }
            if (version >= 4) {
                int storedBg = input.readInt();
                chanLimit = clamp(input.readInt(), MIN_CHAN_LIMIT, MAX_CHAN_LIMIT);
                if (version >= 12) {
                    backgroundMode = clamp(storedBg, BG_POWER_SAVE, BG_KEEP_ACTIVE);
                } else {
                    backgroundMode = BG_POWER_SAVE;
                }
            } else {
                backgroundMode = BG_POWER_SAVE;
                chanLimit = DEFAULT_CHAN_LIMIT;
            }
            if (version >= 5) {
                int ignoreCount = input.readInt();
                if (ignoreCount < 0 || ignoreCount > 30) {
                    return false;
                }
                int k = 0;
                while (k < ignoreCount) {
                    addIgnore(input.readUTF());
                    k++;
                }
                int storedIndicator = input.readInt();
                if (version >= 14) {
                    winIndicator = clamp(storedIndicator, INDICATOR_NUMERAL, INDICATOR_BOTH);
                } else {
                    winIndicator = storedIndicator != 0 ? INDICATOR_BOTH : INDICATOR_NUMERAL;
                }
            } else {
                winIndicator = INDICATOR_BOTH;
            }
            if (version >= 6) {
                nickBold = input.readInt() != 0;
                barMargin = clamp(input.readInt(), MIN_BAR_MARGIN, MAX_BAR_MARGIN);
            } else {
                nickBold = true;
                barMargin = DEFAULT_BAR_MARGIN;
            }
            if (version >= 7) {
                input.readInt(); // reservado: fondo siempre negro
            }
            if (version >= 8) {
                input.readInt(); // reservado: minimo siempre 1
                input.readInt(); // reservado: scroll siempre visible
            }
            if (version >= 9) {
                maxWindows = clamp(input.readInt(), MIN_WINDOW_LIMIT, MAX_WINDOW_LIMIT);
            } else {
                maxWindows = DEFAULT_WINDOW_LIMIT;
            }
            if (version >= 10) {
                reconnectMode = clamp(input.readInt(), RECONNECT_OFF, RECONNECT_ON);
            } else {
                reconnectMode = RECONNECT_ON;
            }
            if (version >= 11) {
                repaintMinMs = clamp(input.readInt(), MIN_REPAINT_MS, MAX_REPAINT_MS);
            } else {
                repaintMinMs = DEFAULT_REPAINT_MS;
            }
            if (version >= 13) {
                textOnlyMode = input.readInt() != 0;
            } else {
                textOnlyMode = true;
            }
            if (version >= 15) {
                showClock = input.readInt() != 0;
            } else {
                showClock = true;
            }
            if (version >= 16) {
                int storedLang = input.readInt();
                language = (storedLang == LANG_ES || storedLang == LANG_EN) ? storedLang : -1;
            } else {
                language = -1;
            }
            input.close();
            if (loaded.size() == 0) {
                return false;
            }
            profiles = loaded;
            active = savedActive;
            if (active < 0 || active >= profiles.size()) {
                active = 0;
            }
            configured = true;
            return true;
        } catch (Exception exception) {
            // Registro corrupto: se vuelve al estado previo (como si
            // nunca se hubiera tocado) y NO se escribe nada.
            restoreSettings();
            return false;
        } finally {
            if (store != null) {
                try {
                    store.closeRecordStore();
                } catch (RecordStoreException exception) {
                }
            }
        }
    }

    public synchronized void save() {
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore(STORE_NAME, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(profiles.size());
            int i = 0;
            while (i < profiles.size()) {
                IrcProfile profile = (IrcProfile) profiles.elementAt(i);
                output.writeUTF(profile.getName());
                output.writeUTF(profile.getHost());
                output.writeInt(profile.getPort());
                output.writeUTF(profile.getNick());
                output.writeUTF(profile.getUser());
                output.writeUTF(profile.getRealName());
                output.writeUTF(profile.getPassword());
                output.writeUTF(profile.getChannels());
                i++;
            }
            output.writeInt(active);
            output.writeInt(historyLimit);
            output.writeInt(joinMode);
            output.writeInt(fontSize);
            output.writeInt(0); // reservado: reparto siempre individual
            output.writeInt(alertMode);
            output.writeInt(backgroundMode);
            output.writeInt(chanLimit);
            output.writeInt(ignores.size());
            int k = 0;
            while (k < ignores.size()) {
                output.writeUTF((String) ignores.elementAt(k));
                k++;
            }
            output.writeInt(winIndicator);
            output.writeInt(nickBold ? 1 : 0);
            output.writeInt(barMargin);
            output.writeInt(BG_COLOR_BLACK); // reservado: fondo siempre negro
            output.writeInt(1); // reservado: minimo siempre 1
            output.writeInt(1); // reservado: scroll siempre visible
            output.writeInt(maxWindows);
            output.writeInt(reconnectMode);
            output.writeInt(repaintMinMs);
            output.writeInt(textOnlyMode ? 1 : 0);
            output.writeInt(showClock ? 1 : 0);
            output.writeInt(language);
            output.flush();
            byte[] data = bytes.toByteArray();
            try {
                store.setRecord(RECORD_ID, data, 0, data.length);
            } catch (javax.microedition.rms.InvalidRecordIDException exception) {
                store.addRecord(data, 0, data.length);
            }
            output.close();
            configured = profiles.size() > 0;
            lastSaveOk = true;
        } catch (Exception exception) {
            // RMS lleno o denegado: la config en memoria sigue valiendo,
            // pero se avisa al llamador para que no parezca que se
            // guardó bien.
            lastSaveOk = false;
        } finally {
            if (store != null) {
                try {
                    store.closeRecordStore();
                } catch (RecordStoreException exception) {
                }
            }
        }
    }

    public synchronized int getProfileCount() {
        return profiles.size();
    }

    public synchronized IrcProfile getProfileAt(int index) {
        if (index < 0 || index >= profiles.size()) {
            return null;
        }
        return ((IrcProfile) profiles.elementAt(index)).copy();
    }

    public synchronized void addProfile(IrcProfile profile) {
        if (profile == null || profiles.size() >= MAX_PROFILES) {
            return;
        }
        profiles.addElement(profile.copy());
    }

    public synchronized void setProfileAt(int index, IrcProfile profile) {
        if (index < 0 || index >= profiles.size() || profile == null) {
            return;
        }
        profiles.setElementAt(profile.copy(), index);
    }

    public synchronized void deleteProfile(int index) {
        if (index < 0 || index >= profiles.size()) {
            return;
        }
        profiles.removeElementAt(index);
        if (active >= profiles.size()) {
            active = profiles.size() - 1;
        }
        if (active < 0) {
            active = 0;
        }
    }

    public synchronized int getActiveIndex() {
        return active;
    }

    public synchronized void setActiveProfile(int index) {
        if (index >= 0 && index < profiles.size()) {
            active = index;
        }
    }

    public synchronized IrcProfile getActiveProfile() {
        if (active < 0 || active >= profiles.size()) {
            return null;
        }
        return ((IrcProfile) profiles.elementAt(active)).copy();
    }

    public synchronized int getHistoryLimit() {
        return historyLimit;
    }

    public synchronized void setHistoryLimit(int value) {
        historyLimit = clamp(value, MIN_HISTORY, MAX_HISTORY);
    }

    public synchronized int getAlertMode() {
        return alertMode;
    }

    public synchronized void setAlertMode(int value) {
        alertMode = clamp(value, ALERT_OFF, ALERT_VIBRATE);
    }

    public synchronized int getBackgroundMode() {
        return backgroundMode;
    }

    public synchronized void setBackgroundMode(int value) {
        backgroundMode = clamp(value, BG_POWER_SAVE, BG_KEEP_ACTIVE);
    }

    public synchronized int getChanLimit() {
        return chanLimit;
    }

    public synchronized void setChanLimit(int value) {
        chanLimit = clamp(value, MIN_CHAN_LIMIT, MAX_CHAN_LIMIT);
    }

    public synchronized int getMaxWindows() {
        return maxWindows;
    }

    public synchronized void setMaxWindows(int value) {
        maxWindows = clamp(value, MIN_WINDOW_LIMIT, MAX_WINDOW_LIMIT);
    }

    public synchronized int getReconnectMode() {
        return reconnectMode;
    }

    public synchronized void setReconnectMode(int value) {
        reconnectMode = clamp(value, RECONNECT_OFF, RECONNECT_ON);
    }

    public synchronized int getRepaintMinMs() {
        return repaintMinMs;
    }

    public synchronized void setRepaintMinMs(int value) {
        repaintMinMs = clamp(value, MIN_REPAINT_MS, MAX_REPAINT_MS);
    }

    public synchronized boolean isTextOnlyMode() {
        return textOnlyMode;
    }

    public synchronized void setTextOnlyMode(boolean value) {
        textOnlyMode = value;
    }

    // Reservado: el reloj va siempre visible; se sigue leyendo y
    // guardando su int para no descuadrar el formato RMS existente.

    // -1 interno = sin elegir: se informa ES hasta que se elige.
    public synchronized boolean hasLanguage() {
        return language == LANG_ES || language == LANG_EN;
    }

    public synchronized int getLanguage() {
        return hasLanguage() ? language : LANG_ES;
    }

    public synchronized void setLanguage(int value) {
        language = (value == LANG_EN) ? LANG_EN : LANG_ES;
    }

    public synchronized boolean isIgnored(String nick) {
        String clean = IrcText.trim(nick);
        if (clean.length() == 0) {
            return false;
        }
        int i = 0;
        while (i < ignores.size()) {
            if (IrcText.ircEquals((String) ignores.elementAt(i), clean)) {
                return true;
            }
            i++;
        }
        return false;
    }

    public synchronized void addIgnore(String nick) {
        String clean = IrcText.trim(nick);
        if (clean.length() == 0 || ignores.size() >= 30 || isIgnored(clean)) {
            return;
        }
        ignores.addElement(clean);
    }

    public synchronized void removeIgnore(String nick) {
        String clean = IrcText.trim(nick);
        int i = 0;
        while (i < ignores.size()) {
            if (IrcText.ircEquals((String) ignores.elementAt(i), clean)) {
                ignores.removeElementAt(i);
                return;
            }
            i++;
        }
    }

    public synchronized int getIgnoreCount() {
        return ignores.size();
    }

    public synchronized String getIgnoreAt(int index) {
        if (index < 0 || index >= ignores.size()) {
            return "";
        }
        return (String) ignores.elementAt(index);
    }

    public synchronized int getWinIndicator() {
        return winIndicator;
    }

    public synchronized void setWinIndicator(int value) {
        winIndicator = clamp(value, INDICATOR_NUMERAL, INDICATOR_BOTH);
    }

    public synchronized boolean isNickBold() {
        return nickBold;
    }

    public synchronized void setNickBold(boolean value) {
        nickBold = value;
    }

    public synchronized int getBarMargin() {
        return barMargin;
    }

    public synchronized void setBarMargin(int value) {
        barMargin = clamp(value, MIN_BAR_MARGIN, MAX_BAR_MARGIN);
    }

    public synchronized int getJoinMode() {
        return joinMode;
    }

    public synchronized void setJoinMode(int value) {
        joinMode = clamp(value, JOIN_COMPACT_FINE, JOIN_HIDDEN);
    }

    public synchronized int getFontSize() {
        return fontSize;
    }

    public synchronized void setFontSize(int value) {
        fontSize = clamp(value, FONT_MINI, FONT_LARGE);
    }

    // MIDP solo tiene 3 tamanos: Mini y Pequeno comparten SMALL,
    // Mini ademas aprieta el interlineado.
    public synchronized int getFontSizeConstant() {
        if (fontSize == FONT_LARGE) {
            return Font.SIZE_LARGE;
        }
        if (fontSize == FONT_NORMAL) {
            return Font.SIZE_MEDIUM;
        }
        return Font.SIZE_SMALL;
    }

    public synchronized boolean isCompactLeading() {
        return fontSize == FONT_MINI;
    }

    public synchronized boolean isConfigured() {
        return configured && profiles.size() > 0;
    }

    public synchronized void markConfigured() {
        configured = profiles.size() > 0;
    }

    private static int clamp(int value, int minimum, int maximum) {
        if (value < minimum) {
            return minimum;
        }
        if (value > maximum) {
            return maximum;
        }
        return value;
    }
}
