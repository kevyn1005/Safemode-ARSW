package com.safemode.camerafeed;

import java.awt.MouseInfo;
import java.awt.Point;

public class MousePositionFinder {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("Mueve el mouse a la esquina SUPERIOR IZQUIERDA de la ventana de la cámara...");
        Thread.sleep(4000);
        Point topLeft = MouseInfo.getPointerInfo().getLocation();
        System.out.println("Esquina superior izquierda: " + topLeft);

        System.out.println("Ahora mueve el mouse a la esquina INFERIOR DERECHA...");
        Thread.sleep(4000);
        Point bottomRight = MouseInfo.getPointerInfo().getLocation();
        System.out.println("Esquina inferior derecha: " + bottomRight);

        int width = bottomRight.x - topLeft.x;
        int height = bottomRight.y - topLeft.y;
        System.out.println("Region: new Rectangle(" + topLeft.x + ", " + topLeft.y + ", " + width + ", " + height + ")");
    }
}