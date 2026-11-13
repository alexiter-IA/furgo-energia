package com.furgo.energia

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Outlet
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

private val Fondo = Color(0xFF0E1116)
private val Tarjeta = Color(0xFF1A1F27)
private val Lima = Color(0xFFB6F23C)
private val Naranja = Color(0xFFFF8A3D)
private val Rojo = Color(0xFFFF5A5A)
private val Tenue = Color(0xFF8B94A3)
private val Forma = RoundedCornerShape(20.dp)

class EstacionViewModel(app: Application) : AndroidViewModel(app) {
    val ble = EstacionBle(app)
    override fun onCleared() = ble.desconectar()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Lima,
                    onPrimary = Fondo,
                    background = Fondo,
                    surface = Tarjeta,
                    error = Rojo,
                ),
            ) {
                Surface(Modifier.fillMaxSize(), color = Fondo) { Pantalla() }
            }
        }
    }
}

@Composable
private fun Pantalla(vm: EstacionViewModel = viewModel()) {
    val ble = vm.ble
    val contexto = LocalContext.current
    val permisos = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    var concedidos by remember {
        mutableStateOf(
            permisos.all {
                ContextCompat.checkSelfPermission(contexto, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val pedir = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { r -> concedidos = r.values.all { it } }

    LaunchedEffect(Unit) { if (!concedidos) pedir.launch(permisos) }
    LaunchedEffect(concedidos) { if (concedidos) ble.iniciar() }

    val conexion by ble.conexion.collectAsStateWithLifecycle()
    val estado by ble.estado.collectAsStateWithLifecycle()
    val ajustes by ble.ajustes.collectAsStateWithLifecycle()
    val encontrados by ble.encontrados.collectAsStateWithLifecycle()
    val registro by ble.registro.collectAsStateWithLifecycle()
    val aviso by ble.aviso.collectAsStateWithLifecycle()
    val nombre by ble.nombreGuardado.collectAsStateWithLifecycle()
    val crcEstandar by ble.crcEstandar.collectAsStateWithLifecycle()
    var verDiagnostico by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cabecera(conexion, nombre)

        if (!concedidos) {
            Aviso("La app necesita el permiso de «Dispositivos cercanos» para hablar con la estación.")
            Button(onClick = { pedir.launch(permisos) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Bluetooth, null)
                Spacer(Modifier.width(8.dp))
                Text("Conceder permiso")
            }
            return@Column
        }
        aviso?.let { Aviso(it) }

        TarjetaBateria(estado)

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TarjetaPotencia(
                Icons.Filled.SolarPower, "Entrada", estado?.entradaW,
                estado?.let { "Solar/DC ${it.entradaDcW} W · AC ${it.entradaAcW} W" },
                Lima, Modifier.weight(1f),
            )
            TarjetaPotencia(
                Icons.Filled.Bolt, "Salida", estado?.salidaW, null, Naranja, Modifier.weight(1f),
            )
        }

        val activo = conexion == Conexion.CONECTADO && estado != null
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BotonSalida(Icons.Filled.Usb, "USB", Salida.USB, estado, activo, ble, Modifier.weight(1f))
            BotonSalida(
                Icons.Filled.ElectricalServices, "12 V", Salida.DC, estado, activo, ble,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BotonSalida(Icons.Filled.Outlet, "220 V", Salida.AC, estado, activo, ble, Modifier.weight(1f))
            BotonSalida(Icons.Filled.Lightbulb, "Luz", Salida.LUZ, estado, activo, ble, Modifier.weight(1f))
        }

        ajustes?.let { TarjetaLimites(it, ble) }

        OutlinedButton(onClick = { ble.buscar() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Search, null)
            Spacer(Modifier.width(8.dp))
            Text(if (nombre == null) "Buscar estación" else "Buscar otra estación")
        }

        TextButton(onClick = { verDiagnostico = !verDiagnostico }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Terminal, null, tint = Tenue)
            Spacer(Modifier.width(8.dp))
            Text("Diagnóstico", color = Tenue)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (verDiagnostico) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                tint = Tenue,
            )
        }
        if (verDiagnostico) {
            Diagnostico(registro, crcEstandar, nombre != null, ble)
        }
    }

    // Tras los 20 s de busqueda la lista sigue visible para poder elegir con calma
    if (conexion == Conexion.BUSCANDO || (encontrados.isNotEmpty() && conexion == Conexion.DESCONECTADO)) {
        DialogoBusqueda(encontrados, conexion == Conexion.BUSCANDO, ble)
    }
}

