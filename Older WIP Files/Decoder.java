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

    private final static int QOI_HEADER_SIZE = 14; // header size is 14 bytes
    private final static byte[] QOI_END_MARKER = { 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x1 };
    private final static int QOI_END_MARKER_SIZE = QOI_END_MARKER.length;

    // QOI chunck tags
    private final static byte QOI_OP_RUN = (byte) 0xc0; // 0b11000000
    private final static byte QOI_OP_INDEX = (byte) 0x00; // 0b00000000
    private final static byte QOI_OP_DIFF = (byte) 0x40; // 0b01000000
    private final static byte QOI_OP_LUMA = (byte) 0x80; // 0b10000000
    private final static byte QOI_OP_RGB = (byte) 0xfe; // 0b11111110
    private final static byte QOI_OP_RGBA = (byte) 0xff; // 0b11111111

    // Converts 4 bytes (BE) to an integer
    private static int intFrom32(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
    }

    /**
     * Given the pixel number, convert it to x and y coordinates and draw the pixel
     * onto the image.
     * 
     * @param image       Image to draw onto.
     * @param pixelNumber
     * @param pixel
     */
    private static void setPixel(BufferedImage image, int pixelNumber, Color pixel) {
        int width = image.getWidth();
        int x = pixelNumber % width;
        int y = pixelNumber / width;

        image.setRGB(x, y, pixel.getRGB());

    }

    /**
     * Checks whether the next 8 bytes in the given array starting from the offset
     * is the QOI end marker.
     * 
     * @param bytes  The raw bytes of an QOI image.
     * @param offset Offset from the index 0.
     * @return Whether the next 8 bytes starting from the offset is the QOI end
     *         marker.
     */
    private static boolean isAtQOIEndMarker(byte[] bytes, int offset) {
        return bytes[offset] == 0
                && bytes[offset + 1] == 0
                && bytes[offset + 2] == 0
                && bytes[offset + 3] == 0
                && bytes[offset + 4] == 0
                && bytes[offset + 5] == 0
                && bytes[offset + 6] == 0
                && bytes[offset + 7] == 1;
    }

    /**
     * Helper method for adding the current pixel to the seenPixels hash table.
     * 
     * @param seenPixels The hash table of an size 64 array.
     * @param color      The color to insert into the table.
     */
    private static void addPixelToSeen(Color[] seenPixels, Color color) {
        // Add current pixel to seenPixels
        // Hash/index position in seenPixels (cache)
        int hash = (color.getRed() * 3 + color.getGreen() * 5 + color.getBlue() * 7 + color.getAlpha() * 11)
                % 64;
        seenPixels[hash] = color; // Record color into seenPixels.
    }

    /**
     * Decodes raw bytes encoded in the QOI format into an BufferedImage.
     * 
     * @param bytes The raw bytes of an QOI image.
     * @return The decoded QOI image as an BufferedImage.
     * @throws QOIException
     */
    public static BufferedImage decode(byte[] bytes) throws QOIException {
        return decode(bytes, true);
    }

    /**
     * Decodes raw bytes encoded in the QOI format into an BufferedImage.
     * 
     * @param bytes        The raw bytes of an QOI image.
     * @param useEndMarker Determines whether to use the end marker as the end
     *                     of file. Setting it to false may result in
     *                     exceptions.
     * @return The decoded QOI image as an BufferedImage.
     * @throws QOIException
     */
    public static BufferedImage decode(byte[] bytes, boolean useEndMarker) throws QOIException {
        BufferedImage img = null;

        int index = 0; // Keep track of the index in bytes array

        // Header size is 14 bytes, so if input bytes must be at least 14 bytes
        if (bytes.length < QOI_HEADER_SIZE + QOI_END_MARKER_SIZE) {
            throw new QOIHeaderException("Data is not in QOI format.");
        } // if

        // Read QOI magic bytes to ensure file is an QOI image
        byte[] magicBytes = { 0x71, 0x6F, 0x69, 0x66 };
        for (; index < 4; index++) {
            if (bytes[index] != magicBytes[index]) {
                throw new QOIHeaderException("Data is not marked as QOI format.");
            } // if
        } // for

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
        } // for

        // Read the 4 bytes representing the height
        for (; index < 12; index++) {
            heightBytes[index - 8] = bytes[index];
        } // for

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
        } // if

        if (channels == 3) {
            img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        } else {
            img = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        } // if else

        Color prevPixel = new Color(0, 0, 0, 255);
        Color[] seenPixels = new Color[64];

        int pixelNumber = 0;

        // Initialize all Color in seenPixels to be the color black.
        for (int i = 0; i < seenPixels.length; i++) {
            seenPixels[i] = new Color(0, 0, 0, 0);
        } // for

        // Keeps track of the last index of the bytes array to decode.
        // By defualt, it assumes the end marker to be at the end of all other bytes.
        int endIndex = bytes.length - QOI_END_MARKER_SIZE;

        // Use position of end marker to determine when to end decoder if
        // useEndMarker is true.
        if (useEndMarker) {
            for (int i = bytes.length - 1; i >= QOI_HEADER_SIZE; i--) {
                if (isAtQOIEndMarker(bytes, i)) {
                    endIndex = i;
                    break;
                } // if
            } // for
        } // if

        // Read until the endIndex
        while (index < endIndex) {
            byte curByte = bytes[index++]; // The current byte we are reading
            byte curByte2BitTag = (byte) (curByte & 0b11000000); // Mask byte to obtain the 2 bit tag
            Color color = null; // The current pixel

            // Classify the chuck's from their 2- or 8-bit tag, the decode the chuck.
            // NOTE: Order at which we check the encoding method matters.
            if (curByte == QOI_OP_RGB) {
                // Read RGB from the next 3 bytes
                int red = bytes[index++] & 0xff;
                int green = bytes[index++] & 0xff;
                int blue = bytes[index++] & 0xff;

                // Set current color as the read RGB values
                color = new Color(red, green, blue);

                // Write the pixel onto the image
                setPixel(img, pixelNumber++, color);

                // Add the current pixel into the seenPixels array
                addPixelToSeen(seenPixels, color);
                // Set previous pixel to be the current pixel
                prevPixel = color;

            } else if (curByte == QOI_OP_RGBA) {
                // Read RGBA from the next 4 bytes
                int red = bytes[index++] & 0xff;
                int green = bytes[index++] & 0xff;
                int blue = bytes[index++] & 0xff;
                int alpha = bytes[index++] & 0xff;

                // Set current color as the read RGBA values
                color = new Color(red, green, blue, alpha);

                // Write the pixel onto the image
                setPixel(img, pixelNumber++, color);

                // Add the current pixel into the seenPixels array
                addPixelToSeen(seenPixels, color);
                // Set previous pixel to be the current pixel
                prevPixel = color;

            } else if (curByte2BitTag == QOI_OP_RUN) {
                int run = ((curByte & 0b00111111) & 0xff) + 1; // Read byte and convert to unsigned byte as an integer.
                                                               // And +1 bias
                color = prevPixel;

                for (int i = 0; i < run; i++) {
                    setPixel(img, pixelNumber++, prevPixel);
                } // for

            } else if (curByte2BitTag == QOI_OP_DIFF) {
                // Read difference in RGB current byte
                byte dr = (byte) (((curByte & 0b00110000) >> 4) - 2);
                byte dg = (byte) (((curByte & 0b00001100) >> 2) - 2);
                byte db = (byte) ((curByte & 0b00000011) - 2);

                // Previous pixel data.
                // Casted to bytes so they wrap around when overflowed / underflow .
                byte pr = (byte) prevPixel.getRed();
                byte pg = (byte) prevPixel.getGreen();
                byte pb = (byte) prevPixel.getBlue();
                int pa = prevPixel.getAlpha();

                // Set current color the difference from the previous pixel
                // after convert the byte to unsigned
                color = new Color((dr + pr) & 0xff, (dg + pg) & 0xff, (db + pb) & 0xff, pa);

                // Write the pixel onto the image
                setPixel(img, pixelNumber++, color);

                // Add the current pixel into the seenPixels array
                addPixelToSeen(seenPixels, color);
                // Set previous pixel to be the current pixel
                prevPixel = color;

            } else if (curByte2BitTag == QOI_OP_LUMA) {
                // Read difference in green and revert the bias of 32.
                byte dg = (byte) ((curByte & 0b00111111) - 32);

                // Read the next byte
                byte dr_dg_db_dg = bytes[index++];

                // Read the red channel difference minus green channel difference
                // and revert the bias of 8 on both.
                byte dr_dg = (byte) (((dr_dg_db_dg & 0b11110000) >> 4) - 8);
                byte db_dg = (byte) ((dr_dg_db_dg & 0b00001111) - 8);

                // Calculate dr & db.
                byte dr = (byte) (dr_dg + dg);
                byte db = (byte) (db_dg + dg);

                // Previous pixel data.
                // Casted to bytes so they wrap around when overflowed / underflow .
                byte pr = (byte) prevPixel.getRed();
                byte pg = (byte) prevPixel.getGreen();
                byte pb = (byte) prevPixel.getBlue();
                int pa = prevPixel.getAlpha();

                // Set current color the difference from the previous pixel
                // after convert the byte to unsigned.
                color = new Color((dr + pr) & 0xff, (dg + pg) & 0xff, (db + pb) & 0xff, pa);

                // Write the pixel onto the image
                setPixel(img, pixelNumber++, color);

                // Add the current pixel into the seenPixels array
                addPixelToSeen(seenPixels, color);
                // Set previous pixel to be the current pixel
                prevPixel = color;

            } else if (curByte2BitTag == QOI_OP_INDEX) {
                // Read index: mask out the chunck tag to get the first 6 bit of the in curByte
                int indexInSeenPixels = (curByte & 0b00111111) & 0xff;

                color = seenPixels[indexInSeenPixels];

                // Write the pixel onto the image
                setPixel(img, pixelNumber++, color);

                // Set previous pixel to be the current pixel
                prevPixel = color;

            } else {
                throw new QOIDecodeException("Failed to decode QOI image. Invalid byte encountered.");
            } // if elses

        } // while

        return img;
    } // decode

    

    public static void main(String[] args) throws IOException {
        // File file = new File("qoi_test_images/3x3.qoi");
        // File file = new File("qoi_test_images/10x1-rltest.qoi");
        File file = new File("qoi_test_images/wikipedia_008.qoi");
        // File file = new File("qoi_test_images/testcard.qoi");
        FileInputStream inStream = new FileInputStream(file);
        byte[] bytes = inStream.readAllBytes();

        try {

            long st = System.currentTimeMillis();
            BufferedImage img = decode(bytes);
            System.out.println(System.currentTimeMillis() - st);

            ImageIO.write(img, "png", new File("decodeOut.png"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
