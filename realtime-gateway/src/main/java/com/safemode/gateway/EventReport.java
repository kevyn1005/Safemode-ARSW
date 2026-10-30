package com.safemode.gateway;

import com.safemode.presence.PresenceEventStore.StoredEvent;

import java.util.List;

/** De donde sale el reporte del panel: los ultimos eventos que guardo el tracker y el nombre de cada camara. */
public interface EventReport {

    /** Los ultimos {@code limit} eventos (registros en reposo y retiros), del mas nuevo al mas viejo. */
    List<StoredEvent> recent(int limit);

    /** Nombre vigente de la camara (el que le puso el usuario o "CAM n"). */
    String cameraName(int cameraId);
}
