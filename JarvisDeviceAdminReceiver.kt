package com.jarvis.ai.device

import android.app.admin.DeviceAdminReceiver

/**
 * Device admin receiver. Its only granted power is "force-lock" (see res/xml/device_admin.xml),
 * which lets Jarvis lock the screen. The user must turn it on in Settings.
 */
class JarvisDeviceAdminReceiver : DeviceAdminReceiver()
