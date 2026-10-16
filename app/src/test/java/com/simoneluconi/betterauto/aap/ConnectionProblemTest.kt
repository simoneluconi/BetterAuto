package com.simoneluconi.betterauto.aap

import aap_protobuf.service.control.message.ByeByeReasonOuterClass.ByeByeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionProblemTest {

    @Test
    fun authStatusesBecomeProblemsTheDriverCanActOn() {
        assertNull(ConnectionProblem.ofAuthStatus(0))
        assertEquals(ConnectionProblem.NO_COMPATIBLE_VERSION, ConnectionProblem.ofAuthStatus(-1))
        assertEquals(ConnectionProblem.CERTIFICATE_REJECTED, ConnectionProblem.ofAuthStatus(-2))
        // What a Volkswagen head unit answers to the self-signed certificate
        assertEquals(ConnectionProblem.CERTIFICATE_REJECTED, ConnectionProblem.ofAuthStatus(-3))
        assertEquals(ConnectionProblem.CERTIFICATE_DATES, ConnectionProblem.ofAuthStatus(-23))
        assertEquals(ConnectionProblem.CERTIFICATE_DATES, ConnectionProblem.ofAuthStatus(-24))
        assertEquals(ConnectionProblem.AUTH_FAILED, ConnectionProblem.ofAuthStatus(-7))
        assertEquals(ConnectionProblem.AUTH_FAILED, ConnectionProblem.ofAuthStatus(-999))
    }

    @Test
    fun onlyByeByesWithAProblemAreReported() {
        assertEquals(ConnectionProblem.NOT_SUPPORTED, ConnectionProblem.ofByeBye(ByeByeReason.NOT_SUPPORTED))
        assertEquals(ConnectionProblem.NOT_SUPPORTED, ConnectionProblem.ofByeBye(ByeByeReason.NOT_CURRENTLY_SUPPORTED))
        assertEquals(ConnectionProblem.DEVICE_SWITCH, ConnectionProblem.ofByeBye(ByeByeReason.DEVICE_SWITCH))
        assertNull(ConnectionProblem.ofByeBye(ByeByeReason.USER_SELECTION))
        assertNull(ConnectionProblem.ofByeBye(ByeByeReason.PROBE_SUPPORTED))
    }

    @Test
    fun carMakerFromTheHeadUnitCertificate() {
        assertEquals("Volkswagen", AapTls.organizationOf("OU=09,O=Volkswagen,L=Wolfsburg,ST=Lower Saxony,C=DE"))
        assertEquals("Acme, Inc.", AapTls.organizationOf("CN=hu,O=Acme\\, Inc.,C=US"))
        assertEquals("First", AapTls.organizationOf("O=First,OU=Unit"))
        assertNull(AapTls.organizationOf("CN=hu,OU=Unit"))
    }
}
