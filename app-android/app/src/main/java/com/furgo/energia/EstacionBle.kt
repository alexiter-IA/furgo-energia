package com.furgo.energia

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

enum class Conexion { DESCONECTADO, BUSCANDO, CONECTANDO, CONECTADO }

data class Encontrado(val mac: String, val nombre: String, val rssi: Int, val probable: Boolean)

/**
 * Conexion BLE con la estacion. Los permisos BLUETOOTH_SCAN/CONNECT se piden en la UI
 * antes de llamar a [iniciar]. Toda la cola de envio vive en el hilo principal.
 */
@SuppressLint("MissingPermission")
class EstacionBle(contexto: Context) {

    private val ctx = contexto.applicationContext
    private val prefs = ctx.getSharedPreferences("furgo", Context.MODE_PRIVATE)
    private val adaptador: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val hilo = Handler(Looper.getMainLooper())

    val conexion = MutableStateFlow(Conexion.DESCONECTADO)
    val estado = MutableStateFlow<Estado?>(null)
    val ajustes = MutableStateFlow<Ajustes?>(null)
    val encontrados = MutableStateFlow<List<Encontrado>>(emptyList())
    val registro = MutableStateFlow<List<String>>(emptyList())
    val aviso = MutableStateFlow<String?>(null)
    val nombreGuardado = MutableStateFlow(prefs.getString(P_NOMBRE, null))
    val crcEstandar = MutableStateFlow(prefs.getBoolean(P_CRC, false))

    private var gatt: BluetoothGatt? = null
    private var carEscritura: BluetoothGattCharacteristic? = null
    private var deseado = false
    private val cola = ArrayDeque<ByteArray>()
    private var buffer = ByteArray(0)
    private var ultimoTrozo = 0L

    // ---------- API publica (llamar desde el hilo principal) ----------

    /** Conecta con la estacion guardada o, si no hay ninguna, empieza a buscar. */
    fun iniciar() {
        if (conexion.value != Conexion.DESCONECTADO) return
        if (adaptador?.isEnabled != true) {
            aviso.value = "Activa el Bluetooth del móvil"
            return
        }
        aviso.value = null
        val mac = prefs.getString(P_MAC, null)
        if (mac == null) buscar() else conectar(mac, auto = false)
    }

    fun buscar() {
        if (adaptador?.isEnabled != true) {
            aviso.value = "Activa el Bluetooth del móvil"
            return
        }
        aviso.value = null
        desconectar()
        encontrados.value = emptyList()
        conexion.value = Conexion.BUSCANDO
        val ajustes = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adaptador?.bluetoothLeScanner?.startScan(null, ajustes, escaneo)
        hilo.postDelayed(finBusqueda, 20_000)
        log("Buscando dispositivos…")
    }

    fun pararBusqueda() {
        hilo.removeCallbacks(finBusqueda)
        runCatching { adaptador?.bluetoothLeScanner?.stopScan(escaneo) }
        if (conexion.value == Conexion.BUSCANDO) conexion.value = Conexion.DESCONECTADO
    }

    /** Cierra la busqueda sin elegir: si habia una estacion guardada, vuelve a conectar con ella. */
    fun cerrarBusqueda() {
        pararBusqueda()
        encontrados.value = emptyList()
        val mac = prefs.getString(P_MAC, null)
        if (mac != null && conexion.value == Conexion.DESCONECTADO) conectar(mac, auto = false)
    }

    fun elegir(e: Encontrado) {
        pararBusqueda()
        encontrados.value = emptyList()
        prefs.edit().putString(P_MAC, e.mac).putString(P_NOMBRE, e.nombre).apply()
        nombreGuardado.value = e.nombre
        conectar(e.mac, auto = false)
    }

    fun olvidar() {
        desconectar()
        prefs.edit().remove(P_MAC).remove(P_NOMBRE).apply()
        nombreGuardado.value = null
    }

    fun desconectar() {
        deseado = false
        hilo.removeCallbacksAndMessages(null)
        runCatching { adaptador?.bluetoothLeScanner?.stopScan(escaneo) }
        cola.clear()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        carEscritura = null
        estado.value = null
        ajustes.value = null
        conexion.value = Conexion.DESCONECTADO
    }

