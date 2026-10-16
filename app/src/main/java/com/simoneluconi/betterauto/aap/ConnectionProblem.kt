package com.simoneluconi.betterauto.aap

import aap_protobuf.service.control.message.ByeByeReasonOuterClass.ByeByeReason
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus

/**
 * Why a connection to the car did not work, grouped by what the driver can do about it.
 * [com.simoneluconi.betterauto.AapService] shows each one as a notification with its own explanation.
 */
enum class ConnectionProblem {
    /** AUTH_COMPLETE -1: no protocol version both sides speak. */
    NO_COMPATIBLE_VERSION,

    /** AUTH_COMPLETE -2 / -3: the car checks the phone certificate, and only Google issues the ones it accepts. */
    CERTIFICATE_REJECTED,

    /** AUTH_COMPLETE -23 / -24: the certificate is not yet valid or expired for the car's clock. */
    CERTIFICATE_DATES,

    /** Any other AUTH_COMPLETE status: shown with its code. */
    AUTH_FAILED,

    /** BYEBYE_REQUEST NOT_SUPPORTED / NOT_CURRENTLY_SUPPORTED. */
    NOT_SUPPORTED,

    /** BYEBYE_REQUEST DEVICE_SWITCH: the car moved to another phone. */
    DEVICE_SWITCH,

    /** The link dropped before the car listed its channels, with no reason given. */
    DROPPED_DURING_SETUP,

    /** Android could not open the USB accessory the car announced. */
    USB_NOT_OPENED,

    /** No usable private key and certificate among the app's PEM files: nothing to start TLS with. */
    PHONE_CERTIFICATE;

    companion object {
        fun ofAuthStatus(status: Int): ConnectionProblem? = when (status) {
            MessageStatus.STATUS_SUCCESS_VALUE -> null
            MessageStatus.STATUS_NO_COMPATIBLE_VERSION_VALUE -> NO_COMPATIBLE_VERSION
            MessageStatus.STATUS_CERTIFICATE_ERROR_VALUE,
            MessageStatus.STATUS_AUTHENTICATION_FAILURE_VALUE -> CERTIFICATE_REJECTED
            MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_NOT_YET_VALID_VALUE,
            MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED_VALUE -> CERTIFICATE_DATES
            else -> AUTH_FAILED
        }

        /** Null when the driver ended the session from the car, or for a probe: nothing went wrong. */
        fun ofByeBye(reason: ByeByeReason): ConnectionProblem? = when (reason) {
            ByeByeReason.NOT_SUPPORTED, ByeByeReason.NOT_CURRENTLY_SUPPORTED -> NOT_SUPPORTED
            ByeByeReason.DEVICE_SWITCH -> DEVICE_SWITCH
            else -> null
        }
    }
}
