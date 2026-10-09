package kinirc;

import java.util.Vector;

public final class IrcText {
    private IrcText() {
    }

    // Buffer de trabajo compartido: decode/stripCodes/clean escriben aqui
    // y solo asignan el String resultado. Sin StringBuffer ni substrings
    // intermedios por linea de red. CLDC no tiene ThreadLocal, asi que el
    // acceso va sincronizado (secciones cortas entre hilo de red y UI).
    // La salida nunca supera la entrada, asi que basta una pasada.
    private static char[] sharedChars = new char[512];

    private static char[] scratch(int needed) {
        if (sharedChars.length < needed) {
            sharedChars = new char[needed];
        }
        return sharedChars;
    }

    public static synchronized String decode(byte[] data, int offset, int length) {
		char[] buf = scratch(length);
		int n = 0;
		int end = offset + length;
		int i = offset;
		while (i < end) {
			int b = data[i] & 255;
			if (b < 128) {
				buf[n++] = (char) b;
				i++;
				continue;
			}
			if ((b & 224) == 192) {
				if (i + 1 < end) { // Carácter completo
					int b1 = data[i + 1] & 255;
					if ((b1 & 192) == 128) {
						int c = ((b & 31) << 6) | (b1 & 63);
						if (c >= 128) {
							buf[n++] = (char) c;
							i += 2;
							continue;
						}
					}
				} else { break; } // <--- OPTIMIZACIÓN GPRS: Corta de raíz si falta el siguiente byte
			}
			if ((b & 240) == 224) {
				if (i + 2 < end) { // Carácter completo
					int b1 = data[i + 1] & 255;
					int b2 = data[i + 2] & 255;
					if ((b1 & 192) == 128 && (b2 & 192) == 128) {
						int c = ((b & 15) << 12) | ((b1 & 63) << 6) | (b2 & 63);
						if (c >= 2048 && c <= 65535) {
							buf[n++] = (char) c;
							i += 3;
							continue;
						}
					}
				} else { break; } // <--- OPTIMIZACIÓN GPRS: Ignora fragmento roto al final de la línea
			}
			if ((b & 248) == 240) {
				if (i + 3 < end) { // Carácter completo
					int b1 = data[i + 1] & 255;
					int b2 = data[i + 2] & 255;
					int b3 = data[i + 3] & 255;
					if ((b1 & 192) == 128 && (b2 & 192) == 128 && (b3 & 192) == 128) {
						int c = ((b & 7) << 18) | ((b1 & 63) << 12) | ((b2 & 63) << 6) | (b3 & 63);
						if (c >= 65536 && c <= 1114111) {
							c -= 65536;
							buf[n++] = (char) (55296 + (c >> 10));
							buf[n++] = (char) (56320 + (c & 1023));
							i += 4;
							continue;
						}
					}
				} else { break; } // <--- OPTIMIZACIÓN GPRS: Evita meter caracteres corruptos si la red se traba
			}
			buf[n++] = (char) b;
			i++;
		}
		return new String(buf, 0, n);
	}


    public static byte[] encode(String value) {
        byte[] buffer = new byte[value.length() * 3 + 8];
        int length = 0;
        int i = 0;
        while (i < value.length()) {
            int c = value.charAt(i) & 65535;
            if (c < 128) {
                buffer[length++] = (byte) c;
            } else if (c < 2048) {
                buffer[length++] = (byte) (192 | (c >> 6));
                buffer[length++] = (byte) (128 | (c & 63));
            } else {
                buffer[length++] = (byte) (224 | (c >> 12));
                buffer[length++] = (byte) (128 | ((c >> 6) & 63));
                buffer[length++] = (byte) (128 | (c & 63));
            }
            i++;
        }
        byte[] result = new byte[length];
        System.arraycopy(buffer, 0, result, 0, length);
        return result;
    }

    public static synchronized String clean(String value) {
        if (value == null) {
            return "";
        }
        int len = value.length();
        char[] buf = scratch(len);
        // Transforma y recorta a 510 (techo del protocolo IRC: 512 bytes
        // con CRLF), asi un parrafo recibido no se trunca. El envio sigue
        // acotado antes por los cuadros de texto (240-256).
        int n = 0;
        int i = 0;
        while (i < len && n < 510) {
            char c = value.charAt(i);
            buf[n++] = (c == '\r' || c == '\n') ? ' ' : c;
            i++;
        }
        return new String(buf, 0, n);
    }

