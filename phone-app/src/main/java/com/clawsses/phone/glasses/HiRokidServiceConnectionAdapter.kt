package com.clawsses.phone.glasses

import android.content.ServiceConnection
import com.rokid.cxr.link.CXRLink

/** Isolates the private CXR-L 1.1.1 service-connection compatibility boundary. */
internal fun interface HiRokidServiceConnectionAdapter {
    fun connectionFor(link: CXRLink): ServiceConnection
}

internal object CxrLink111ServiceConnectionAdapter : HiRokidServiceConnectionAdapter {
    const val SUPPORTED_SDK_VERSION = "1.1.1"

    override fun connectionFor(link: CXRLink): ServiceConnection =
        findAssignableInstanceField(link, ServiceConnection::class.java)
            ?: error(
                "Hi Rokid CXR-L $SUPPORTED_SDK_VERSION ServiceConnection is unavailable.",
            )
}

internal fun <T : Any> findAssignableInstanceField(instance: Any, expectedType: Class<T>): T? {
    var type: Class<*>? = instance.javaClass
    while (type != null) {
        val field = type.declaredFields.firstOrNull { expectedType.isAssignableFrom(it.type) }
        if (field != null) {
            field.isAccessible = true
            return expectedType.cast(field.get(instance))
        }
        type = type.superclass
    }
    return null
}
