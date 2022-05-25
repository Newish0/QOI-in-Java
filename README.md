# QOI in Java

Quickly read, write, encode, decode QOI images!

An QOI encoder/decoder written in Java.


## Quick Start

Example of writing an QOI image.
```java
BufferedImage inputImage = ...
QOI.write(inputImage, "output.qoi");
```

Example of reading an QOI image.
```java
BufferedImage image = QOI.read("input.qoi");
```


Example of converting from PNG to QOI.
```java
File file = new File("input.png");
BufferedImage img = ImageIO.read(file);
QOI.write(img, "output.qoi");
```



