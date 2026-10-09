package kinirc;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

public final class IrcMidlet extends MIDlet implements CommandListener {
    private IrcSettings settings;
    private IrcActionStore actionStore;
    private IrcClient client;
    private IrcCanvas canvas;
    private Display display;
    private boolean firstStart = true;

    private List profileList;
    private Command listNewCommand;
    private Command listEditCommand;
    private Command listDeleteCommand;
    private Command listOptionsCommand;
    private Command listHelpCommand;
    private Command listExitCommand;
    private boolean helpFromProfiles;

    private Form profileForm;
    private TextField profileNameField;
    private TextField hostField;
    private TextField portField;
    private TextField nickField;
    private TextField userField;
    private TextField realNameField;
    private TextField passwordField;
    private TextField channelsField;
    private Command profileSaveCommand;
    private Command profileConnectCommand;
    private Command profileCancelCommand;
    private int editingProfile = -1;

    private Form prefsForm;
    private TextField historyField;
    private ChoiceGroup eventsChoice;
    private ChoiceGroup styleChoice;
    private ChoiceGroup fontChoice;
    private ChoiceGroup alertChoice;
    private ChoiceGroup reconnectChoice;
    private TextField chanLimitField;
    private TextField maxWindowsField;
    private TextField refreshField;
    private ChoiceGroup bgChoice;
    private ChoiceGroup boldChoice;
    private ChoiceGroup indicatorChoice;
    private TextField marginField;
    private ChoiceGroup langChoice;
    private Command prefsSaveCommand;
    private Command prefsBackCommand;
    private boolean prefsFromMenu;
    private List langList;

    private List actionList;
    private Command actionNewCommand;
    private Command actionEditCommand;
    private Command actionDeleteCommand;
    private Command actionBackCommand;

    private TextBox actionBox;
    private Command actionSaveCommand;
    private Command actionCancelCommand;
    private int editingAction = -1;

    public IrcMidlet() {
        settings = new IrcSettings();
        settings.load();
        // Idioma antes que cualquier pantalla: solo reside el activo.
        IrcStrings.load(settings.getLanguage());
        actionStore = new IrcActionStore();
        actionStore.load();
    }

    protected void startApp() {
        display = Display.getDisplay(this);
        if (client == null) {
            client = new IrcClient(settings);
            canvas = new IrcCanvas(this, client);
        }
        if (firstStart) {
            firstStart = false;
            if (settings.hasLanguage()) {
                showProfiles();
            } else {
                showLangPicker();
            }
        } else {
            // Vuelta de segundo plano: se restaura la ventana que habia.
            client.setAppPaused(false);
            client.restoreFromPause();
            showCanvas();
        }
    }

    protected void pauseApp() {
        if (client == null) {
            return;
        }
        client.setAppPaused(true);
        // Ambos modos conservan el socket: la bifurcacion
        // PART/JOIN (ahorro) frente a solo-mute (activo) vive en
        // IrcClient.minimizeToStatus(). A Status para que los avisos
        // suenen: si quedara el canal activo, el codigo creeria que
        // lo sigues leyendo.
        client.minimizeToStatus();
    }

    protected void destroyApp(boolean unconditional) {
        if (canvas != null) {
            canvas.stopStatusBlink();
        }
        if (client != null) {
            client.disconnect();
        }
    }

    public IrcClient getClient() {
        return client;
    }

    public void showCanvas() {
        if (display == null) {
            return;
        }
        if (client == null) {
            client = new IrcClient(settings);
        }
        if (canvas == null) {
            canvas = new IrcCanvas(this, client);
        }
        display.setCurrent(canvas);
        client.attachCanvas(canvas);
    }

