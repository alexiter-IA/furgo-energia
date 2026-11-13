# Protocolo BLE de la FOSSiBOT F2400 (app BrightEMS / firmware Sydpower)

Obtenido por análisis del APK de BrightEMS (app Flutter, paquete `brightems_app`, lógica en `libapp.so`)
y contrastado con el proyecto abierto [ESP-FBot](https://github.com/Ylianst/ESP-FBot) (Apache-2.0),
que controla el mismo equipo desde un ESP32.

> Estado: **validado contra una F2400 real el 13/11/2026** con la app de `app-android` (CRC con byte alto primero).

## Resumen

La estación habla **Modbus RTU encapsulado en BLE GATT**. No hay emparejamiento, cifrado ni login:
la nube solo le dice a la app oficial la dirección Modbus y el número de registros del modelo
(`modbus_address`, `modbus_count`), y por eso exige internet. Para la F2400 son fijos: `0x11` y `80`.

## GATT

| Elemento | UUID |
|---|---|
| Servicio | `0000a002-0000-1000-8000-00805f9b34fb` |
| Escritura (write without response) | `0000c304-0000-1000-8000-00805f9b34fb` |
| Notificación | `0000c305-0000-1000-8000-00805f9b34fb` |

En el binario aparece el prefijo de nombre `POWER-` (probable nombre de anuncio BLE; por confirmar).

## Trama de petición (8 bytes)

```
[0] 0x11        dirección esclavo
[1] función     0x04 leer registros de entrada (estado)
                0x03 leer registros holding (ajustes)
                0x06 escribir un registro holding
[2..3] registro (big-endian)
[4..5] cantidad (lectura) o valor (escritura), big-endian
[6..7] CRC-16/MODBUS (init 0xFFFF, polinomio reflejado 0xA001) sobre los bytes 0..5,
       enviado **byte alto primero** (al revés que el Modbus estándar)
```

### Tramas listas para usar

| Acción | Bytes |
|---|---|
| Leer estado (80 reg. de entrada) | `11 04 00 00 00 50 A6 F2` |
| Leer ajustes (80 reg. holding) | `11 03 00 00 00 50 66 47` |
| USB ON / OFF | `11 06 00 18 00 01 9D CA` / `11 06 00 18 00 00 5D 0B` |
| DC 12V ON / OFF | `11 06 00 19 00 01 5D 9B` / `11 06 00 19 00 00 9D 5A` |
| AC 220V ON / OFF | `11 06 00 1A 00 01 5D 6B` / `11 06 00 1A 00 00 9D AA` |
| Luz LED ON / OFF | `11 06 00 1B 00 01 9D 3A` / `11 06 00 1B 00 00 5D FB` |

## Respuesta

Llega por notificaciones en `C305` (puede venir troceada en varios paquetes según el MTU).
Byte 0 = `0x11`, byte 1 = función. El registro *n* es un entero de 16 bits big-endian en el
offset `6 + 2·n`.

## Registros de entrada (estado, función 0x04)

| Índice | Significado | Escala |
|---|---|---|
| 2 | Nivel de carga AC (1–5 → 300, 500, 700, 900, 1100 W) | — |
| 3 | Potencia entrada AC | W |
| 4 | Potencia entrada DC (solar/coche) | W |
| 6 | Potencia de entrada total | W |
| 18 | Tensión salida AC | ×0,1 V |
| 19 | Frecuencia salida AC | ×0,1 Hz |
| 20 | Potencia total | W |
| 21 | Potencia sistema | W |
| 22 | Frecuencia entrada AC | ×0,01 Hz |
| 30, 31 | USB-A1, USB-A2 | ×0,1 W |
| 34–37 | USB-C1…C4 | ×0,1 W |
| 39 | Potencia de salida total | W |
| 41 | Flags de estado: bit 9 USB, bit 10 DC, bit 11 AC, bit 12 luz | máscara |
| 53, 55 | SOC baterías de expansión 1 y 2 (0 = no conectada) | ÷10 − 1 % |
| 56 | SOC batería principal | ÷10 % |
| 58 | Tiempo hasta carga completa | min |
| 59 | Autonomía restante | min |

## Registros holding (ajustes, 0x03 leer / 0x06 escribir)

| Índice | Significado | Valores |
|---|---|---|
| 13 | Límite de carga AC | 1–5 |
| 24 | Salida USB | 0 / 1 |
| 25 | Salida DC 12 V | 0 / 1 |
| 26 | Salida AC 220 V | 0 / 1 |
| 27 | Luz | 0 off, 1 on, 2 SOS, 3 intermitente |
| 56 | Sonido de teclas | 0 / 1 |
| 57 | Carga AC silenciosa | 0 / 1 |
| 66 | Límite inferior de descarga (la app oficial tampoco consigue que corte USB/12 V al llegar al límite) | ‰ (0–500 → 0–50 %) |
| 67 | Límite superior de carga | ‰ (100–1000 → 10–100 %) |

## Notas de uso

- Tras suscribirse a `C305`, pedir estado; ~500 ms después, pedir ajustes.
- Sondeo de estado cada pocos segundos; tras un comando, esperar ~2 s antes del siguiente sondeo.
- Existe una función `0x07` para pasarle SSID/contraseña WiFi a la estación. No la necesitamos.
