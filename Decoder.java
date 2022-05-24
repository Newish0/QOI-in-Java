import java.io.FileInputStream;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.awt.Color;

/**
 * An QOI decoder written in Java.
 * QOI Documentation: https://qoiformat.org/qoi-specification.pdf
 */
public class Decoder {

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

    // Converts 4 bytes (BE) to an integer
    private static int intFrom32(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
    }

    private static void setPixel(BufferedImage image, int pixelNumber, Color pixel) {
        int width = image.getWidth();
        int x = pixelNumber % width;
        int y = pixelNumber / width;

        // System.out.println(x + ", " + y + ", " + pixelNumber);

        if (y >= image.getHeight()) {
            System.out.println(x + ", " + y + " p: " + pixelNumber);
            // System.exit(0);
        } else {
            image.setRGB(x, y, pixel.getRGB());
        }
    }

    private static boolean isEndMark(byte[] bytes, int offset) {
        return bytes[offset] == 0
                && bytes[offset + 1] == 0
                && bytes[offset + 2] == 0
                && bytes[offset + 3] == 0
                && bytes[offset + 4] == 0
                && bytes[offset + 5] == 0
                && bytes[offset + 6] == 0
                && bytes[offset + 7] == 1;
    }

    public static BufferedImage decode(byte[] bytes) throws Exception {
        BufferedImage img = null;

        int index = 0; // Keep track of the index in bytes array

        // Header size is 14 bytes, so if input bytes must be at least 14 bytes
        if (bytes.length < 14) {
            throw new QOIHeaderException("Data is not in QOI format.");
        }

        // Read QOI magic bytes to ensure file is an QOI image
        byte[] magicBytes = { 0x71, 0x6F, 0x69, 0x66 };
        for (; index < 4; index++) {
            if (bytes[index] != magicBytes[index]) {
                throw new QOIHeaderException("Data is not marked as QOI format.");
            }
        }

        // Procced to read rest of the header to obtain required meta data
        byte[] widthBytes = new byte[4];
        byte[] heightBytes = new byte[4];
        int width = -1;
        int height = -1;
        int channels = -1;
        int colorspace = -1;

        // Read the 4 bytes representing the width
        for (; index < 8; index++) {
            widthBytes[index - 4] = bytes[index];
        }

        // Read the 4 bytes representing the height
        for (; index < 12; index++) {
            heightBytes[index - 8] = bytes[index];
        }

        // Convert the 4 bytes representing the width into an integer
        width = intFrom32(widthBytes);

        // Convert the 4 bytes representing the height into an integer
        height = intFrom32(heightBytes);

        // Read channels from bytes
        channels = bytes[index++];

        // Read colorspace from bytes
        colorspace = bytes[index++];

        if (width == -1 || height == -1 || channels == -1 || colorspace == -1) {
            throw new QOIHeaderException("Invalid QOI header.");
        }

        if (channels == 3) {
            img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        } else {
            img = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        }

        System.out.println(img);

        Color prevPixel = new Color(0, 0, 0, 255);
        Color[] seenPixels = new Color[64];

        int pixelNumber = 0;

        // Initialize all Color in seenPixels to be the color black
        for (int i = 0; i < seenPixels.length; i++) {
            seenPixels[i] = new Color(0, 0, 0, 0);
        } // for

        // Read until the last 8 ending bits
        while (index < bytes.length - 8 && !isEndMark(bytes, index - 1)) {
            // System.out.println("i: " + index);
            // System.out.println(bytes.length - index);

            if(pixelNumber / width > 858) {
                System.out.println(Arrays.toString(Arrays.copyOfRange(bytes, index - 10, index + 10)));
                break;
            }


            byte curByte = bytes[index++]; // The current byte we are reading
            byte curByte2B = (byte) (curByte & 0b11000000);
            Color color = null; // The current pixel

            // If tag is 1 1, decode as Run Length
            if (curByte2B == QOI_OP_RUN) {
                int run = (curByte & 0b00111111) & 0xff; // Read byte and convert to unsigned byte as an integer.

                color = prevPixel;

                for (int i = 0; i < run; i++) {
                    setPixel(img, pixelNumber, color);
                    pixelNumber++;
                }

                // System.out.println("Run Length: " + curByte);
            } else if (curByte2B == QOI_OP_LUMA) {
                // Read difference in green
                byte dg = (byte) (curByte & 0b00111111);

                // Read the next byte
                byte dr_dg_db_dg = bytes[index++];

                // Read the red channel difference minus green channel difference
                byte dr_dg = (byte) (dr_dg_db_dg >> 4);
                byte db_dg = (byte) (dr_dg_db_dg & 0b00001111);

                // Calculate dr & db
                byte dr = (byte) (dr_dg + dg);
                byte db = (byte) (db_dg + dg);

                // Previous pixel data
                byte pr = (byte) prevPixel.getRed();
                byte pg = (byte) prevPixel.getGreen();
                byte pb = (byte) prevPixel.getBlue();
                int pa = prevPixel.getAlpha();

                // Set current color the difference from the previous pixel
                // after convert the byte to unsigned
                color = new Color((pr - dr) & 0xff, (pg - dg) & 0xff, (pb - db) & 0xff, pa);

                // Write the pixel onto the image
                setPixel(img, pixelNumber++, color);

                // System.out.println("Luma Diff: " + curByte);
            } else if (curByte2B == QOI_OP_DIFF) {
                // Read difference in RGB current byte
                byte dr = (byte) ((curByte & 0b00110000) >> 4);
                byte dg = (byte) ((curByte & 0b00001100) >> 2);
                byte db = (byte) (curByte & 0b00000011);

                // Previous pixel data
                byte pr = (byte) prevPixel.getRed();
                byte pg = (byte) prevPixel.getGreen();
                byte pb = (byte) prevPixel.getBlue();
                int pa = prevPixel.getAlpha();

                // Set current color the difference from the previous pixel
                // after convert the byte to unsigned
                color = new Color((pr - dr) & 0xff, (pg - dg) & 0xff, (pb - db) & 0xff, pa);

                // Write the pixel onto the image
                setPixel(img, pixelNumber++, color);

                // System.out.println("RGB Diff: " + curByte);
            } else if (curByte2B == QOI_OP_INDEX) {
                // Read index from the first 6 bit of the current byte
                int indexInSeenPixels = (curByte & 0b00111111) & 0xff;

                color = seenPixels[indexInSeenPixels];

                // Write the pixel onto the image
                setPixel(img, pixelNumber++, color);

                // System.out.println("Index: " + curByte);
            } else if (curByte == QOI_OP_RGB) {
                // Read RGB from the next 3 bytes
                byte red = bytes[index++];
                byte green = bytes[index++];
                byte blue = bytes[index++];

                // Set current color as the read RGB values
                color = new Color(red, green, blue);

                // Write the pixel onto the image
                setPixel(img, pixelNumber++, color);

                // System.out.println("RGB: " + curByte);
            } else if (curByte == QOI_OP_RGBA) {
                // Read RGBA from the next 4 bytes
                byte red = bytes[index++];
                byte green = bytes[index++];
                byte blue = bytes[index++];
                byte alpha = bytes[index++];

                // Set current color as the read RGBA values
                color = new Color(red, green, blue, alpha);

                // Write the pixel onto the image
                setPixel(img, pixelNumber++, color);

                // System.out.println("RGBA: " + curByte);
            } else {
                System.out.println("Number (ERROR): " + curByte);
            }

            // Add current pixel to seenPixels
            // Hash/index position in seenPixels (cache)
            int hash = (color.getRed() * 3 + color.getGreen() * 5 + color.getBlue() * 7 + color.getAlpha() * 11)
                    % 64;
            seenPixels[hash] = color; // Record color into seenPixels.

        }

        return img;
    }

    public static void main(String[] args) throws IOException {
        // File file = new File("3x3.qoi");
        // File file = new File("10x1-rltest.qoi");
        File file = new File("qoi_test_images/wikipedia_008.qoi");
        FileInputStream inStream = new FileInputStream(file);
        byte[] bytes = inStream.readAllBytes();

        try {
            BufferedImage img = decode(bytes);

            ImageIO.write(img, "png", new File("decodeOut.png"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