    // Minimizar a segundo plano (tecla 3 larga o menu "Minimizar").
    // J2ME no tiene una API garantizada para ocultar la app, asi que se
    // intentan las dos vias estandar: setCurrent(null) la baja en Nokia
    // S60 y Sony Ericsson (en S40/Samsung se ignora sin romper nada) y
    // notifyPaused() la pide a la AMS en los que lo soportan. La parte
    // de red (Status + PART en ahorro) queda aplicada arriba en todo
    // caso, y si la AMS avisa despues no se duplica nada.
    // setCurrent(null) y notifyPaused() corren en el hilo de eventos
    // (callSerially): invocados desde un Timer (tecla 3) la AMS de
    // algunos Sony Ericsson deja la app sin poder maximizarse.
    public void minimizeApp() {
        if (client != null) {
            client.minimizeToStatus();
        }
        try {
            Display.getDisplay(this).callSerially(new Runnable() {
                public void run() {
                    hideFromDisplay();
                }
            });
        } catch (Exception ignored) {
            hideFromDisplay();
        }
    }

    private void hideFromDisplay() {
        try {
            Display.getDisplay(this).setCurrent(null);
        } catch (Exception ignored) {
            // Terminal sin soporte: se sigue con notifyPaused().
        }
        try {
            notifyPaused();
        } catch (Exception ignored) {
            // AMS que lo ignora: la red ya quedo en segundo plano.
        }
    }

    // Primer arranque sin idioma: se elige una vez y se guarda.
    // Autonimos (no traducidos): asi se entienden en ambos idiomas.
    public void showLangPicker() {
        langList = new List(IrcStrings.get(IrcStrings.TITLE_LANGUAGE), List.IMPLICIT,
            new String[] { "Español", "English" }, null);
        langList.setCommandListener(this);
        display.setCurrent(langList);
    }

    private void pickLanguage(int index) {
        if (index < 0 || index > 1) {
            return;
        }
        settings.setLanguage(index == 1 ? IrcSettings.LANG_EN : IrcSettings.LANG_ES);
        settings.save();
        IrcStrings.load(settings.getLanguage());
        langList = null;
        showProfiles();
    }

    // Lista de perfiles de conexion. Elegir uno conecta con el.
    public void showProfiles() {
        if (display == null) {
            return;
        }
        if (client == null) {
            client = new IrcClient(settings);
        }
        if (canvas == null) {
            canvas = new IrcCanvas(this, client);
        }
        if (settings.getProfileCount() == 0) {
            showProfileForm(-1);
            return;
        }
        profileList = buildProfileList();
        display.setCurrent(profileList);
    }

    // Construir sin mostrar: para avisar antes con setCurrent(aviso, lista).
    private List buildProfileList() {
        profileList = new List(IrcStrings.get(IrcStrings.TITLE_PROFILES), List.IMPLICIT);
        refreshProfileList();
        listNewCommand = new Command(IrcStrings.get(IrcStrings.CMD_NEW), Command.SCREEN, 1);
        listEditCommand = new Command(IrcStrings.get(IrcStrings.CMD_EDIT), Command.SCREEN, 2);
        listDeleteCommand = new Command(IrcStrings.get(IrcStrings.CMD_DELETE), Command.SCREEN, 3);
        listOptionsCommand = new Command(IrcStrings.get(IrcStrings.CMD_INTERFACE), Command.SCREEN, 4);
        listHelpCommand = new Command(IrcStrings.get(IrcStrings.CMD_HELP), Command.SCREEN, 5);
        listExitCommand = new Command(IrcStrings.get(IrcStrings.CMD_EXIT), Command.SCREEN, 6);
        profileList.addCommand(listNewCommand);
        profileList.addCommand(listEditCommand);
        profileList.addCommand(listDeleteCommand);
        profileList.addCommand(listOptionsCommand);
        profileList.addCommand(listHelpCommand);
        profileList.addCommand(listExitCommand);
        profileList.setCommandListener(this);
        return profileList;
    }

    // Aviso antes de la pantalla de perfiles (lista o formulario):
    // con setCurrent(aviso, siguiente) se lee en vez de morir tapado.
    public void showProfilesAfterNotice(String value) {
        if (settings.getProfileCount() == 0) {
            showNotice(value, buildProfileForm(-1));
        } else {
            showNotice(value, buildProfileList());
        }
    }

