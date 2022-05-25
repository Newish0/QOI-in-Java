import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
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

    /**
     * Calculates the difference in Color.
     * 
     * @param a The minuend.
     * @param b The subtrahend.
     * @return Returns a difference in Color as an byte array: {Red, Green, Blue,
     *         Alpha}
     */
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

    /**
     * Encodes an BufferedImage into the QOI format (raw bytes).
     * 
     * @param img The input image.
     * @return The QOI format in an byte array
     */
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

    /**
     * Write an BufferedImage into the QOI format image.
     * 
     * @param img      The input image.
     * @param filepath The output path of the encoded image.
     * @throws IOException
     */
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

    /**
     * Converts 4 bytes (BE) to an integer
     * 
     * @param bytes The input; the 4 bytes (BE) the integer is converted from.
     * @return An 32 bit signed integer.
     */
    private static int intFrom32(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
    } // intFrom32

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

    } // setPixel

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
    } // isAtQOIEndMarker

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
    } // addPixelToSeen

    /**
     * Decodes raw bytes encoded in the QOI format into an BufferedImage.
     * 
     * @param bytes The raw bytes of an QOI image.
     * @return The decoded QOI image as an BufferedImage.
     * @throws QOIException
     */
    public static BufferedImage decode(byte[] bytes) throws QOIException {
        return decode(bytes, true);
    } // decode

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
            // Assume end marker is at (if not near) the end of the file.
            // Hence loop in reversed to increase efficiency.
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

    /**
     * Reads an QOI image and decodes it into an BufferedImage.
     * 
     * @param filepath The file path of the QOI image to read from.
     * @return The decoded image.
     * @throws IOException
     * @throws QOIException
     */
    public static BufferedImage read(String filepath) throws IOException, QOIException {
        File file = new File(filepath);
        return read(file);
    }

    /**
     * Reads an QOI image and decodes it into an BufferedImage.
     * 
     * @param file The file of the QOI image to read from.
     * @return The decoded image.
     * @throws IOException
     * @throws QOIException
     */
    public static BufferedImage read(File file) throws IOException, QOIException {
        FileInputStream inStream = new FileInputStream(file);
        byte[] bytes = inStream.readAllBytes();
        inStream.close();
        return QOI.decode(bytes);
    }
} // QOI
