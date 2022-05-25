public class QOIDecodedData {
    private int width;
    private int height;
    private byte channels;
    private byte colorspace;

    public QOIDecodedData() {
        int width = -1;
        int height = -1;
        byte channels = -1;
        byte colorspace = -1;
    }

    public QOIDecodedData(int width, int height, byte channels, byte colorspace) {
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.colorspace = colorspace;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public byte getChannels() {
        return channels;
    }

    public void setChannels(byte channels) {
        this.channels = channels;
    }

    public byte getColorspace() {
        return colorspace;
    }

    public void setColorspace(byte colorspace) {
        this.colorspace = colorspace;
    }
    
}