    private void refreshProfileList() {
        profileList.deleteAll();
        int i = 0;
        while (i < settings.getProfileCount()) {
            IrcProfile profile = settings.getProfileAt(i);
            String label = profile.getLabel();
            if (i == settings.getActiveIndex()) {
                label = "* " + label;
            }
            profileList.append(label, null);
            i++;
        }
    }

    private int selectedProfile() {
        if (profileList == null) {
            return settings.getActiveIndex();
        }
        int selected = profileList.getSelectedIndex();
        if (selected < 0 || selected >= settings.getProfileCount()) {
            return settings.getActiveIndex();
        }
        return selected;
    }

    private void connectToProfile(int index) {
        if (index < 0 || index >= settings.getProfileCount()) {
            return;
        }
        IrcProfile profile = settings.getProfileAt(index);
        if (profile == null || !profile.isUsable()) {
            showNotice(IrcStrings.get(IrcStrings.NOTICE_COMPLETE_PROFILE),
                buildProfileForm(index));
            return;
        }
        if (client.isConnected()) {
            client.disconnect();
        }
        settings.setActiveProfile(index);
        settings.save();
        settings.markConfigured();
        client.updateSettings(settings);
        showCanvas();
        client.connectFromProfile();
    }

    public void showProfileForm(int index) {
        if (display == null) {
            return;
        }
        display.setCurrent(buildProfileForm(index));
    }    // Construir sin mostrar: para avisar antes con setCurrent(aviso, form).
    private Form buildProfileForm(int index) {
        if (client == null) {
            client = new IrcClient(settings);
        }
        if (canvas == null) {
            canvas = new IrcCanvas(this, client);
        }
        editingProfile = index;
        IrcProfile profile = index >= 0 ? settings.getProfileAt(index) : new IrcProfile();
        if (profile == null) {
            profile = new IrcProfile();
        }
        profileForm = new Form(index < 0 ? IrcStrings.get(IrcStrings.TITLE_NEW_PROFILE)
            : IrcStrings.get(IrcStrings.TITLE_EDIT_PROFILE));
        profileNameField = new TextField(IrcStrings.get(IrcStrings.FLD_PROFILE_NAME), profile.getName(),
            32, TextField.ANY);
        hostField = new TextField(IrcStrings.get(IrcStrings.FLD_SERVER), profile.getHost(), 128,
            TextField.ANY);
        portField = new TextField(IrcStrings.get(IrcStrings.FLD_PORT),
            Integer.toString(profile.getPort()), 5, TextField.NUMERIC);
        nickField = new TextField(IrcStrings.get(IrcStrings.FLD_NICK), profile.getNick(), 32,
            TextField.ANY);
        userField = new TextField(IrcStrings.get(IrcStrings.FLD_USER), profile.getUser(), 32,
            TextField.ANY);
        realNameField = new TextField(IrcStrings.get(IrcStrings.FLD_REALNAME), profile.getRealName(),
            48, TextField.ANY);
        passwordField = new TextField(IrcStrings.get(IrcStrings.FLD_PASSWORD), profile.getPassword(),
            64, TextField.PASSWORD);
        channelsField = new TextField(IrcStrings.get(IrcStrings.FLD_CHANNELS), profile.getChannels(),
            256, TextField.ANY);
        profileForm.append(profileNameField);
        profileForm.append(hostField);
        profileForm.append(portField);
        profileForm.append(nickField);
        profileForm.append(userField);
        profileForm.append(realNameField);
        profileForm.append(passwordField);
        profileForm.append(channelsField);
        profileSaveCommand = new Command(IrcStrings.get(IrcStrings.CMD_SAVE), Command.OK, 1);
        profileConnectCommand = new Command(IrcStrings.get(IrcStrings.CMD_SAVE_CONNECT), Command.SCREEN,
            2);
        profileCancelCommand = new Command(IrcStrings.get(IrcStrings.CMD_CANCEL), Command.BACK, 3);
        profileForm.addCommand(profileSaveCommand);
        profileForm.addCommand(profileConnectCommand);
        profileForm.addCommand(profileCancelCommand);
        profileForm.setCommandListener(this);
        return profileForm;
    }