    // Quita codigos de formato mIRC (negrita, color, etc.) para no
    // ensuciar la pantalla con caracteres raros.
    public static synchronized String stripCodes(String value) {
        if (value == null) {
            return "";
        }
        int len = value.length();
        char[] buf = scratch(len);
        value.getChars(0, len, buf, 0);
        // Lectura y escritura sobre el mismo array (n <= i siempre):
        // sin copias intermedias, misma maquina de estados que antes.
        int n = 0;
        int i = 0;
        while (i < len) {
            char c = buf[i];
            if (c == 2 || c == 15 || c == 22 || c == 29 || c == 31) {
                i++;
            } else if (c == 3) {
                i++;
                int digits = 0;
                while (digits < 2 && i < len
                    && buf[i] >= '0' && buf[i] <= '9') {
                    i++;
                    digits++;
                }
                if (digits > 0 && i < len && buf[i] == ',') {
                    int back = 0;
                    while (back < 2 && i + 1 + back < len
                        && buf[i + 1 + back] >= '0'
                        && buf[i + 1 + back] <= '9') {
                        back++;
                    }
                    if (back > 0) {
                        i += 1 + back;
                    }
                }
            } else {
                buf[n++] = c;
                i++;
            }
        }
        return new String(buf, 0, n);
    }

    public static String trim(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) <= ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    public static String upper(String value) {
        if (value == null) {
            return "";
        }
        StringBuffer result = new StringBuffer(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c >= 'a' && c <= 'z') {
                c -= 32;
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }

    public static String lower(String value) {
        if (value == null) {
            return "";
        }
        StringBuffer result = new StringBuffer(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c += 32;
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }

    public static boolean equalsIgnoreCase(String first, String second) {
        if (first == null || second == null) {
            return first == second;
        }
        if (first.length() != second.length()) {
            return false;
        }
        int i = 0;
        while (i < first.length()) {
            char a = first.charAt(i);
            char b = second.charAt(i);
            if (a >= 'A' && a <= 'Z') {
                a += 32;
            }
            if (b >= 'A' && b <= 'Z') {
                b += 32;
            }
            if (a != b) {
                return false;
            }
            i++;
        }
        return true;
    }

    // Folding RFC1459 para nicks/canal: A-Z -> a-z y []\^ -> {}|~.
    // equalsIgnoreCase solo ASCII fallaba con nicks tipo "Pepe[^]".
    public static char ircLowerChar(char c) {
        if (c >= 'A' && c <= 'Z') {
            return (char) (c + 32);
        }
        if (c == '[') {
            return '{';
        }
        if (c == ']') {
            return '}';
        }
        if (c == '\\') {
            return '|';
        }
        if (c == '^') {
            return '~';
        }
        return c;
    }

    public static String ircLower(String value) {
        if (value == null) {
            return "";
        }
        StringBuffer result = new StringBuffer(value.length());
        int i = 0;
        while (i < value.length()) {
            result.append(ircLowerChar(value.charAt(i)));
            i++;
        }
        return result.toString();
    }

    public static boolean ircEquals(String first, String second) {
        if (first == null || second == null) {
            return first == second;
        }
        if (first.length() != second.length()) {
            return false;
        }
        int i = 0;
        while (i < first.length()) {
            if (ircLowerChar(first.charAt(i)) != ircLowerChar(second.charAt(i))) {
                return false;
            }
            i++;
        }
        return true;
    }

    public static String limitUtf8(String value, int maximumBytes) {
        if (value == null) {
            return "";
        }
        StringBuffer result = new StringBuffer(value.length());
        int bytes = 0;
        int i = 0;
        while (i < value.length()) {
            int c = value.charAt(i) & 65535;
            int needed = c < 128 ? 1 : c < 2048 ? 2 : 3;
            if (bytes + needed > maximumBytes) {
                break;
            }
            result.append(value.charAt(i));
            bytes += needed;
            i++;
        }
        return result.toString();
    }

    public static boolean isAction(String value) {
        if (value == null || value.length() < 8) {
            return false;
        }
        return value.charAt(0) == 1
            && value.substring(1, 7).equals("ACTION")
            && (value.length() == 8 || value.charAt(7) == ' ')
            && value.charAt(value.length() - 1) == 1;
    }

    public static String actionText(String value) {
        if (!isAction(value)) {
            return value;
        }
        return trim(value.substring(7, value.length() - 1));
    }

    public static String[] split(String value, char separator) {
        if (value == null) {
            return new String[0];
        }
        Vector parts = new Vector();
        int start = 0;
        int i = 0;
        while (i <= value.length()) {
            if (i == value.length() || value.charAt(i) == separator) {
                String part = trim(value.substring(start, i));
                if (part.length() > 0) {
                    parts.addElement(part);
                }
                start = i + 1;
            }
            i++;
        }
        String[] result = new String[parts.size()];
        int index = 0;
        while (index < result.length) {
            result[index] = (String) parts.elementAt(index);
            index++;
        }
        return result;
    }
}
