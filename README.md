# FreeRUN

Prototipo Android para leer un monitor de frecuencia cardíaca ANT+ y publicar sesiones en Health Connect.

## Estado actual

- Proyecto Android Kotlin creado.
- Pantalla inicial con estado, inicio del servicio y modo demostración.
- Servicio en primer plano preparado para mantener la captura activa.
- Dependencia de Health Connect incluida.
- Permisos Bluetooth y servicio `connectedDevice` declarados.
- Detección de ANT+ nativo y de dongle ANT+ conectado por USB.
- Controles para desconectar el sensor, detener el monitoreo y cerrar la app.

## Próximo paso

Integrar el ANT Radio Service/ANT Android SDK y convertir cada mensaje del perfil Heart Rate Monitor en muestras `HeartRateRecord` de Health Connect. Un dongle USB requiere además solicitar permiso USB al usuario y utilizar el SDK compatible con ese adaptador.
