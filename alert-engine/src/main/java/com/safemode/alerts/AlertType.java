package com.safemode.alerts;

/** Motivo de una alerta: cada tipo sale de una forma distinta en que se retiro un objeto (ver RemovalKind). */
public enum AlertType {
    /** Alguien distinto del dueno se llevo el objeto. */
    POSSIBLE_THEFT,
    /** Estaban el dueno y otra persona junto al objeto: no se sabe quien se lo llevo. */
    AMBIGUOUS_REMOVAL,
    /** El objeto desaparecio sin nadie cerca. */
    OBJECT_VANISHED,
    /** El objeto no tenia dueno registrado y alguien se lo llevo. */
    UNKNOWN_OWNER_REMOVAL
}
