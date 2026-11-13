# Furgo Energía

App Android para controlar por Bluetooth una estación de energía **FOSSiBOT F2400** (y, en principio,
otras que usan la app BrightEMS / firmware Sydpower) **sin internet y sin cuenta**.

Nació porque la app oficial exige conexión a internet para abrirse, aunque luego habla con la estación
por Bluetooth: inútil en medio del monte, sin cobertura.

> **English:** Offline Android app to control a **FOSSiBOT F2400** portable power station over
> Bluetooth LE, with no internet and no account — a replacement for the BrightEMS app, which refuses
> to open without a connection. Toggles USB / 12 V DC / 230 V AC / LED outputs, shows battery,
> input and output power, and sets charge/discharge limits. The reverse-engineered protocol
> (Modbus RTU over BLE) is documented in [PROTOCOLO.md](PROTOCOLO.md).

## Modelos

- **Probado:** FOSSiBOT F2400.
- **Sin probar, pero usan la misma app BrightEMS y el mismo firmware Sydpower:** FOSSiBOT F3600 Pro,
  SYDPOWER N052 y otros, y algunos modelos de AFERIY. Si lo pruebas en otro modelo, abre una issue
  contando qué tal.

## Qué hace

- Enciende y apaga las salidas **USB**, **12 V**, **220 V** y la **luz LED**.
- Muestra el % de batería, la autonomía o el tiempo hasta carga completa, y los vatios de entrada
  (solar/DC y AC) y de salida.
- Ajusta los límites de **descarga** y de **carga** de la batería.
- Recuerda la estación y reconecta sola cuando vuelve a estar al alcance.
- No pide permiso de internet: solo «Dispositivos cercanos» (Bluetooth).

## Protocolo

La estación habla Modbus RTU encapsulado en BLE, sin cifrado ni emparejamiento.
Está documentado en [PROTOCOLO.md](PROTOCOLO.md).

## Compilar

Proyecto Gradle en [`app-android`](app-android): Kotlin + Jetpack Compose, Android 12 o superior.

```
cd app-android
gradlew assembleDebug
```

`gradle.properties` fija `org.gradle.java.home` a la ruta de un JDK 17 concreto: cámbiala por la tuya
o borra esa línea. En Windows, `compilar.bat` compila, instala por USB y abre la app.

## Créditos y aviso

- El mapa de registros se contrastó con [ESP-FBot](https://github.com/Ylianst/ESP-FBot) (Apache-2.0).
- Proyecto personal, sin relación con FOSSiBOT, Sydpower ni BrightEMS. Úsalo bajo tu responsabilidad:
  la app enciende y apaga salidas de potencia reales.

## Licencia

[MIT](LICENSE).
