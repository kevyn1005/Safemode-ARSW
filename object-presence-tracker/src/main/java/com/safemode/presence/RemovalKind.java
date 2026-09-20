package com.safemode.presence;

/**
 * Como se retiro un objeto, segun quien estaba junto a el (persona mas cercana o presente cuando desaparecio o se
 * lo llevaron). Es la base para el centro de alertas: lo normal es {@link #BY_OWNER}; lo sospechoso es que se lo
 * lleve alguien distinto del dueno o que desaparezca sin nadie cerca.
 */
public enum RemovalKind {
    /** Solo el dueno estaba junto al objeto: retiro normal. */
    BY_OWNER,
    /** Habia alguien distinto del dueno junto al objeto y el dueno no: posible robo. */
    BY_OTHER,
    /** Estaban el dueno y otra persona: no se sabe quien lo tomo (ambiguo, conviene revisar la foto). */
    OWNER_AND_OTHER_NEAR,
    /** Nadie estaba junto al objeto: desaparecio solo (o se lo llevaron fuera de camara). */
    NO_ONE_NEAR,
    /** El objeto no tenia dueno registrado, pero alguien estaba cerca al retirarse. */
    OWNER_UNKNOWN
}
