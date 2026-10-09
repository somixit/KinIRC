package kinirc;

// Mide el ancho en pixeles de un texto. Existe para poder probar el
// ajuste de lineas sin una pantalla (Font de MIDP no existe en Java SE).
public interface IrcWidth {
    int stringWidth(String value);

    int substringWidth(String value, int offset, int length);
}
