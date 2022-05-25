import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

public class QOIDisplay {

    public static void main(String avg[]) throws IOException, QOIException {
        QOIDisplay display = new QOIDisplay("testout.qoi");
    }

    public QOIDisplay(String filepath) throws IOException, QOIException {
        BufferedImage img = QOI.read(filepath);
        ImageIcon icon = new ImageIcon(img);
        JFrame frame = new JFrame();
        frame.setLayout(new FlowLayout());
        frame.setSize(img.getWidth(), img.getHeight());
        JLabel lbl = new JLabel();
        lbl.setIcon(icon);
        frame.add(lbl);
        frame.setVisible(true);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }
}