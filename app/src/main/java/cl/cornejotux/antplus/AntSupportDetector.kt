package cl.cornejotux.antplus

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** Detecta ANT+ integrado y la ruta alternativa por USB Host/dongle. */
class AntSupportDetector(private val context: Context) {
    fun isReady(): Boolean = hasRadioService() && hasPluginsInstalled()

    fun hasRadioService() = installed("com.dsi.ant.service.socket")
    fun hasPluginsInstalled() = installed("com.dsi.ant.plugins.antplus")
    fun hasUsbServiceInstalled() = installed("com.dsi.ant.usbservice")

    fun setupReport(): String = buildString {
        appendLine(if (hasRadioService()) "✓ ANT Radio Service disponible" else "✗ Falta ANT Radio Service")
        appendLine(if (hasUsbServiceInstalled()) "✓ ANT USB Service disponible" else "○ ANT USB Service opcional/no compatible")
        appendLine(if (hasPluginsInstalled()) "✓ ANT+ Plugins disponible" else "✗ Faltan ANT+ Plugins")
        append(if (supportsUsbHost()) "✓ USB Host disponible" else "✗ USB Host no disponible")
    }
    fun describe(): String {
        if (hasRadioService()) return "ANT+ Radio Service disponible"
        if (hasUsbDongle()) return "ANT+ nativo no disponible; dongle USB detectado"
        if (supportsUsbHost()) return "ANT+ nativo no disponible; USB Host disponible, conecta un dongle"
        return "Este teléfono no muestra ANT+ nativo ni soporte USB Host"
    }

    private fun installed(packageName: String) = try { context.packageManager.getPackageInfo(packageName, 0); true } catch (_: Exception) { false }

    private fun supportsUsbHost() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private fun hasUsbDongle(): Boolean {
        val manager = context.getSystemService(UsbManager::class.java)
        return manager.deviceList.values.any(::looksLikeAntDongle)
    }

    private fun looksLikeAntDongle(device: UsbDevice): Boolean {
        // Los dongles pueden variar de fabricante; se excluyen periféricos comunes.
        val name = (device.productName.orEmpty() + " " + device.manufacturerName.orEmpty()).lowercase()
        return name.contains("ant") || name.contains("dynastream") || name.contains("garmin")
    }
}
