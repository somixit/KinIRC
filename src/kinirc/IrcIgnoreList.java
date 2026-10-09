package kinirc;

// Lista de nicks ignorados: se lee en vivo de los ajustes (sin buffer
// propio) y la tecla blanda derecha desbloquea el seleccionado. El
// fuego no hace nada a proposito para evitar desbloqueos accidentales.
public final class IrcIgnoreList extends IrcListCanvas {

    public IrcIgnoreList(IrcMidlet midlet, IrcClient client) {
        super(midlet, client);
    }

    protected int rowCount() {
        return client.getIgnoreCount();
    }

    protected String headerText() {
        return IrcStrings.get(IrcStrings.IGN_TITLE_PRE) + client.getIgnoreCount()
            + IrcStrings.get(IrcStrings.IGN_NICK_SUFFIX);
    }

    protected String emptyText() {
        return IrcStrings.get(IrcStrings.IGN_EMPTY);
    }

    protected String rowText(int index) {
        return client.getIgnoreAt(index);
    }

    protected String rightLabel() {
        return IrcStrings.get(IrcStrings.CMD_REMOVE);
    }

    protected void onKey(int keyCode, int action) {
        if (action == FIRE) {
            // Sin accion a proposito: desbloquear es solo con la tecla
            // derecha para evitar pulsaciones accidentales del centro.
        } else if (keyCode == KEY_SOFT_RIGHT) {
            unignoreSelected();
        } else if (keyCode == KEY_SOFT_LEFT) {
            midlet.showCanvas();
        } else {
            super.onKey(keyCode, action);
        }
    }

    private void unignoreSelected() {
        if (client.getIgnoreCount() <= 0) {
            return;
        }
        if (selected >= client.getIgnoreCount()) {
            selected = client.getIgnoreCount() - 1;
        }
        if (selected < 0) {
            return;
        }
        client.unignoreAt(selected);
        if (selected >= client.getIgnoreCount() && selected > 0) {
            selected--;
        }
        repaint();
    }
}
