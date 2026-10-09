package kinirc;

public final class IrcWindow {
    public static final int CHANNEL = 0;
    public static final int QUERY = 1;
    public static final int STATUS = 2;

    public static final int NICK_NORMAL = 0;
    public static final int NICK_VOICE = 1;
    public static final int NICK_OP = 2;

    private final int type;
    private final String name;
    private IrcMessage[] messages;
    private int start;
    private int count;
    private int scroll;
    private int unread;
    private boolean joined;
    private boolean closing;
    private String topic = "";
    private String modes = "";
    private String[] nicks = new String[0];
    private int[] nickFlags = new int[0];

    public IrcWindow(int type, String name, int capacity) {
        this.type = type;
        this.name = name;
        messages = new IrcMessage[clamp(capacity, 1, 100)];
    }

    public int getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public boolean isChannel() {
        return type == CHANNEL;
    }

    public boolean isQuery() {
        return type == QUERY;
    }

    public boolean isServer() {
        return type == STATUS;
    }

    public synchronized void add(IrcMessage message) {
        if (message == null) {
            return;
        }
        if (count == messages.length) {
            start = (start + 1) % messages.length;
            count--;
        }
        int index = (start + count) % messages.length;
        messages[index] = message;
        count++;
        unread++;
        if (unread > count) {
            unread = count;
        }
    }

    public synchronized IrcMessage get(int index) {
        if (index < 0 || index >= count) {
            return null;
        }
        return messages[(start + index) % messages.length];
    }

    // Copia atomica para pintar sin carreras: antes size()+get(i) en
    // bucle podia ver count cambiado por clearMessages/setCapacity/add
    // desde el hilo de red y provocar NPE o saltos.
    public synchronized IrcMessage[] snapshot() {
        IrcMessage[] copy = new IrcMessage[count];
        int i = 0;
        while (i < count) {
            copy[i] = messages[(start + i) % messages.length];
            i++;
        }
        return copy;
    }

    public synchronized int size() {
        return count;
    }

    public synchronized void clearMessages() {
        int i = 0;
        while (i < messages.length) {
            messages[i] = null;
            i++;
        }
        start = 0;
        count = 0;
        scroll = 0;
        unread = 0;
    }

    public synchronized void setCapacity(int capacity) {
        int newCapacity = clamp(capacity, 1, 100);
        if (newCapacity == messages.length) {
            return;
        }
        IrcMessage[] replacement = new IrcMessage[newCapacity];
        int keep = Math.min(count, newCapacity);
        int first = count - keep;
        int i = 0;
        while (i < keep) {
            replacement[i] = messages[(start + first + i) % messages.length];
            i++;
        }
        messages = replacement;
        start = 0;
        count = keep;
        if (scroll > 0) {
            scroll = 0;
        }
    }

    public synchronized int getScroll() {
        return scroll;
    }

    public synchronized void setScroll(int value) {
        if (value < 0) {
            value = 0;
        }
        scroll = value;
    }

    public synchronized int getUnread() {
        return unread;
    }

    public synchronized void markRead() {
        unread = 0;
    }

    public synchronized boolean addNick(String nick) {
        String normalized = normalizeNick(nick);
        if (normalized.length() == 0 || hasNick(normalized)) {
            return false;
        }
        String[] names = new String[nicks.length + 1];
        int[] flags = new int[nicks.length + 1];
        System.arraycopy(nicks, 0, names, 0, nicks.length);
        System.arraycopy(nickFlags, 0, flags, 0, nickFlags.length);
        names[nicks.length] = normalized;
        flags[nicks.length] = nickFlagOf(nick);
        nicks = names;
        nickFlags = flags;
        // Sin ordenar: la red guarda en bruto y la lista ordena al abrirse.
        return true;
    }

