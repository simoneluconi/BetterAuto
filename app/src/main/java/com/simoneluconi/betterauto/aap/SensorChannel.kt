package com.simoneluconi.betterauto.aap

import aap_protobuf.service.sensorsource.SensorMessageIdOuterClass.SensorMessageId
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorRequestOuterClass.SensorRequest
import aap_protobuf.service.sensorsource.message.SensorResponseOuterClass.SensorResponse
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.car.CarState

/** Car sensors we subscribe to. For now only night mode, which drives the day/night theme. */
class SensorChannel(
    session: AapSession,
    channelId: Int,
    private val available: List<SensorType>,
) : ServiceChannel(session, channelId) {
    override val name = "sensors"

    override fun onOpened() {
        for (type in listOf(SensorType.SENSOR_NIGHT_MODE)) {
            if (type !in available) {
                EventLog.log("Sensors: car does not offer $type")
                continue
            }
            send(
                SensorMessageId.SENSOR_MESSAGE_REQUEST_VALUE,
                SensorRequest.newBuilder().setType(type).setMinUpdatePeriod(0).build(),
            )
        }
    }

    override fun onChannelMessage(msg: AapMessage) {
        when (msg.id) {
            SensorMessageId.SENSOR_MESSAGE_RESPONSE_VALUE ->
                EventLog.log("Sensors: request ${SensorResponse.parseFrom(msg.payload).status}")
            SensorMessageId.SENSOR_MESSAGE_BATCH_VALUE -> {
                val batch = SensorBatch.parseFrom(msg.payload)
                batch.nightModeDataList.lastOrNull()?.takeIf { it.hasNightMode() }?.let {
                    if (CarState.nightMode.value != it.nightMode) EventLog.log("Night mode from car: ${it.nightMode}")
                    CarState.nightMode.value = it.nightMode
                }
            }
            else -> EventLog.log("Sensors: unhandled message ${msg.id} (${msg.payload.size} bytes)")
        }
    }
}
