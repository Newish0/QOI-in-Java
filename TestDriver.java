import java.io.File;
import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

public class TestDriver {
    public static void main(String[] args) throws IOException, QOIException {
        // Benchmark
        File file = new File("qoi_test_images/wikipedia_008.png");
        BufferedImage input = ImageIO.read(file);
        System.out.println("wikipedia_008.png Benchmark");
        long st = System.currentTimeMillis();
        byte[] bytes = QOI.encode(input);
        System.out.println("Encode (ms): " + (System.currentTimeMillis() - st));

        st = System.currentTimeMillis();
        BufferedImage decodedImg = QOI.decode(bytes);
        System.out.println("Decode (ms): " + (System.currentTimeMillis() - st));
    }
}