    private void saveProfile(boolean connectAfterSave) {
        int port;
        try {
            port = Integer.parseInt(portField.getString().trim());
        } catch (NumberFormatException exception) {
            showNotice(IrcStrings.get(IrcStrings.ERR_PORT_NUMBER));
            return;
        }
        if (port < 1 || port > 65535) {
            showNotice(IrcStrings.get(IrcStrings.ERR_PORT_INVALID));
            return;
        }
        IrcProfile profile = new IrcProfile();
        profile.setName(profileNameField.getString());
        profile.setHost(hostField.getString());
        profile.setPort(port);
        profile.setNick(nickField.getString());
        profile.setUser(userField.getString());
        profile.setRealName(realNameField.getString());
        profile.setPassword(passwordField.getString());
        profile.setChannels(channelsField.getString());
        if (!profile.isUsable()) {
            showNotice(IrcStrings.get(IrcStrings.ERR_MISSING_SERVER_NICK));
            return;
        }
        int index = editingProfile;
        if (index < 0) {
            if (settings.getProfileCount() >= IrcSettings.MAX_PROFILES) {
                showNotice(IrcStrings.get(IrcStrings.ERR_MAX_PROFILES));
                return;
            }
            settings.addProfile(profile);
            index = settings.getProfileCount() - 1;
        } else {
            settings.setProfileAt(index, profile);
        }
        settings.setActiveProfile(index);
        settings.save();
        settings.markConfigured();
        client.updateSettings(settings);
        client.applyProfileNick();
        if (!settings.isLastSaveOk()) {
            showNotice(IrcStrings.get(IrcStrings.ERR_SAVE_FAILED));
        }
        if (connectAfterSave) {
            connectToProfile(index);
        } else {
            showProfiles();
        }
    }

    public void showPrefs() {
        showPrefs(false);
    }

