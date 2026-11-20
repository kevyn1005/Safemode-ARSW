-- Generador de retiros sospechosos (lo que hace el tracker): inserta un retiro BY_OTHER cada 0,5 s. El motor de alertas del gateway lo
-- convierte en alerta y la empuja a todos los sockets. performance/ejecutar.ps1 sustituye __CUENTA__ por el numero de repeticiones
-- (duracion de la prueba / 0,5 s). Cada \watch es una transaccion aparte, asi cada evento es visible al instante.
INSERT INTO object_presence_event (tracked_object_id, class_name, event_type, occurred_at, pos_x, pos_y, width, height, person_nearby,
    frame_path, owner_person_id, owner_description, owner_crop_path, removal_kind, remover_person_id, remover_description,
    remover_crop_path, camera_id)
VALUES ((random() * 1000000)::bigint + 1000, 'suitcase', 'REMOVED', now(), 100, 100, 50, 80, true,
    'data/frames/prueba.png', 1, 'persona de prueba', 'data/frames/prueba_owner.png', 'BY_OTHER', 2, 'otra persona de prueba',
    'data/frames/prueba_remover.png', 1)
\watch i=0.5 c=__CUENTA__
