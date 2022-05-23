import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.awt.Color;

/**
 * An QOI encoder and decoder written in Java.
 * QOI Documentation: https://qoiformat.org/qoi-specification.pdf
 * Tutorial referenced (TS implementation):
 * https://www.youtube.com/watch?v=GgsRQuGSrc0
 */

public class QOI {

    final static int QOI_HEADER_SIZE = 14; // header size is 14 bytes
    final static byte[] QOI_END_MARKER = { 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x1 };
    final static int QOI_END_MARKER_SIZE = QOI_END_MARKER.length;

    // QOI chunck tags
    final static byte QOI_OP_RUN = (byte) 0xc0; // 0b11000000
    final static byte QOI_OP_INDEX = (byte) 0x00; // 0b00000000
    final static byte QOI_OP_DIFF = (byte) 0x40; // 0b01000000
    final static byte QOI_OP_LUMA = (byte) 0x80; // 0b10000000
    final static byte QOI_OP_RGB = (byte) 0xfe; // 0b11111110
    final static byte QOI_OP_RGBA = (byte) 0xff; // 0b11111111

    // Returns a difference in Color as an byte array: {Red, Green, Blue, Alpha}
    private static byte[] colorDiff(Color a, Color b) {
        // NOTE: Must cast to byte to take advantage of wrap around
        byte[] diff = {
                (byte) (a.getRed() - b.getRed()),
                (byte) (a.getGreen() - b.getGreen()),
                (byte) (a.getBlue() - b.getBlue()),
                (byte) (a.getAlpha() - b.getAlpha())
        };
        return diff;
    } // colorDiff

    public static byte[] encode(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();

        ColorModel cm = img.getColorModel();
        int channels = cm.getNumComponents();

        int imageSize = width * height * channels;
        int lastPixel = imageSize - channels;

        Color prevColor = new Color(0, 0, 0, 255);
        int run = 0;
        Color[] seenPixels = new Color[64];

        // Initialize all Color in seenPixels to be the color black
        for (int i = 0; i < seenPixels.length; i++) {
            seenPixels[i] = new Color(0, 0, 0, 0);
        } // for

        int maxSize = width * height * (channels + 1) + QOI_HEADER_SIZE + QOI_END_MARKER_SIZE;
        byte[] bytes = new byte[maxSize]; // Stores all bytes of the encoded QOI
        int index = 0;

        // Write the header
        ByteBuffer headerByteBuffer = ByteBuffer.allocate(14); // NOTE: initial order of a byte buffer is BIG_ENDIAN.
        headerByteBuffer.put(new String("qoif").getBytes()); // magic bytes "qoif"
        headerByteBuffer.putInt(width); // uint32_t width , image width in pixels (BE)
        headerByteBuffer.putInt(height); // uint32_t height, image height in pixels (BE)
        headerByteBuffer.put((byte) (channels)); // uint8_t channels, 3 = RGB, 4 = RGBA
        headerByteBuffer.put((byte) (0)); // uint8_t colorspace, 0 = sRGB with linear alpha, 1 = all channels linear

        // Add header into bytes array
        byte[] header = headerByteBuffer.array();

        for (byte b : header) {
            bytes[index++] = b;
        } // for

        int offset = 0; // The current pixel number counter

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Color color = new Color(img.getRGB(x, y), channels == 4);

                // Run Length Encoding:
                // If the current pixel is the same as the previous pixel, increase the run.
                if (color.equals(prevColor)) {
                    run++;
                    if (run == 62 || offset == lastPixel) {
                        bytes[index++] = (byte) (QOI_OP_RUN | (run - 1));
                        run = 0;
                    }

                    // There is a difference btween the current and previous pixel: cannot use Run
                    // Length Encoding.
                } else {
                    // If there is an existing run, but current pixel have changed,
                    // encode the run (and resets it) before moving onto the current pixel
                    if (run > 0) {
                        bytes[index++] = (byte) (QOI_OP_RUN | (run - 1));
                        run = 0;
                    }

                    // Hash/index position in seenPixels (cache)
                    int hash = (color.getRed() * 3 + color.getGreen() * 5 + color.getBlue() * 7 + color.getAlpha() * 11)
                            % 64;

                    // If color found in array of previously seen pixels, encode as reference to
                    // index in array.
                    // Otherwise, this is a new color; procced to other compression methods
                    if (color.equals(seenPixels[hash])) {
                        bytes[index++] = (byte) (QOI_OP_INDEX | hash);
                    } else {
                        seenPixels[hash] = color; // Record color into seenPixels.

                        byte[] diff = colorDiff(color, prevColor); // RGBA difference from the previous pixel. Stored as
                                                                   // byte array: {Red, Green, Blue, Alpha}
                        int dr_dg = diff[0] - diff[1]; // red channel difference minus green channel difference
                        int db_dg = diff[2] - diff[1]; // blue channel difference minus green channel difference

                        // Only encode the difference in color(RGB or luma) if alpha is the same.
                        // Otherwise, encode the full RGBA value of this pixel.
                        if (diff[3] == 0) {

                            // Encode differece in RGB if in range (small enough).
                            // Else, try to encode as difference in luma if in range (small enough).
                            // If all fails, fall back to encoding the full RGB values.
                            if ((diff[0] >= -2 && diff[0] <= 1)
                                    && (diff[1] >= -2 && diff[1] <= 1)
                                    && (diff[2] >= -2 && diff[2] <= 1)) {

                                bytes[index++] = (byte) (QOI_OP_DIFF
                                        | ((diff[0] + 2) << 4)
                                        | ((diff[1] + 2) << 2)
                                        | ((diff[2] + 2) << 0));

                            } else if ((diff[1] >= -32 && diff[1] <= 31)
                                    && (dr_dg >= -8 && dr_dg <= 7)
                                    && (db_dg >= -8 && db_dg <= 7)) {

                                bytes[index++] = (byte) (QOI_OP_LUMA
                                        | (diff[1] + 32));

                                bytes[index++] = (byte) (((dr_dg + 8) << 4) | (db_dg + 8));

                            } else {
                                bytes[index++] = QOI_OP_RGB;
                                bytes[index++] = (byte) color.getRed();
                                bytes[index++] = (byte) color.getGreen();
                                bytes[index++] = (byte) color.getBlue();
                            } // if elses - difference in chroma, luma, or full RGB

                        } else {
                            // Cannot use any other encoding method
                            // Encode as full RGBA pixel
                            bytes[index++] = QOI_OP_RGBA;
                            bytes[index++] = (byte) color.getRed();
                            bytes[index++] = (byte) color.getGreen();
                            bytes[index++] = (byte) color.getBlue();
                            bytes[index++] = (byte) color.getAlpha();
                        } // if else - difference (alpha)
                    } // if else - hash table

                } // if else - run length

                prevColor = color;
                offset++;
            } // for - y
        } // for - x

        // Write end marker
        for (byte b : QOI_END_MARKER) {
            bytes[index++] = b;
        } // for

        return Arrays.copyOf(bytes, index);
    } // encode

    public static void write(BufferedImage img, String filepath) throws IOException {
        // Get encoded bytes via QOI.encode()
        byte[] bytes = QOI.encode(img);

        // Write byte array as QOI file
        File outputFile = new File(filepath);
        FileOutputStream outputStream = new FileOutputStream(outputFile);
        BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream);
        bufferedOutputStream.write(bytes);
        bufferedOutputStream.close();
    } // write

    public static void main(String[] args) throws IOException {
        File file = new File("qoi_test_images/wikipedia_008.png");
        BufferedImage img = ImageIO.read(file);
        QOI.write(img, "testout.bin");
    } // main
}
