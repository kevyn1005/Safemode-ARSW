package com.safemode.gateway;

import java.util.Map;

/** Los nombres de las camaras que el panel muestra y el usuario puede editar. */
public interface CameraDirectory {

    /** Id de camara -> nombre vigente. */
    Map<Integer, String> names();

    /**
     * Cambia el nombre de una camara (vacio = el nombre por defecto) y avisa a los paneles.
     * {@link IllegalArgumentException} (con un mensaje apto para mostrar) si el id o el nombre no son validos.
     */
    void rename(int cameraId, String name);
}