@Composable
private fun Cabecera(conexion: Conexion, nombre: String?) {
    val (icono, texto, color) = when (conexion) {
        Conexion.CONECTADO -> Triple(Icons.Filled.BluetoothConnected, nombre ?: "Conectado", Lima)
        Conexion.CONECTANDO -> Triple(Icons.Filled.BluetoothSearching, "Conectando…", Naranja)
        Conexion.BUSCANDO -> Triple(Icons.Filled.BluetoothSearching, "Buscando…", Naranja)
        Conexion.DESCONECTADO -> Triple(Icons.Filled.BluetoothDisabled, "Sin conexión", Tenue)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Furgo Energía", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("FOSSiBOT F2400 · sin internet", fontSize = 13.sp, color = Tenue)
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Tarjeta)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icono, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(texto, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Aviso(texto: String) {
    Text(
        texto,
        color = Naranja,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(Forma)
            .background(Tarjeta)
            .padding(16.dp),
    )
}

@Composable
private fun TarjetaBateria(estado: Estado?) {
    val soc = estado?.soc
    val color = when {
        soc == null -> Tenue
        soc < 15f -> Rojo
        soc < 35f -> Naranja
        else -> Lima
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Forma)
            .background(Tarjeta)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { ((soc ?: 0f) / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.size(120.dp),
                color = color,
                trackColor = Fondo,
                strokeWidth = 10.dp,
                strokeCap = StrokeCap.Round,
            )
            Text(
                if (soc == null) "--" else "${soc.toInt()}%",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text("Batería", color = Tenue, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            val cargando = estado != null && estado.entradaW > estado.salidaW
            val minutos = when {
                estado == null -> null
                cargando -> estado.minutosCarga
                else -> estado.minutosRestantes
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Timer, null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    duracion(minutos),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
            Text(
                when {
                    estado == null -> "Esperando datos"
                    cargando -> "hasta carga completa"
                    else -> "de autonomía"
                },
                color = Tenue,
                fontSize = 13.sp,
            )
        }
    }
}

private fun duracion(minutos: Int?): String = when {
    minutos == null || minutos <= 0 -> "--"
    minutos < 60 -> "$minutos min"
    else -> "${minutos / 60} h ${minutos % 60} min"
}

@Composable
private fun TarjetaPotencia(
    icono: ImageVector,
    titulo: String,
    vatios: Int?,
    detalle: String?,
    color: Color,
    modifier: Modifier,
) {
    Column(
        modifier
            .clip(Forma)
            .background(Tarjeta)
            .heightIn(min = 118.dp)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icono, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(titulo, color = Tenue, fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (vatios == null) "-- W" else "$vatios W",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        if (detalle != null) Text(detalle, color = Tenue, fontSize = 11.sp)
    }
}

@Composable
private fun BotonSalida(
    icono: ImageVector,
    titulo: String,
    salida: Salida,
    estado: Estado?,
    activo: Boolean,
    ble: EstacionBle,
    modifier: Modifier,
) {
    val encendida = estado?.encendida(salida) == true
    val fondo = if (encendida) Lima else Tarjeta
    val tinta = when {
        !activo -> Tenue.copy(alpha = 0.5f)
        encendida -> Fondo
        else -> Color.White
    }
    Column(
        modifier
            .clip(Forma)
            .background(fondo)
            .clickable(enabled = activo) { ble.conmutar(salida, !encendida) }
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icono, null, tint = tinta, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(titulo, color = tinta, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            if (encendida) "ENCENDIDO" else "APAGADO",
            color = tinta.copy(alpha = 0.75f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TarjetaLimites(ajustes: Ajustes, ble: EstacionBle) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Forma)
            .background(Tarjeta)
            .padding(16.dp),
    ) {
        Text("Límites de batería", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Limite(
            Icons.Filled.BatteryAlert, "Cortar la descarga al", ajustes.limiteDescarga,
            minimo = 0, maximo = 50, color = Naranja,
        ) { ble.fijarLimiteDescarga(it) }
        Limite(
            Icons.Filled.BatteryChargingFull, "Parar la carga al", ajustes.limiteCarga,
            minimo = 50, maximo = 100, color = Lima,
        ) { ble.fijarLimiteCarga(it) }
    }
}

/** Deslizador en pasos del 5 %: solo escribe en la estacion al soltar el dedo. */
@Composable
private fun Limite(
    icono: ImageVector,
    titulo: String,
    valor: Int,
    minimo: Int,
    maximo: Int,
    color: Color,
    alFijar: (Int) -> Unit,
) {
    var posicion by remember(valor) { mutableFloatStateOf(valor.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icono, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(titulo, color = Tenue, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            "${posicion.roundToInt()} %",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
    Slider(
        value = posicion.coerceIn(minimo.toFloat(), maximo.toFloat()),
        onValueChange = { posicion = it },
        onValueChangeFinished = { alFijar(posicion.roundToInt()) },
        valueRange = minimo.toFloat()..maximo.toFloat(),
        steps = (maximo - minimo) / 5 - 1,
        colors = SliderDefaults.colors(
            thumbColor = color,
            activeTrackColor = color,
            inactiveTrackColor = Fondo,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
    )
}

@Composable
private fun Diagnostico(registro: List<String>, crcEstandar: Boolean, hayGuardada: Boolean, ble: EstacionBle) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Forma)
            .background(Tarjeta)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CRC en orden estándar", color = Color.White, fontSize = 14.sp)
                Text(
                    "Actívalo solo si la estación no responde a los botones.",
                    color = Tenue,
                    fontSize = 12.sp,
                )
            }
            Switch(checked = crcEstandar, onCheckedChange = { ble.fijarCrcEstandar(it) })
        }
        Text(
            if (registro.isEmpty()) "Sin actividad" else registro.takeLast(25).joinToString("\n"),
            color = Tenue,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 15.sp,
        )
        if (hayGuardada) {
            OutlinedButton(
                onClick = { ble.olvidar() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Rojo),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.DeleteForever, null)
                Spacer(Modifier.width(8.dp))
                Text("Olvidar esta estación")
            }
        }
    }
}

@Composable
private fun DialogoBusqueda(encontrados: List<Encontrado>, buscando: Boolean, ble: EstacionBle) {
    AlertDialog(
        onDismissRequest = { ble.cerrarBusqueda() },
        containerColor = Tarjeta,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Elige tu estación", modifier = Modifier.weight(1f))
                if (buscando) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
            }
        },
        text = {
            if (encontrados.isEmpty()) {
                Text("Buscando dispositivos Bluetooth cercanos…", color = Tenue)
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(encontrados, key = { it.mac }) { e ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { ble.elegir(e) }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (e.probable) Icons.Filled.Bolt else Icons.Filled.Bluetooth,
                                null,
                                tint = if (e.probable) Lima else Tenue,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    e.nombre,
                                    color = Color.White,
                                    fontWeight = if (e.probable) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(
                                    if (e.probable) "Parece la estación · ${e.mac}" else e.mac,
                                    color = Tenue,
                                    fontSize = 11.sp,
                                )
                            }
                            Text("${e.rssi} dBm", color = Tenue, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { ble.cerrarBusqueda() }) {
                Icon(Icons.Filled.Close, null)
                Spacer(Modifier.width(6.dp))
                Text("Cerrar")
            }
        },
    )
}