    fun conmutar(salida: Salida, encender: Boolean) {
        if (conexion.value != Conexion.CONECTADO) return
        cola.addFirst(Modbus.escribir(salida.registro, if (encender) 1 else 0, crcEstandar.value))
        // Reflejo inmediato en pantalla; el siguiente sondeo confirma (o corrige) el estado real
        estado.value = estado.value?.con(salida, encender)
        hilo.removeCallbacks(sondeo)
        hilo.postDelayed(sondeo, 1_500)
    }

    /** Limite inferior de descarga, en % (la estacion admite 0-50). */
    fun fijarLimiteDescarga(porcentaje: Int) {
        val p = porcentaje.coerceIn(0, 50)
        escribirAjuste(Modbus.REG_LIMITE_DESCARGA, p * 10)
        ajustes.value = ajustes.value?.copy(limiteDescarga = p)
    }

    /** Limite superior de carga, en % (la estacion admite 10-100). */
    fun fijarLimiteCarga(porcentaje: Int) {
        val p = porcentaje.coerceIn(10, 100)
        escribirAjuste(Modbus.REG_LIMITE_CARGA, p * 10)
        ajustes.value = ajustes.value?.copy(limiteCarga = p)
    }

    private fun escribirAjuste(registro: Int, valor: Int) {
        if (conexion.value != Conexion.CONECTADO) return
        cola.addFirst(Modbus.escribir(registro, valor, crcEstandar.value))
        // Releer los ajustes para confirmar que la estacion ha aceptado el valor
        hilo.removeCallbacks(pedirAjustes)
        hilo.postDelayed(pedirAjustes, 1_200)
    }

    fun fijarCrcEstandar(v: Boolean) {
        crcEstandar.value = v
        prefs.edit().putBoolean(P_CRC, v).apply()
    }

    // ---------- Busqueda ----------

    private val finBusqueda = Runnable { pararBusqueda() }

