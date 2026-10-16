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
    UNKNOWN_OWNER_REMOVAL,
    /**
     * Lo retiro alguien distinto del dueno registrado, pero se viste parecido a el: puede ser el dueno que salio de cuadro y volvio
     * (el seguimiento lo olvida a los pocos frames). No se silencia: queda visible, con las fotos del dueno y de quien retiro.
     */
    POSSIBLE_OWNER_RETURN
}
