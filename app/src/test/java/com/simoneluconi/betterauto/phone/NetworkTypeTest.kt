package com.simoneluconi.betterauto.phone

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkTypeTest {
    @Test
    fun theCarrierOverrideWins() {
        // 5G on a 4G anchor (the usual 5G in Europe) and 4G with carrier aggregation
        assertEquals("5G", networkLabel(TelephonyManager.NETWORK_TYPE_LTE, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA))
        assertEquals("4G+", networkLabel(TelephonyManager.NETWORK_TYPE_LTE, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA))
    }

    @Test
    fun eachTechnologyHasItsLabel() {
        assertEquals("5G", networkLabel(TelephonyManager.NETWORK_TYPE_NR))
        assertEquals("4G", networkLabel(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("H+", networkLabel(TelephonyManager.NETWORK_TYPE_HSPAP))
        assertEquals("H", networkLabel(TelephonyManager.NETWORK_TYPE_HSDPA))
        assertEquals("3G", networkLabel(TelephonyManager.NETWORK_TYPE_UMTS))
        assertEquals("E", networkLabel(TelephonyManager.NETWORK_TYPE_EDGE))
        assertEquals("G", networkLabel(TelephonyManager.NETWORK_TYPE_GPRS))
        assertEquals("2G", networkLabel(TelephonyManager.NETWORK_TYPE_GSM))
    }

    @Test
    fun nothingWithoutService() {
        assertNull(networkLabel(TelephonyManager.NETWORK_TYPE_UNKNOWN))
        assertNull(networkLabel(TelephonyManager.NETWORK_TYPE_IWLAN))
    }
}