    private val escaneo = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val nombre = r.scanRecord?.deviceName ?: r.device.name
            val anunciaServicio = r.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICIO)) == true
            val probable = anunciaServicio || nombre?.startsWith("POWER", ignoreCase = true) == true
            if (nombre == null && !probable) return
            val e = Encontrado(r.device.address, nombre ?: "(sin nombre)", r.rssi, probable)
            encontrados.value = (encontrados.value.filter { it.mac != e.mac } + e)
                .sortedWith(compareByDescending<Encontrado> { it.probable }.thenByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            log("Fallo al buscar (código $errorCode)")
            hilo.post { pararBusqueda() }
        }
    }

    // ---------- Conexion ----------

    private fun conectar(mac: String, auto: Boolean) {
        val adp = adaptador ?: return
        deseado = true
        conexion.value = Conexion.CONECTANDO
        log(if (auto) "Esperando a que la estación esté al alcance…" else "Conectando con $mac…")
        gatt = adp.getRemoteDevice(mac).connectGatt(ctx, auto, callbackGatt, BluetoothDevice.TRANSPORT_LE)
    }

    private fun alPerderConexion() {
        hilo.removeCallbacks(sondeo)
        hilo.removeCallbacks(bombeo)
        cola.clear()
        runCatching { gatt?.close() }
        gatt = null
        carEscritura = null
        estado.value = null
        ajustes.value = null
        val mac = prefs.getString(P_MAC, null)
        if (deseado && mac != null) {
            conexion.value = Conexion.CONECTANDO
            // autoConnect: Android reconecta solo en cuanto la estacion vuelva a estar al alcance
            hilo.postDelayed({ if (deseado && gatt == null) conectar(mac, auto = true) }, 1_500)
        } else {
            conexion.value = Conexion.DESCONECTADO
        }
    }

    private fun listo() {
        conexion.value = Conexion.CONECTADO
        log("Conectado")
        hilo.removeCallbacks(sondeo)
        hilo.removeCallbacks(bombeo)
        hilo.post(sondeo)
        hilo.post(bombeo)
        hilo.postDelayed(pedirAjustes, 800)
    }

    private val callbackGatt = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Enlace establecido, negociando MTU")
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Desconectado (estado $status)")
                hilo.post { if (g === gatt) alPerderConexion() }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU $mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val servicio = g.getService(SERVICIO)
            val escritura = servicio?.getCharacteristic(CAR_ESCRITURA)
            val notificacion = servicio?.getCharacteristic(CAR_NOTIFICACION)
            if (escritura == null || notificacion == null) {
                log("Este dispositivo no tiene el servicio A002: no es la estación")
                aviso.value = "Ese dispositivo no parece la estación de energía"
                hilo.post { olvidar() }
                return
            }
            carEscritura = escritura
            g.setCharacteristicNotification(notificacion, true)
            val cccd = notificacion.getDescriptor(CCCD)
            if (cccd == null) {
                hilo.post { listo() }
                return
            }
            val valor = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, valor)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = valor
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            hilo.post { listo() }
        }

        // API 33+: Android llama a las dos versiones; atendemos solo la nueva
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            recibir(value)
        }

        @Deprecated("Solo para Android 12")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) c.value?.let { recibir(it.copyOf()) }
        }
    }

    // ---------- Envio y recepcion ----------

    private val sondeo = object : Runnable {
        override fun run() {
            if (cola.isEmpty()) cola.addLast(Modbus.leerEstado(crcEstandar.value))
            hilo.postDelayed(this, 2_000)
        }
    }

    private val pedirAjustes = Runnable {
        if (conexion.value == Conexion.CONECTADO) cola.addLast(Modbus.leerAjustes(crcEstandar.value))
    }

    /** Vacia la cola dejando un hueco entre tramas para no pisar operaciones GATT. */
    private val bombeo = object : Runnable {
        override fun run() {
            val enviada = cola.removeFirstOrNull()
            enviada?.let { enviar(it) }
            // Tras pedir una lectura se deja mas margen para que llegue entera la respuesta (troceada)
            val esLectura = enviada != null && enviada[1].toInt() != Modbus.FN_ESCRIBIR
            hilo.postDelayed(this, if (esLectura) 600 else 250)
        }
    }

    private fun enviar(bytes: ByteArray) {
        val g = gatt ?: return
        val c = carEscritura ?: return
        val sinRespuesta = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val tipo = if (sinRespuesta) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        // Las lecturas periodicas no se registran para no inundar el log
        if (bytes[1].toInt() == Modbus.FN_ESCRIBIR) log("TX " + Modbus.hex(bytes))
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, tipo)
        } else {
            @Suppress("DEPRECATION")
            c.writeType = tipo
            @Suppress("DEPRECATION")
            c.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    /** Las respuestas largas llegan troceadas segun el MTU: se reensamblan aqui. */
    @Synchronized
    private fun recibir(v: ByteArray) {
        if (v.isEmpty()) return
        val ahora = SystemClock.elapsedRealtime()
        val fn = if (v.size >= 2) v[1].toInt() and 0xFF else 0
        val esLectura = v[0] == Modbus.DIRECCION.toByte() &&
            (fn == Modbus.FN_LEER_ESTADO || fn == Modbus.FN_LEER_AJUSTES)
        when {
            esLectura && (buffer.isEmpty() || ahora - ultimoTrozo > 500) -> buffer = v
            buffer.isNotEmpty() && ahora - ultimoTrozo <= 500 -> buffer += v
            else -> {
                // Eco de una escritura, excepcion Modbus o algo inesperado
                buffer = ByteArray(0)
                log("RX " + Modbus.hex(v))
                return
            }
        }
        ultimoTrozo = ahora
        if (buffer.size >= Modbus.LONGITUD_RESPUESTA) {
            val completa = buffer
            buffer = ByteArray(0)
            if ((completa[1].toInt() and 0xFF) == Modbus.FN_LEER_ESTADO) {
                if (estado.value == null) log("RX estado " + Modbus.hex(completa, 12))
                estado.value = Estado.de(completa)
            } else {
                val nuevos = Ajustes.de(completa)
                log("RX ajustes: descarga ${nuevos.limiteDescarga}% · carga ${nuevos.limiteCarga}%")
                ajustes.value = nuevos
            }
        }
    }

    private fun log(linea: String) {
        registro.value = (registro.value + linea).takeLast(80)
    }

    private companion object {
        const val P_MAC = "mac"
        const val P_NOMBRE = "nombre"
        const val P_CRC = "crcEstandar"
        val SERVICIO: UUID = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb")
        val CAR_ESCRITURA: UUID = UUID.fromString("0000c304-0000-1000-8000-00805f9b34fb")
        val CAR_NOTIFICACION: UUID = UUID.fromString("0000c305-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
