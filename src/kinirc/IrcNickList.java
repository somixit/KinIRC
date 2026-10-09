package kinirc;

public final class IrcNickList extends IrcListCanvas {
    public static final int PICK_QUERY = 0;
    public static final int PICK_KICK = 1;
    public static final int PICK_BAN = 2;
    public static final int PICK_OP = 3;
    public static final int PICK_DEOP = 4;
    public static final int PICK_VOICE = 5;
    public static final int PICK_DEVOICE = 6;

    private final IrcWindow window;
    private final int pickOp;
    // Nick copiado al pintar la fila seleccionada: la accion recae sobre
    // el que se veia en pantalla, no sobre el indice al pulsar.
    private String pickedNick;

    public IrcNickList(IrcMidlet midlet, IrcClient client, IrcWindow window, int pickOp) {
        super(midlet, client);
        this.window = window;
        this.pickOp = pickOp;
        client.attachNickList(this);
    }

    protected void onShown() {
        // La red guarda en bruto: se ordena aqui, al abrir, una sola vez.
        window.sortNicks();
        client.requestNames();
    }

    protected void onHidden() {
        client.detachNickList(this);
    }

    protected int rowCount() {
        return window.getNickCount();
    }

    protected String headerText() {
        return window.getName();
    }

    protected String headerRightText() {
        return Integer.toString(window.getNickCount());
    }

    protected String rowText(int index) {
        if (index == selected) {
            pickedNick = window.getNickAt(index);
        }
        return nickLabel(window, index);
    }

    protected int rowColor(int index, boolean isSelected) {
        if (isSelected) {
            return IrcDraw.STRIP_TEXT;
        }
        return nickRowColor(window.getNickFlagAt(index));
    }

    protected String rightLabel() {
        return IrcStrings.get(IrcStrings.CMD_CHOOSE);
    }

    protected void onKey(int keyCode, int action) {
        if (action == FIRE || keyCode == KEY_STAR) {
            pickSelected();
        } else if (keyCode == KEY_SOFT_RIGHT) {
            pickSelected();
        } else if (keyCode == KEY_SOFT_LEFT) {
            midlet.showCanvas();
        } else {
            super.onKey(keyCode, action);
        }
    }

    private void pickSelected() {
        String nick = pickedNick;
        if (nick == null || nick.length() == 0) {
            return;
        }
        pickedNick = null;
        if (pickOp == PICK_QUERY) {
            client.openQuery(nick);
        } else {
            client.chanOp(window, pickOp, nick);
        }
        midlet.showCanvas();
    }

    private static String nickLabel(IrcWindow window, int index) {
        String name = window.getNickAt(index);
        int flag = window.getNickFlagAt(index);
        if (flag == IrcWindow.NICK_OP) {
            return "@" + name;
        }
        if (flag == IrcWindow.NICK_VOICE) {
            return "+" + name;
        }
        return name;
    }

    private static int nickRowColor(int flag) {
        if (flag == IrcWindow.NICK_OP) {
            return IrcMessage.COLOR_JOIN;
        }
        if (flag == IrcWindow.NICK_VOICE) {
            return IrcMessage.COLOR_NOTICE;
        }
        return IrcDraw.ROW_TEXT;
    }
}