    // Carga en lote (respuesta NAMES de cientos de nicks): crece los
    // arrays una sola vez, sin ordenar (la lista ordena al abrirse).
    // Con chequeo de duplicados (el propio nick llega por JOIN y tambien
    // dentro del 353): solo comparaciones baratas, sin realojamientos
    // ni basura extra.
    // Devuelve cuantos se anadieron.
    public synchronized int addNicksBulk(String[] values) {
        if (values == null || values.length == 0) {
            return 0;
        }
        int count = 0;
        boolean resync = false;
        int i = 0;
        while (i < values.length) {
            String normalized = normalizeNick(values[i]);
            if (normalized.length() > 0) {
                if (!hasNick(normalized)) {
                    count++;
                } else if (!resync
                    && getNickFlag(normalized) != nickFlagOf(values[i])) {
                    // Foto del servidor con flag distinto y sin altas: hay
                    // trabajo igual (el @ que llego como dupe), no retornar.
                    resync = true;
                }
            }
            i++;
        }
        if (count == 0 && !resync) {
            return 0;
        }
        String[] names = new String[nicks.length + count];
        int[] flags = new int[nicks.length + count];
        System.arraycopy(nicks, 0, names, 0, nicks.length);
        System.arraycopy(nickFlags, 0, flags, 0, nickFlags.length);
        // hasNick trabaja sobre nicks[], que aun no incluye el lote: se
        // rechequea contra names[] (ya con lo anadido) para cazar dupes
        // dentro del propio lote y carreras con JOINs entre rafagas.
        // El 353 es foto completa del servidor: si el nick ya estaba (p.
        // ej. el propio via eco de JOIN como normal), se sincroniza su
        // flag con el del lote en vez de saltarlo.
        int pos = nicks.length;
        i = 0;
        while (i < values.length) {
            String normalized = normalizeNick(values[i]);
            if (normalized.length() > 0) {
                int found = indexOfNickIn(names, pos, normalized);
                if (found < 0) {
                    names[pos] = normalized;
                    flags[pos] = nickFlagOf(values[i]);
                    pos++;
                } else {
                    flags[found] = nickFlagOf(values[i]);
                }
            }
            i++;
        }
        if (pos < names.length) {
            // Dupes dentro del propio lote: se recorta la cola sin usar.
            String[] trimmed = new String[pos];
            int[] trimmedFlags = new int[pos];
            System.arraycopy(names, 0, trimmed, 0, pos);
            System.arraycopy(flags, 0, trimmedFlags, 0, pos);
            names = trimmed;
            flags = trimmedFlags;
        }
        int added = pos - nicks.length;
        nicks = names;
        nickFlags = flags;
        // Sin ordenar: la lista ordena al abrirse (ver sortNicks).
        return added;
    }

    private static boolean hasNickIn(String[] names, int length, String nick) {
        return indexOfNickIn(names, length, nick) >= 0;
    }

