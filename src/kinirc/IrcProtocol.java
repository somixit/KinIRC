package kinirc;

public final class IrcProtocol {
    public static final int MAX_PARAMS = 16;

    private String prefix = "";
    private String command = "";
    private String text = "";
    private String[] params = new String[MAX_PARAMS];
    private int paramCount;

    private IrcProtocol() {
    }

    public static IrcProtocol parse(String line) {
        if (line == null || line.length() == 0) {
            return null;
        }
        IrcProtocol result = new IrcProtocol();
        int position = 0;
        if (line.charAt(0) == ':') {
            int end = line.indexOf(' ', 1);
            if (end < 0) {
                return null;
            }
            result.prefix = line.substring(1, end);
            position = end + 1;
        }
        if (position >= line.length()) {
            return null;
        }
        int commandEnd = line.indexOf(' ', position);
        if (commandEnd < 0) {
            result.command = IrcText.upper(line.substring(position));
            return result;
        }
        result.command = IrcText.upper(line.substring(position, commandEnd));
        position = commandEnd + 1;
        if (position >= line.length()) {
            return result;
        }
        if (line.charAt(position) == ':') {
            result.text = line.substring(position + 1);
            return result;
        }
        int parameterEnd = line.length();
        int textStart = line.indexOf(" :", position);
        if (textStart >= 0) {
            result.text = line.substring(textStart + 2);
            parameterEnd = textStart;
        }
        while (position < parameterEnd && result.paramCount < MAX_PARAMS) {
            // Espacios duplicados (servidores antiguos, bouncers): se
            // saltan sin generar params vacios que desplazarian todo.
            while (position < parameterEnd && line.charAt(position) == ' ') {
                position++;
            }
            if (position >= parameterEnd) {
                break;
            }
            int end = line.indexOf(' ', position);
            if (end < 0 || end > parameterEnd) {
                result.params[result.paramCount++] = line.substring(position, parameterEnd);
                position = parameterEnd;
            } else {
                result.params[result.paramCount++] = line.substring(position, end);
                position = end + 1;
            }
        }
        return result;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getCommand() {
        return command;
    }

    public String getNick() {
        int bang = prefix.indexOf('!');
        if (bang >= 0) {
            return prefix.substring(0, bang);
        }
        int at = prefix.indexOf('@');
        if (at >= 0) {
            return prefix.substring(0, at);
        }
        return prefix;
    }

    public String getParam(int index) {
        if (index < 0 || index >= paramCount) {
            return "";
        }
        return params[index];
    }

    public int getParamCount() {
        return paramCount;
    }

    public String getText() {
        return text;
    }

    public boolean is(String value) {
        return IrcText.equalsIgnoreCase(command, value);
    }

    public boolean isNumeric() {
        if (command.length() == 0) {
            return false;
        }
        int i = 0;
        while (i < command.length()) {
            if (command.charAt(i) < '0' || command.charAt(i) > '9') {
                return false;
            }
            i++;
        }
        return true;
    }
}
