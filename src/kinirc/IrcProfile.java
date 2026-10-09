package kinirc;

public final class IrcProfile {
    private static final java.util.Random RANDOM = new java.util.Random();

    private String name = "";
    private String host = IrcSettings.DEFAULT_HOST;
    private int port = IrcSettings.DEFAULT_PORT;
    private String nick = "";
    private String user = "kinirc";
    private String realName = "KinIRC";
    private String password = "";
    private String channels = "";

    public IrcProfile() {
        String base;
        synchronized (IrcProfile.class) {
            // Random compartido: antes new Random() por copia (IrcSettings
            // llama a copy() mucho) con misma semilla si se crea seguido.
            base = "KinUsr" + (10 + RANDOM.nextInt(90));
        }
        nick = base;
        name = base;
    }

    public IrcProfile copy() {
        IrcProfile result = new IrcProfile();
        result.name = name;
        result.host = host;
        result.port = port;
        result.nick = nick;
        result.user = user;
        result.realName = realName;
        result.password = password;
        result.channels = channels;
        return result;
    }

    public String getName() {
        return name;
    }

    public void setName(String value) {
        name = IrcText.trim(value);
    }

    public String getHost() {
        return host;
    }

    public void setHost(String value) {
        host = IrcText.trim(value);
        if (host.length() == 0) {
            host = IrcSettings.DEFAULT_HOST;
        }
    }

    public int getPort() {
        return port;
    }

    public void setPort(int value) {
        if (value < 1) {
            value = 1;
        }
        if (value > 65535) {
            value = 65535;
        }
        port = value;
    }

    public String getNick() {
        return nick;
    }

    public void setNick(String value) {
        nick = IrcText.trim(value);
    }

    public String getUser() {
        return user;
    }

    public void setUser(String value) {
        user = IrcText.trim(value);
        if (user.length() == 0) {
            user = "kinirc";
        }
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String value) {
        realName = IrcText.trim(value);
        if (realName.length() == 0) {
            realName = "KinIRC";
        }
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String value) {
        password = value == null ? "" : value;
    }

    public String getChannels() {
        return channels;
    }

    public void setChannels(String value) {
        channels = IrcText.trim(value);
    }

    public String[] getChannelArray() {
        return IrcText.split(channels, ',');
    }

    public boolean isUsable() {
        return host.length() > 0 && nick.length() > 0;
    }

    public String getLabel() {
        if (name.length() > 0) {
            return name;
        }
        return nick.length() > 0 ? nick + "@" + host : host;
    }
}