    private static int indexOfNickIn(String[] names, int length, String nick) {
        int i = 0;
        while (i < length) {
            if (IrcText.ircEquals(names[i], nick)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    public synchronized boolean setNickFlag(String nick, int flag) {
        String normalized = normalizeNick(nick);
        int i = 0;
        while (i < nicks.length) {
            if (IrcText.ircEquals(nicks[i], normalized)) {
                if (nickFlags[i] != flag) {
                    nickFlags[i] = flag;
                    // Sin ordenar: la lista ordena al abrirse.
                }
                return true;
            }
            i++;
        }
        return false;
    }

    public synchronized boolean removeNick(String nick) {
        String normalized = normalizeNick(nick);
        int found = -1;
        int i = 0;
        while (i < nicks.length) {
            if (IrcText.ircEquals(nicks[i], normalized)) {
                found = i;
                break;
            }
            i++;
        }
        if (found < 0) {
            return false;
        }
        String[] names = new String[nicks.length - 1];
        int[] flags = new int[nicks.length - 1];
        System.arraycopy(nicks, 0, names, 0, found);
        System.arraycopy(nickFlags, 0, flags, 0, found);
        System.arraycopy(nicks, found + 1, names, found, nicks.length - found - 1);
        System.arraycopy(nickFlags, found + 1, flags, found, nicks.length - found - 1);
        nicks = names;
        nickFlags = flags;
        return true;
    }

    public synchronized boolean renameNick(String oldNick, String newNick) {
        String normalizedOld = normalizeNick(oldNick);
        String normalizedNew = normalizeNick(newNick);
        int found = -1;
        int i = 0;
        while (i < nicks.length) {
            if (IrcText.ircEquals(nicks[i], normalizedOld)) {
                found = i;
                break;
            }
            i++;
        }
        if (found < 0) {
            return false;
        }
        nicks[found] = normalizedNew;
        // Sin ordenar: la lista ordena al abrirse.
        return true;
    }

    public synchronized boolean hasNick(String nick) {
        String normalized = normalizeNick(nick);
        int i = 0;
        while (i < nicks.length) {
            if (IrcText.ircEquals(nicks[i], normalized)) {
                return true;
            }
            i++;
        }
        return false;
    }

    public synchronized int getNickCount() {
        return nicks.length;
    }

    public synchronized String getNickAt(int index) {
        if (index < 0 || index >= nicks.length) {
            return "";
        }
        return nicks[index];
    }

    public synchronized int getNickFlagAt(int index) {
        if (index < 0 || index >= nickFlags.length) {
            return NICK_NORMAL;
        }
        return nickFlags[index];
    }

    public synchronized int getNickFlag(String nick) {
        String normalized = normalizeNick(nick);
        int i = 0;
        while (i < nicks.length) {
            if (IrcText.ircEquals(nicks[i], normalized)) {
                return nickFlags[i];
            }
            i++;
        }
        return NICK_NORMAL;
    }

    public synchronized void clearNicks() {
        nicks = new String[0];
        nickFlags = new int[0];
    }

    public synchronized boolean isJoined() {
        return joined;
    }

    public synchronized void setJoined(boolean value) {
        joined = value;
    }

    // Semaforo de cierre diferido: al pedir cerrar un canal la ventana se
    // queda en amarillo hasta que el servidor confirma la salida con el
    // eco del PART (o un timeout). Los mensajes rezagados caen en ella de
    // forma normal porque sigue existiendo: sin efecto zombi.
    public synchronized boolean isClosing() {
        return closing;
    }

    public synchronized void setClosing(boolean value) {
        closing = value;
    }

    // Topic y modos cacheados para la marquesina de la lista de
    // ventanas. Solo texto ya limpio; un par de cadenas por canal.
    public synchronized String getTopic() {
        return topic;
    }

    public synchronized void setTopic(String value) {
        topic = value == null ? "" : value;
    }

    public synchronized String getModes() {
        return modes;
    }

    public synchronized void setModes(String value) {
        modes = value == null ? "" : value;
    }

    // Ordenacion bajo demanda: la red guarda en bruto y solo se ordena
    // al abrir la lista de nicks. QuickSort in-place O(N log N) sobre
    // los dos arrays en paralelo, sin crear ni un objeto (apto para la
    // KVM). Mismo criterio que antes (ver compareNick).
    public synchronized void sortNicks() {
        quickSortNicks(0, nicks.length - 1);
    }

    private void quickSortNicks(int low, int high) {
        while (low < high) {
            // Pivote central: con entrada ya ordenada (reaperturas) un
            // pivote ingenuo degradaria a O(N^2).
            int pivot = (low + high) / 2;
            int pivotFlag = nickFlags[pivot];
            String pivotName = nicks[pivot];
            int i = low;
            int j = high;
            while (i <= j) {
                while (compareNick(nickFlags[i], nicks[i], pivotFlag, pivotName) < 0) {
                    i++;
                }
                while (compareNick(nickFlags[j], nicks[j], pivotFlag, pivotName) > 0) {
                    j--;
                }
                if (i <= j) {
                    String swapName = nicks[i];
                    nicks[i] = nicks[j];
                    nicks[j] = swapName;
                    int swapFlag = nickFlags[i];
                    nickFlags[i] = nickFlags[j];
                    nickFlags[j] = swapFlag;
                    i++;
                    j--;
                }
            }
            // Recursión solo por el lado pequeño: profundidad O(log N).
            if (j - low < high - i) {
                if (low < j) {
                    quickSortNicks(low, j);
                }
                low = i;
            } else {
                if (i < high) {
                    quickSortNicks(i, high);
                }
                high = j;
            }
        }
    }

    private static int compareNick(int flagA, String nameA, int flagB, String nameB) {
        if (flagA != flagB) {
            return flagB - flagA;
        }
        return IrcText.lower(nameA).compareTo(IrcText.lower(nameB));
    }

    private static int nickFlagOf(String value) {
        String text = IrcText.trim(value);
        if (text.length() == 0) {
            return NICK_NORMAL;
        }
        char c = text.charAt(0);
        if (c == '+') {
            return NICK_VOICE;
        }
        if (isStatusPrefix(c) || c == '@' || c == '~' || c == '&' || c == '%') {
            return NICK_OP;
        }
        return NICK_NORMAL;
    }

    private static String normalizeNick(String value) {
        String result = IrcText.trim(value);
        while (result.length() > 0) {
            char c = result.charAt(0);
            if (c == '@' || c == '+' || c == '~' || c == '%' || c == '!' || c == '&'
                || isStatusPrefix(c)) {
                result = result.substring(1);
            } else {
                break;
            }
        }
        return result;
    }

    // Prefijos de estado del servidor (005 PREFIX): por defecto o/v.
    // Se actualizan al conectar; sin 005 vale lo anterior.
    private static String prefixModes = "ov";
    private static String prefixChars = "@+";

    static void setServerPrefixes(String modes, String prefixes) {
        if (modes == null || prefixes == null || modes.length() == 0
            || modes.length() != prefixes.length()) {
            return;
        }
        prefixModes = modes;
        prefixChars = prefixes;
    }

    static void resetServerPrefixes() {
        prefixModes = "ov";
        prefixChars = "@+";
    }

    static boolean isPrefixMode(char c) {
        return prefixModes.indexOf(c) >= 0;
    }

    // Flag segun emparejamiento del 005 (modos[i] <-> prefijos[i]):
    // voz si empareja '+', op en otro caso. Con o/v de siempre igual.
    static int prefixFlag(char c) {
        int at = prefixModes.indexOf(c);
        if (at >= 0 && at < prefixChars.length()) {
            return prefixChars.charAt(at) == '+' ? NICK_VOICE : NICK_OP;
        }
        if (c == 'o') {
            return NICK_OP;
        }
        if (c == 'v') {
            return NICK_VOICE;
        }
        return NICK_NORMAL;
    }

    private static boolean isStatusPrefix(char c) {
        return prefixChars.indexOf(c) >= 0;
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