    public void showPrefs(boolean fromMenu) {
        if (display == null) {
            return;
        }
        prefsFromMenu = fromMenu;
        prefsForm = new Form(IrcStrings.get(IrcStrings.TITLE_INTERFACE));
        historyField = new TextField(IrcStrings.get(IrcStrings.FLD_MSG_WINDOW),
            Integer.toString(settings.getHistoryLimit()), 3, TextField.NUMERIC);
        eventsChoice = new ChoiceGroup(IrcStrings.get(IrcStrings.GRP_EVENTS), Choice.EXCLUSIVE,
            new String[] { IrcStrings.get(IrcStrings.EVT_HIDE_ALL),
                IrcStrings.get(IrcStrings.EVT_HIDE_JOINS),
                IrcStrings.get(IrcStrings.EVT_SHOW_ALL) }, null);
        eventsChoice.setSelectedIndex(eventsIndex(settings.isTextOnlyMode(), settings.getJoinMode()),
            true);
        styleChoice = new ChoiceGroup(IrcStrings.get(IrcStrings.GRP_JOINSTYLE), Choice.EXCLUSIVE,
            new String[] { IrcStrings.get(IrcStrings.JOIN_FINE),
                IrcStrings.get(IrcStrings.JOIN_COMPACT),
                IrcStrings.get(IrcStrings.JOIN_NORMAL) }, null);
        styleChoice.setSelectedIndex(styleIndex(settings.getJoinMode()), true);
        alertChoice = new ChoiceGroup(IrcStrings.get(IrcStrings.GRP_ALERTS), Choice.EXCLUSIVE,
            new String[] { IrcStrings.get(IrcStrings.ALERT_OFF),
                IrcStrings.get(IrcStrings.ALERT_SOUND),
                IrcStrings.get(IrcStrings.ALERT_VIBRATE) }, null);
        alertChoice.setSelectedIndex(settings.getAlertMode(), true);
        reconnectChoice = new ChoiceGroup(IrcStrings.get(IrcStrings.GRP_RECONNECT),
            Choice.EXCLUSIVE, new String[] { IrcStrings.get(IrcStrings.OPT_YES),
                IrcStrings.get(IrcStrings.OPT_NO) }, null);
        reconnectChoice.setSelectedIndex(
            settings.getReconnectMode() == IrcSettings.RECONNECT_ON ? 0 : 1, true);
        boldChoice = new ChoiceGroup(IrcStrings.get(IrcStrings.GRP_NICKBOLD),
            Choice.EXCLUSIVE, new String[] { IrcStrings.get(IrcStrings.OPT_YES),
                IrcStrings.get(IrcStrings.OPT_NO) }, null);
        boldChoice.setSelectedIndex(settings.isNickBold() ? 0 : 1, true);
        fontChoice = new ChoiceGroup(IrcStrings.get(IrcStrings.GRP_FONT), Choice.EXCLUSIVE,
            new String[] { IrcStrings.get(IrcStrings.FONT_MINI),
                IrcStrings.get(IrcStrings.FONT_SMALL), IrcStrings.get(IrcStrings.FONT_NORMAL),
                IrcStrings.get(IrcStrings.FONT_LARGE) }, null);
        fontChoice.setSelectedIndex(settings.getFontSize(), true);
        chanLimitField = new TextField(IrcStrings.get(IrcStrings.FLD_MAX_CHAN),
            Integer.toString(settings.getChanLimit()), 4, TextField.NUMERIC);
        maxWindowsField = new TextField(IrcStrings.get(IrcStrings.FLD_MAX_WIN),
            Integer.toString(settings.getMaxWindows()), 2, TextField.NUMERIC);
        refreshField = new TextField(IrcStrings.get(IrcStrings.FLD_REFRESH),
            Integer.toString(settings.getRepaintMinMs()), 5, TextField.NUMERIC);
        bgChoice = new ChoiceGroup(IrcStrings.get(IrcStrings.GRP_BG), Choice.EXCLUSIVE,
            new String[] { IrcStrings.get(IrcStrings.BG_SAVE),
                IrcStrings.get(IrcStrings.BG_KEEP) }, null);
        bgChoice.setSelectedIndex(settings.getBackgroundMode(), true);
        indicatorChoice = new ChoiceGroup(IrcStrings.get(IrcStrings.GRP_INDICATOR),
            Choice.EXCLUSIVE, new String[] { IrcStrings.get(IrcStrings.IND_NUM),
                IrcStrings.get(IrcStrings.IND_BARS), IrcStrings.get(IrcStrings.IND_BOTH) }, null);
        indicatorChoice.setSelectedIndex(settings.getWinIndicator(), true);
        marginField = new TextField(IrcStrings.get(IrcStrings.FLD_MARGIN),
            Integer.toString(settings.getBarMargin()), 2, TextField.NUMERIC);
        langChoice = new ChoiceGroup(IrcStrings.get(IrcStrings.GRP_LANGUAGE), Choice.EXCLUSIVE,
            new String[] { "Español", "English" }, null);
        langChoice.setSelectedIndex(settings.getLanguage() == IrcSettings.LANG_EN ? 1 : 0, true);
        prefsForm.append(historyField);
        prefsForm.append(eventsChoice);
        prefsForm.append(styleChoice);
        prefsForm.append(alertChoice);
        prefsForm.append(reconnectChoice);
        prefsForm.append(boldChoice);
        prefsForm.append(fontChoice);
        prefsForm.append(chanLimitField);
        prefsForm.append(maxWindowsField);
        prefsForm.append(refreshField);
        prefsForm.append(bgChoice);
        prefsForm.append(indicatorChoice);
        prefsForm.append(marginField);
        prefsForm.append(langChoice);
        prefsSaveCommand = new Command(IrcStrings.get(IrcStrings.CMD_SAVE), Command.OK, 1);
        prefsBackCommand = new Command(IrcStrings.get(IrcStrings.CMD_BACK_SHORT), Command.BACK, 2);
        prefsForm.addCommand(prefsSaveCommand);
        prefsForm.addCommand(prefsBackCommand);
        prefsForm.setCommandListener(this);
        display.setCurrent(prefsForm);
    }

    // Mapeo formulario <-> ajustes (probado sin UI): los RMS viejos
    // (textOnly x joinMode) entran solos, sin migracion de formato.
    static int eventsIndex(boolean textOnly, int joinMode) {
        if (textOnly) {
            return 0;
        }
        if (joinMode == IrcSettings.JOIN_HIDDEN) {
            return 1;
        }
        return 2;
    }

