package kinirc;

public final class IrcBanList extends IrcListCanvas {

    public IrcBanList(IrcMidlet midlet, IrcClient client) {
        super(midlet, client);
        client.attachBanList(this);
    }

    protected void onHidden() {
        // Igual que canales: conservar para interrupciones; al reabrir
        // se vuelve a pedir (ver requestBanList).
        client.detachBanList(this);
    }

    // Mascara copiada al pintar la fila seleccionada.
    private String pickedMask;

    protected int rowCount() {
        return client.getBanCount();
    }

    protected String headerText() {
        return IrcStrings.get(IrcStrings.BAN_TITLE_PRE) + client.getBanChannel() + "  "
            + client.getBanCount();
    }

    protected String rowText(int index) {
        if (index == selected) {
            pickedMask = client.getBanAt(index);
        }
        return client.getBanAt(index);
    }

    protected String rightLabel() {
        return IrcStrings.get(IrcStrings.CMD_REMOVE);
    }

    protected void onKey(int keyCode, int action) {
        String mask = pickedMask;
        if (action == FIRE || keyCode == KEY_STAR || keyCode == KEY_SOFT_RIGHT) {
            // Igual que nicks: la mascara se copia al pintar la fila
            // seleccionada, asi la accion no recae sobre otra.
            if (mask != null && mask.length() > 0) {
                pickedMask = null;
                client.unban(mask);
            }
            repaint();
        } else if (keyCode == KEY_SOFT_LEFT) {
            midlet.showCanvas();
        } else {
            super.onKey(keyCode, action);
        }
    }
}
