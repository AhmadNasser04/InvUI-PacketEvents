package xyz.xenondevs.invui.internal.util;

import java.io.DataInputStream;
import java.io.IOException;

public final class DataUtils {
    private DataUtils() {}

    public static byte[] readByteArray(DataInputStream din) throws IOException {
        int size = din.readInt();
        byte[] array = new byte[size];
        din.readFully(array);
        return array;
    }

    public static byte[][] read2DByteArray(DataInputStream din) throws IOException {
        int size2d = din.readInt();
        byte[][] array2d = new byte[size2d][];
        for (int i = 0; i < size2d; i++) {
            array2d[i] = readByteArray(din);
        }
        return array2d;
    }
}