    static int styleIndex(int joinMode) {
        if (joinMode < IrcSettings.JOIN_COMPACT_FINE || joinMode > IrcSettings.JOIN_NORMAL) {
            return IrcSettings.JOIN_COMPACT;
        }
        return joinMode;
    }

    static boolean saveTextOnly(int events) {
        return events == 0;
    }

    static int saveJoinMode(int events, int style, int currentJoinMode) {
        if (events == 1) {
            return IrcSettings.JOIN_HIDDEN;
        }
        if (events == 2) {
            if (style < IrcSettings.JOIN_COMPACT_FINE || style > IrcSettings.JOIN_NORMAL) {
                return IrcSettings.JOIN_COMPACT;
            }
            return style;
        }
        return currentJoinMode;
    }

    private void savePrefs() {
        int history;
        int chanLimit;
        int maxWindows;
        int refreshMs;
        int barMargin;
        try {
            history = Integer.parseInt(historyField.getString().trim());
            chanLimit = Integer.parseInt(chanLimitField.getString().trim());
            maxWindows = Integer.parseInt(maxWindowsField.getString().trim());
            refreshMs = Integer.parseInt(refreshField.getString().trim());
            barMargin = Integer.parseInt(marginField.getString().trim());
        } catch (NumberFormatException exception) {
            showNotice(IrcStrings.get(IrcStrings.ERR_ALL_NUMBERS));
            return;
        }
        if (history < IrcSettings.MIN_HISTORY || history > IrcSettings.MAX_HISTORY) {
            showNotice(IrcStrings.get(IrcStrings.ERR_LINES));
            return;
        }
        if (chanLimit < IrcSettings.MIN_CHAN_LIMIT || chanLimit > IrcSettings.MAX_CHAN_LIMIT) {
            showNotice(IrcStrings.get(IrcStrings.ERR_CHANS));
            return;
        }
        if (maxWindows < IrcSettings.MIN_WINDOW_LIMIT || maxWindows > IrcSettings.MAX_WINDOW_LIMIT) {
            showNotice(IrcStrings.get(IrcStrings.ERR_WINDOWS));
            return;
        }
        if (refreshMs < IrcSettings.MIN_REPAINT_MS || refreshMs > IrcSettings.MAX_REPAINT_MS) {
            showNotice(IrcStrings.get(IrcStrings.ERR_REFRESH));
            return;
        }
        if (barMargin < IrcSettings.MIN_BAR_MARGIN || barMargin > IrcSettings.MAX_BAR_MARGIN) {
            showNotice(IrcStrings.get(IrcStrings.ERR_MARGIN));
            return;
        }
        settings.setHistoryLimit(history);
        int events = eventsChoice.getSelectedIndex();
        int style = styleChoice.getSelectedIndex();
        settings.setTextOnlyMode(saveTextOnly(events));
        settings.setJoinMode(saveJoinMode(events, style, settings.getJoinMode()));
        settings.setFontSize(fontChoice.getSelectedIndex());
        settings.setAlertMode(alertChoice.getSelectedIndex());
        settings.setReconnectMode(reconnectChoice.getSelectedIndex() == 0
            ? IrcSettings.RECONNECT_ON : IrcSettings.RECONNECT_OFF);
        settings.setChanLimit(chanLimit);
        settings.setMaxWindows(maxWindows);
        settings.setRepaintMinMs(refreshMs);
        settings.setBackgroundMode(bgChoice.getSelectedIndex());
        settings.setNickBold(boldChoice.getSelectedIndex() == 0);
        settings.setWinIndicator(indicatorChoice.getSelectedIndex());
        settings.setBarMargin(barMargin);
        settings.setLanguage(langChoice.getSelectedIndex() == 1
            ? IrcSettings.LANG_EN : IrcSettings.LANG_ES);
        settings.save();
        // El idioma se recarga aqui: las pantallas se reconstruyen al
        // reabrirse y ya nacen traducidas.
        IrcStrings.load(settings.getLanguage());
        if (!settings.isLastSaveOk()) {
            settings.save();
        }
        if (client != null) {
            client.updateSettings(settings);
        }
        if (prefsFromMenu) {
            prefsFromMenu = false;
            if (canvas != null) {
                canvas.showCanvas();
            } else {
                showCanvas();
            }
        } else {
            showProfiles();
        }
    }

