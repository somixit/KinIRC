package kinirc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Vector;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

// Acciones guardadas: comandos ("/join #x") o textos listos para enviar.
public final class IrcActionStore {
    private static final String STORE_NAME = "kinirc-actions";
    private static final int RECORD_ID = 1;
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_ACTIONS = 20;

    private Vector actions = new Vector();

    public synchronized void load() {
        if (loadStore()) {
            return;
        }
        // Registro corrupto o vacio: solo se rellena la lista en memoria.
        // NO se escribe todavia (salvaria encima del registro roto del
        // usuario); se guarda al add()/set()/remove() del usuario o al salir.
        actions.removeAllElements();
        actions.addElement("/join #fun");
        actions.addElement("Hi, how are you??");
        actions.addElement("I write from an old phone!");
    }

    private boolean loadStore() {
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore(STORE_NAME, false);
            byte[] data = store.getRecord(RECORD_ID);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
            int version = input.readInt();
            if (version != FORMAT_VERSION && version != FORMAT_VERSION - 1) {
                return false;
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_ACTIONS) {
                return false;
            }
            Vector loaded = new Vector();
            int i = 0;
            while (i < count) {
                String action = input.readUTF();
                // Migracion v1->v2: elimina el preescrito obsoleto.
                if (version == FORMAT_VERSION - 1 && IrcText.trim(action).equals("/list help")) {
                    i++;
                    continue;
                }
                loaded.addElement(action);
                i++;
            }
            input.close();
            actions = loaded;
            if (version != FORMAT_VERSION) {
                save();
            }
            return true;
        } catch (Exception exception) {
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
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(actions.size());
            int i = 0;
            while (i < actions.size()) {
                output.writeUTF((String) actions.elementAt(i));
                i++;
            }
            output.flush();
            byte[] data = bytes.toByteArray();
            try {
                store.setRecord(RECORD_ID, data, 0, data.length);
            } catch (javax.microedition.rms.InvalidRecordIDException exception) {
                store.addRecord(data, 0, data.length);
            }
            output.close();
        } catch (Exception exception) {
        } finally {
            if (store != null) {
                try {
                    store.closeRecordStore();
                } catch (RecordStoreException exception) {
                }
            }
        }
    }

    public synchronized int size() {
        return actions.size();
    }

    public synchronized String get(int index) {
        if (index < 0 || index >= actions.size()) {
            return "";
        }
        return (String) actions.elementAt(index);
    }

    public synchronized void add(String value) {
        String text = IrcText.trim(value);
        if (text.length() == 0 || actions.size() >= MAX_ACTIONS) {
            return;
        }
        if (text.length() > 240) {
            text = text.substring(0, 240);
        }
        actions.addElement(text);
    }

    public synchronized void set(int index, String value) {
        String text = IrcText.trim(value);
        if (index < 0 || index >= actions.size() || text.length() == 0) {
            return;
        }
        if (text.length() > 240) {
            text = text.substring(0, 240);
        }
        actions.setElementAt(text, index);
    }

    public synchronized void remove(int index) {
        if (index < 0 || index >= actions.size()) {
            return;
        }
        actions.removeElementAt(index);
    }
}
