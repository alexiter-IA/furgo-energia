package com.furgo.energia

/**
 * Protocolo de la estacion (ver PROTOCOLO.md): Modbus RTU encapsulado en BLE.
 * Tramas de 8 bytes: direccion, funcion, registro (2), valor/cantidad (2), CRC (2).
 */
object Modbus {
    const val DIRECCION = 0x11
    const val NUM_REGISTROS = 80
    private const val CABECERA = 6

    /** Longitud minima de una respuesta de lectura completa (sin contar el CRC final). */
    const val LONGITUD_RESPUESTA = CABECERA + NUM_REGISTROS * 2

    const val FN_LEER_AJUSTES = 0x03
    const val FN_LEER_ESTADO = 0x04
    const val FN_ESCRIBIR = 0x06

    fun crc16(datos: ByteArray, longitud: Int = datos.size): Int {
        var crc = 0xFFFF
        for (i in 0 until longitud) {
            crc = crc xor (datos[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    /**
     * @param crcEstandar false = byte alto primero (lo que usa este firmware);
     *                    true = byte bajo primero (Modbus estandar), por si hiciera falta probarlo.
     */
    fun trama(funcion: Int, registro: Int, valor: Int, crcEstandar: Boolean = false): ByteArray {
        val t = ByteArray(8)
        t[0] = DIRECCION.toByte()
        t[1] = funcion.toByte()
        t[2] = (registro shr 8).toByte()
        t[3] = registro.toByte()
        t[4] = (valor shr 8).toByte()
        t[5] = valor.toByte()
        val crc = crc16(t, 6)
        val alto = (crc shr 8).toByte()
        val bajo = crc.toByte()
        t[6] = if (crcEstandar) bajo else alto
        t[7] = if (crcEstandar) alto else bajo
        return t
    }

    /** Registros holding de los limites de bateria, en tanto por mil. */
    const val REG_LIMITE_DESCARGA = 66
    const val REG_LIMITE_CARGA = 67

    fun leerEstado(crcEstandar: Boolean) = trama(FN_LEER_ESTADO, 0, NUM_REGISTROS, crcEstandar)

    fun leerAjustes(crcEstandar: Boolean) = trama(FN_LEER_AJUSTES, 0, NUM_REGISTROS, crcEstandar)

    fun escribir(registro: Int, valor: Int, crcEstandar: Boolean) =
        trama(FN_ESCRIBIR, registro, valor, crcEstandar)

    /** Registro n de una respuesta de lectura: entero de 16 bits big-endian en el offset 6 + 2n. */
    fun registro(respuesta: ByteArray, n: Int): Int {
        val o = CABECERA + n * 2
        if (o + 1 >= respuesta.size) return 0
        return ((respuesta[o].toInt() and 0xFF) shl 8) or (respuesta[o + 1].toInt() and 0xFF)
    }

    fun hex(b: ByteArray, max: Int = 24): String {
        val s = b.take(max).joinToString(" ") { "%02X".format(it) }
        return if (b.size > max) "$s … (${b.size} bytes)" else s
    }
}

/** Salidas conmutables: registro holding que se escribe y bit del registro de entrada 41 que refleja su estado. */
enum class Salida(val registro: Int, val bit: Int) {
    USB(24, 1 shl 9),
    DC(25, 1 shl 10),
    AC(26, 1 shl 11),
    LUZ(27, 1 shl 12),
}

/** Ajustes leidos de los registros holding. Porcentajes enteros. */
data class Ajustes(val limiteDescarga: Int, val limiteCarga: Int) {
    companion object {
        fun de(r: ByteArray) = Ajustes(
            limiteDescarga = Modbus.registro(r, Modbus.REG_LIMITE_DESCARGA) / 10,
            limiteCarga = Modbus.registro(r, Modbus.REG_LIMITE_CARGA) / 10,
        )
    }
}

data class Estado(
    val soc: Float,
    val entradaW: Int,
    val entradaAcW: Int,
    val entradaDcW: Int,
    val salidaW: Int,
    val flags: Int,
    val minutosRestantes: Int,
    val minutosCarga: Int,
) {
    fun encendida(s: Salida) = flags and s.bit != 0

    fun con(s: Salida, on: Boolean) =
        copy(flags = if (on) flags or s.bit else flags and s.bit.inv())

    companion object {
        fun de(r: ByteArray) = Estado(
            soc = Modbus.registro(r, 56) / 10f,
            entradaW = Modbus.registro(r, 6),
            entradaAcW = Modbus.registro(r, 3),
            entradaDcW = Modbus.registro(r, 4),
            salidaW = Modbus.registro(r, 39),
            flags = Modbus.registro(r, 41),
            minutosRestantes = Modbus.registro(r, 59),
            minutosCarga = Modbus.registro(r, 58),
        )
    }
}