    // Ayuda como las demas pantallas de texto: ver IrcHelp.
    public void showHelp() {
        try {
            display.setCurrent(new IrcHelp(this));
        } catch (Throwable stuck) {
            // Si el canvas fallara en este terminal, volver a perfiles.
            closeHelp();
        }
    }

    public void closeHelp() {
        if (helpFromProfiles) {
            helpFromProfiles = false;
            showProfiles();
        } else {
            showCanvas();
        }
    }

    public void showNotice(String value) {
        showNotice(value, null);
    }

    public void showNotice(String value, Displayable next) {
        if (display == null || value == null) {
            return;
        }
        // Sin AlertType: con INFO el telefono emite su propio aviso al
        // mostrar el modal (igual que pasaba con la memoria).
        Alert alert = new Alert("KinIRC", value, null, null);
        alert.setTimeout(2500);
        if (next == null) {
            display.setCurrent(alert);
        } else {
            // Con siguiente: el aviso se lee y al cerrarse va a la
            // pantalla indicada, en vez de morir tapado por ella.
            display.setCurrent(alert, next);
        }
    }

    public void exit() {
        // destroyApp ya desconecta: llamar dos veces solo duplicaba QUIT.
        destroyApp(true);
        notifyDestroyed();
    }

    public void commandAction(Command command, Displayable source) {
        if (source == langList) {
            pickLanguage(langList.getSelectedIndex());
            return;
        }
        if (source == profileList) {
            if (command == List.SELECT_COMMAND) {
                connectToProfile(profileList.getSelectedIndex());
            } else if (command == listNewCommand) {
                showProfileForm(-1);
            } else if (command == listEditCommand) {
                showProfileForm(selectedProfile());
            } else if (command == listDeleteCommand) {
                deleteSelectedProfile();
            } else if (command == listOptionsCommand) {
                showPrefs();
            } else if (command == listHelpCommand) {
                helpFromProfiles = true;
                showHelp();
            } else if (command == listExitCommand) {
                exit();
            }
            return;
        }
        if (source == profileForm) {
            if (command == profileSaveCommand) {
                saveProfile(false);
            } else if (command == profileConnectCommand) {
                saveProfile(true);
            } else if (command == profileCancelCommand) {
                showProfiles();
            }
            return;
        }
        if (source == prefsForm) {
            if (command == prefsSaveCommand) {
                savePrefs();
            } else if (command == prefsBackCommand) {
                if (prefsFromMenu) {
                    prefsFromMenu = false;
                    if (canvas != null) {
                        canvas.showCanvas();
                    } else {
                        showCanvas();
                    }
                } else {
                    showProfiles();
                }
            }
            return;
        }
        if (source == actionList) {
            int selected = actionList.getSelectedIndex();
            if (command == List.SELECT_COMMAND) {
                launchAction(selected);
            } else if (command == actionNewCommand) {
                showActionForm(-1);
            } else if (command == actionEditCommand) {
                if (selected >= 0 && selected < actionStore.size()) {
                    showActionForm(selected);
                }
            } else if (command == actionDeleteCommand) {
                if (selected >= 0 && selected < actionStore.size()) {
                    actionStore.remove(selected);
                    actionStore.save();
                    refreshActionList();
                }
            } else if (command == actionBackCommand) {
                showCanvas();
            }
            return;
        }
        if (source == actionBox) {
            if (command == actionSaveCommand) {
                saveActionForm();
            } else if (command == actionCancelCommand) {
                showActions();
            }
        }
    }

