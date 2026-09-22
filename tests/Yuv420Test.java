import com.smartcam.capture.Yuv420;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class Yuv420Test {
    private static void equal(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) throw new AssertionError(Arrays.toString(actual));
    }
    public static void main(String[] args) {
        // Padded Y rows; last row has no trailing padding (common Android buffer layout).
        ByteBuffer padded = ByteBuffer.wrap(new byte[]{1,2,3,99,99,4,5,6});
        byte[] output = new byte[6];
        int end = Yuv420.copyPlane(padded, 5, 1, 0, 0, 3, 2, output, 0);
        equal(new byte[]{1,2,3,4,5,6}, output);
        if (end != 6 || padded.position() != 0) throw new AssertionError();
        // Interleaved chroma with a non-zero buffer position and absent final padding.
        ByteBuffer chroma = ByteBuffer.wrap(new byte[]{99,10,90,11,91,99,99,12,92,13});
        chroma.position(1);
        output = new byte[4];
        Yuv420.copyPlane(chroma, 6, 2, 0, 0, 2, 2, output, 0);
        equal(new byte[]{10,11,12,13}, output);
        if (chroma.position() != 1) throw new AssertionError();
        // Cropped plane plus destination offset.
        output = new byte[3];
        Yuv420.copyPlane(ByteBuffer.wrap(new byte[]{0,1,2,3,4,5,6,7}), 4, 1, 1, 1, 2, 1, output, 1);
        equal(new byte[]{0,5,6}, output);
        // Buffer limit, rather than capacity, must bound reads.
        ByteBuffer limited = ByteBuffer.allocate(20); limited.limit(3);
        try {
            Yuv420.copyPlane(limited, 4, 1, 0, 0, 2, 2, new byte[4], 0);
            throw new AssertionError("Expected bounds rejection");
        } catch (IllegalArgumentException expected) { }
        try {
            Yuv420.copyPlane(padded, 5, 1, 0, 0, 3, 2, new byte[5], 0);
            throw new AssertionError("Expected destination rejection");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: padded rows, interleaved chroma, crop, positions and bounds");
    }
}
