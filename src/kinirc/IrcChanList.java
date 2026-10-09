package kinirc;

public final class IrcChanList extends IrcListCanvas {

    public IrcChanList(IrcMidlet midlet, IrcClient client) {
        super(midlet, client);
        client.attachChanList(this);
    }

    protected void onHidden() {
        // Solo se suelta la pantalla: los datos se conservan para que una
        // interrupcion (llamada) no vacie la lista. Al reabrir se vuelve
        // a pedir y se refresca (ver requestChanList).
        client.detachChanList(this);
    }

    protected int rowCount() {
        return client.getChanEntryCount();
    }

    protected String headerText() {
        int count = client.getChanEntryCount();
        int total = client.getChanTotalCount();
        if (count == 0 && client.isListingChannels() && total == 0) {
            return IrcStrings.get(IrcStrings.CHAN_SEARCHING);
        }
        if (total > 0 && (client.isListingChannels() || total != count)) {
            // Mostrados/recibidos: 130/130, 300/300, 300/450, 300/1300.
            // El tope es para no saturar la pantalla; el total sigue
            // subiendo mientras el socket recibe para dar feedback.
            return IrcStrings.get(IrcStrings.CHAN_TITLE_PRE) + count + "/" + total;
        }
        return IrcStrings.get(IrcStrings.CHAN_TITLE_PRE) + count;
    }

    protected String emptyText() {
        if (client.isListingChannels()) {
            return IrcStrings.get(IrcStrings.CHAN_SEARCHING_SHORT);
        }
        return IrcStrings.get(IrcStrings.LIST_EMPTY);
    }

    protected String rowText(int index) {
        return entryLabel(client.getChanEntryAt(index));
    }

    protected String rightLabel() {
        return IrcStrings.get(IrcStrings.CMD_JOIN_ENTER);
    }

    protected void onKey(int keyCode, int action) {
        if (action == FIRE || keyCode == KEY_STAR) {
            joinSelected();
        } else if (keyCode == KEY_SOFT_RIGHT) {
            joinSelected();
        } else if (keyCode == KEY_SOFT_LEFT) {
            midlet.showCanvas();
        } else {
            super.onKey(keyCode, action);
        }
    }

    private void joinSelected() {
        String[] entry = client.getChanEntryAt(selected);
        if (entry == null) {
            return;
        }
        client.requestJoin(entry[0]);
        midlet.showCanvas();
    }

    private static String entryLabel(String[] entry) {
        if (entry == null || entry.length < 2) {
            return "";
        }
        return entry[0] + " " + entry[1];
    }
}