    private void deleteSelectedProfile() {
        int index = selectedProfile();
        if (index < 0 || index >= settings.getProfileCount()) {
            return;
        }
        if (client.isConnected() && index == settings.getActiveIndex()) {
            client.disconnect();
        }
        settings.deleteProfile(index);
        settings.save();
        if (settings.getProfileCount() == 0) {
            showProfileForm(-1);
        } else {
            refreshProfileList();
        }
    }

    public void showActions() {
        actionList = new List(IrcStrings.get(IrcStrings.TITLE_ACTIONS), List.IMPLICIT);
        refreshActionList();
        actionNewCommand = new Command(IrcStrings.get(IrcStrings.CMD_NEW), Command.OK, 1);
        actionEditCommand = new Command(IrcStrings.get(IrcStrings.CMD_EDIT), Command.SCREEN, 2);
        actionDeleteCommand = new Command(IrcStrings.get(IrcStrings.CMD_DELETE), Command.SCREEN, 3);
        actionBackCommand = new Command(IrcStrings.get(IrcStrings.CMD_BACK), Command.BACK, 4);
        actionList.addCommand(actionNewCommand);
        actionList.addCommand(actionEditCommand);
        actionList.addCommand(actionDeleteCommand);
        actionList.addCommand(actionBackCommand);
        actionList.setCommandListener(this);
        display.setCurrent(actionList);
    }

    private void refreshActionList() {
        actionList.deleteAll();
        int i = 0;
        while (i < actionStore.size()) {
            actionList.append(actionStore.get(i), null);
            i++;
        }
        if (actionStore.size() == 0) {
            actionList.append(IrcStrings.get(IrcStrings.ACT_EMPTY), null);
        }
    }

    private void launchAction(int index) {
        if (index < 0 || index >= actionStore.size()) {
            return;
        }
        showCanvas();
        client.runAction(actionStore.get(index));
    }

    private void showActionForm(int index) {
        editingAction = index;
        String text = index >= 0 ? actionStore.get(index) : "";
        actionBox = new TextBox(IrcStrings.get(IrcStrings.TITLE_ACTION), text, 240, TextField.ANY);
        IrcDraw.lowerCaseInput(actionBox);
        actionSaveCommand = new Command(IrcStrings.get(IrcStrings.CMD_SAVE), Command.OK, 1);
        actionCancelCommand = new Command(IrcStrings.get(IrcStrings.CMD_CANCEL), Command.BACK, 2);
        actionBox.addCommand(actionSaveCommand);
        actionBox.addCommand(actionCancelCommand);
        actionBox.setCommandListener(this);
        display.setCurrent(actionBox);
    }

    private void saveActionForm() {
        if (editingAction < 0) {
            actionStore.add(actionBox.getString());
        } else {
            actionStore.set(editingAction, actionBox.getString());
        }
        actionStore.save();
        showActions();
    }

    // En hilo propio: el tono/vibracion bloquea ~180-400 ms y antes
    // congelaba el pintado al ir dentro del hilo de red (handleEvent).
    public void playAlert() {
        if (client == null || !client.isConnected()) {
            return;
        }
        final int mode = settings.getAlertMode();
        if (mode != IrcSettings.ALERT_SOUND && mode != IrcSettings.ALERT_VIBRATE) {
            return;
        }
        Thread beep = new Thread(new Runnable() {
            public void run() {
                playAlertNow(mode);
            }
        });
        try {
            beep.start();
        } catch (Exception e) {
            // Sin hilos: se omite el aviso.
        }
    }

    private void playAlertNow(int mode) {
        try {
            if (mode == IrcSettings.ALERT_SOUND) {
                try {
                    javax.microedition.media.Manager.playTone(76, 180, 100);
                } catch (Throwable stuck) {
                    // MMAPI opcional: sin ella no hay Exception sino Error
                    // (NoClassDefFoundError) y mataria el hilo en bucle.
                }
            } else if (mode == IrcSettings.ALERT_VIBRATE) {
                try {
                    if (display != null) {
                        display.vibrate(400);
                    }
                } catch (Throwable stuck) {
                    // Vibracion no soportada: se ignora.
                }
            }
        } catch (Exception e) {
            // Nunca matar el hilo del aviso por una excepcion.
        }
    }
}
