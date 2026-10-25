# Autofruit

App Android que juega sola al minijuego de fichas de frutas de Widgetable
(junta 3 fichas iguales; si la bandeja se llena, pierdes).

## Instalar

1. Descarga `autofruit.apk` de la release **latest** del repositorio
   (la genera GitHub Actions en cada push) e instálalo.
2. Abre Autofruit y sigue los pasos:
   - Activa el **servicio de accesibilidad** "Autofruit" (hace las capturas y los
     toques). En Android 13+ con apps instaladas fuera de la tienda puede salir
     "Ajuste restringido": Información de la app → ⋮ → *Permitir ajustes restringidos*.
   - Permite las **notificaciones** (notificación permanente con Iniciar/Parar).
3. Abre un nivel y pulsa el botón flotante ▶ o "Iniciar" en la notificación.

Necesita Android 11 o superior (usa `AccessibilityService.takeScreenshot`).
No hace falta el permiso de "mostrar sobre otras apps": el botón flotante lo
dibuja el propio servicio de accesibilidad.

## Cómo juega

1. **Captura** la pantalla y solo juega cuando la zona del tablero y la bandeja
   es idéntica píxel a píxel a la captura anterior: nunca decide mientras una
   ficha vuela a la bandeja, un trío desaparece o una ficha se ilumina. Una
   fruta que no reconoce (nueva o a medio animar) se espera a que se vea quieta
   antes de aprenderla.
2. **Detecta** las fichas (`core/.../Detector.kt`):
   - Fichas iluminadas (seleccionables): zonas del color crema de la cara.
   - Fichas tapadas: son la misma ficha oscurecida (multiplicada por un factor
     que depende de la profundidad). Se reconstruyen capa a capa ajustando un
     rectángulo de ficha (cara, borde oscuro y canto inferior) a cada trozo gris
     visible; cuando todo lo que solapa ya está explicado por fichas de encima,
     se acepta y se baja una capa.
   - La bandeja: la barra verde oscura de abajo y las fichas que tiene.
3. **Reconoce la fruta** con una rejilla de colores de la cara (las tapadas se
   aclaran antes y solo se comparan las celdas visibles). Trae un catálogo de 12
   frutas sacado de capturas y aprende las nuevas al verlas iluminadas. Si un
   trozo tapado no se reconoce con seguridad, se trata como desconocido.
4. **Planifica** (`Solver.kt`): modela qué fichas quedan libres al quitar las de
   encima y hace una búsqueda en haz de hasta 30 jugadas, puntuando tríos hechos,
   ocupación de la bandeja y si cada fruta de la bandeja aún se puede completar.
   Completar un trío disponible se juega siempre al momento. Solo se ejecuta la
   primera jugada y se vuelve a mirar el tablero.
5. **Toca** la ficha con `dispatchGesture`.

Si un toque no cambia nada, prueba otra ficha. Las jugadas seguidas sobre
fichas ya iluminadas (hasta 3) se tocan en ráfaga sin volver a mirar. Al
terminar un nivel espera 5 s a que aparezca el siguiente y sigue; solo se para
si se llena la bandeja.

## Depuración

Mantener pulsado el botón flotante (o "Captura debug" en la notificación) guarda
en `Imágenes/Autofruit` la última captura en la que se veía el tablero y
otra imagen con lo que ha entendido el bot (recuadros por fruta, `tipo/capa`
en las tapadas, y el orden del plan). Con esas imágenes se puede ajustar el
detector: añádelas a `core/src/test/resources` y a `DetectorTest`.

## Desarrollo

- Reproducir una grabación de pantalla con el bot y ver qué decidiría en cada
  fotograma: `ffmpeg -i video.mp4 -vf fps=10 frames/%04d.png` y luego
  `./gradlew :core:test --tests '*VideoReplayTest*' -Dautofruit.frames=$PWD/frames`.

- `core/`: Kotlin puro (detección, reconocimiento, solver), con tests sobre
  capturas reales: `./gradlew :core:test` (deja imágenes anotadas en
  `core/build/debug/`).
- `app/`: la app Android; solo se incluye si hay un Android SDK.
- Regenerar el catálogo de frutas:
  `./gradlew :core:test --tests '*SeedTest*' -Dautofruit.writeSeeds=$PWD/core/src/main/resources/autofruit/seed-types.bin`
- `app/autofruit.keystore` (contraseña `autofruit`) firma todas las builds para
  que cada APK se instale encima del anterior. Es una clave pública de usar y
  tirar, no la uses para nada más.
