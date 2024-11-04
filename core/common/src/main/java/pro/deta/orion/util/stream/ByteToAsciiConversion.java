package pro.deta.orion.util.stream;

public class ByteToAsciiConversion {
    public static final String LINE_SEPARATOR = System.getProperty("line.separator");

    public static final String NON_PRINTABLE = ".";
    public static final String[] ASCII_CONSTANTS = { " ", "!", "\"", "#", "$", "%", "&", "'", "(", ")", "*", "+", ",", "-",
            ".", "/", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ":", ";", "<", "=", ">", "?", "@", "A", "B",
            "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W",
            "X", "Y", "Z", "[", "\\", "]", "^", "_", "`", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k",
            "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "{", "|", "}", "~" };

    public static String byteToAscii(int value) {
        if (isAsciiPrintable(value)) {
            return ASCII_CONSTANTS[value - 32];
        } else {
            return NON_PRINTABLE;
        }
    }

    public static boolean isAsciiPrintable(int value) {
        return value >= 32 && value <= 126;
    }
}
