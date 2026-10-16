package com.simoneluconi.betterauto.aap

import aap_protobuf.service.sensorsource.SensorMessageIdOuterClass.SensorMessageId
import aap_protobuf.service.sensorsource.message.GearOuterClass
import aap_protobuf.service.sensorsource.message.HeadLightStateOuterClass.HeadLightState
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorRequestOuterClass.SensorRequest
import aap_protobuf.service.sensorsource.message.SensorResponseOuterClass.SensorResponse
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import aap_protobuf.service.sensorsource.message.TurnIndicatorStateOuterClass.TurnIndicatorState
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.car.Doors
import com.simoneluconi.betterauto.car.Environment
import com.simoneluconi.betterauto.car.FaultCodes
import com.simoneluconi.betterauto.car.Fuel
import com.simoneluconi.betterauto.car.Gear
import com.simoneluconi.betterauto.car.GearPosition
import com.simoneluconi.betterauto.car.HeadLights
import com.simoneluconi.betterauto.car.Hvac
import com.simoneluconi.betterauto.car.Lights
import com.simoneluconi.betterauto.car.Motion
import com.simoneluconi.betterauto.car.Odometer
import com.simoneluconi.betterauto.car.Position
import com.simoneluconi.betterauto.car.Satellites
import com.simoneluconi.betterauto.car.Speed
import com.simoneluconi.betterauto.car.Timed
import com.simoneluconi.betterauto.car.Tires
import com.simoneluconi.betterauto.car.TurnSignal
import com.simoneluconi.betterauto.car.VehicleData

/**
 * Car sensors: everything the car offers is requested. Night mode drives the theme; the rest feeds
 * [VehicleData]. The first sample of each kind is logged, without coordinates or other personal data,
 * to see what a car really sends.
 */
class SensorChannel(
    session: AapSession,
    channelId: Int,
    private val available: List<SensorType>,
) : ServiceChannel(session, channelId) {
    override val name = "sensors"

    private val logged = HashSet<String>()

    override fun onOpened() {
        for (type in available) {
            // Continuous values at most once a second, everything else as it changes
            val period = if (type in Continuous) 1000L else 0L
            send(
                SensorMessageId.SENSOR_MESSAGE_REQUEST_VALUE,
                SensorRequest.newBuilder().setType(type).setMinUpdatePeriod(period).build(),
            )
        }
    }

    override fun onChannelMessage(msg: AapMessage) {
        when (msg.id) {
            SensorMessageId.SENSOR_MESSAGE_RESPONSE_VALUE -> {
                val status = SensorResponse.parseFrom(msg.payload).status
                if (logged.add("response $status")) EventLog.log("Sensors: request $status")
            }
            SensorMessageId.SENSOR_MESSAGE_BATCH_VALUE -> onBatch(SensorBatch.parseFrom(msg.payload))
            else -> EventLog.log("Sensors: unhandled message ${msg.id} (${msg.payload.size} bytes)")
        }
    }

    private fun onBatch(batch: SensorBatch) {
        batch.nightModeDataList.lastOrNull()?.takeIf { it.hasNightMode() }?.let {
            if (CarState.nightMode.value != it.nightMode) EventLog.log("Night mode from car: ${it.nightMode}")
            CarState.nightMode.value = it.nightMode
        }
        batch.fuelDataList.lastOrNull()?.let { d ->
            first("fuel", "level ${d.fuelLevel}%, range ${d.range} km, low ${d.lowFuelWarning}")
            VehicleData.update {
                it.copy(fuel = Timed(Fuel(d.fuelLevel.takeIf { d.hasFuelLevel() }, d.range.takeIf { d.hasRange() }, d.lowFuelWarning.takeIf { d.hasLowFuelWarning() })))
            }
        }
        batch.odometerDataList.lastOrNull()?.let { d ->
            first("odometer", "present")
            VehicleData.update { it.copy(odometer = Timed(Odometer(d.kmsE1 / 10.0, if (d.hasTripKmsE1()) d.tripKmsE1 / 10.0 else null))) }
        }
        batch.tirePressureDataList.lastOrNull()?.let { d ->
            first("tires", "${d.tirePressuresE2Count} values")
            // kPa x100 to bar
            VehicleData.update { it.copy(tires = Timed(Tires(d.tirePressuresE2List.map { p -> p / 100.0 / 100.0 }))) }
        }
        batch.environmentDataList.lastOrNull()?.let { d ->
            first("environment", "temperature ${d.hasTemperatureE3()}, pressure ${d.hasPressureE3()}, rain ${d.hasRain()}")
            VehicleData.update {
                it.copy(
                    environment = Timed(
                        Environment(
                            if (d.hasTemperatureE3()) d.temperatureE3 / 1000.0 else null,
                            if (d.hasPressureE3()) d.pressureE3 / 1000.0 else null,
                            if (d.hasRain()) d.rain else null,
                        )
                    )
                )
            }
        }
        batch.hvacDataList.lastOrNull()?.let { d ->
            first("hvac", "present")
            VehicleData.update {
                it.copy(hvac = Timed(Hvac(if (d.hasTargetTemperatureE3()) d.targetTemperatureE3 / 1000.0 else null, if (d.hasCurrentTemperatureE3()) d.currentTemperatureE3 / 1000.0 else null)))
            }
        }
        batch.doorDataList.lastOrNull()?.let { d ->
            first("doors", "${d.doorOpenCount} doors")
            VehicleData.update {
                it.copy(doors = Timed(Doors(d.hoodOpen.takeIf { d.hasHoodOpen() }, d.trunkOpen.takeIf { d.hasTrunkOpen() }, d.doorOpenList)))
            }
        }
        batch.lightDataList.lastOrNull()?.let { d ->
            first("lights", "present")
            val head = if (!d.hasHeadLightState()) null else when (d.headLightState) {
                HeadLightState.HEAD_LIGHT_STATE_ON -> HeadLights.ON
                HeadLightState.HEAD_LIGHT_STATE_HIGH -> HeadLights.HIGH
                else -> HeadLights.OFF
            }
            val turn = if (!d.hasTurnIndicatorState()) null else when (d.turnIndicatorState) {
                TurnIndicatorState.TURN_INDICATOR_LEFT -> TurnSignal.LEFT
                TurnIndicatorState.TURN_INDICATOR_RIGHT -> TurnSignal.RIGHT
                else -> TurnSignal.NONE
            }
            VehicleData.update { it.copy(lights = Timed(Lights(head, turn, d.hazardLightsOn.takeIf { d.hasHazardLightsOn() }))) }
        }
        batch.diagnosticsDataList.lastOrNull()?.let { d ->
            val codes = FaultCodes.parse(d.dtc.toByteArray())
            val previous = VehicleData.faultCodes
            if (codes != previous) EventLog.log("Diagnostics: ${codes.size} fault codes ${codes.joinToString()} (${d.dtc.size()} bytes)")
            VehicleData.update { it.copy(diagnostics = Timed(codes)) }
        }
        batch.locationDataList.lastOrNull()?.takeIf { it.latitudeE7 != 0 || it.longitudeE7 != 0 }?.let { d ->
            first("location", "accuracy ${if (d.hasAccuracyE3()) d.accuracyE3 / 1000 else "?"} m")
            VehicleData.update {
                it.copy(
                    position = Timed(
                        Position(
                            latitude = d.latitudeE7 / 1e7,
                            longitude = d.longitudeE7 / 1e7,
                            accuracyM = if (d.hasAccuracyE3()) d.accuracyE3 / 1000.0 else null,
                            altitudeM = if (d.hasAltitudeE2()) d.altitudeE2 / 100.0 else null,
                            speedKmh = if (d.hasSpeedE3()) d.speedE3 / 1000.0 * 3.6 else null,
                            bearing = if (d.hasBearingE6()) d.bearingE6 / 1e6 else null,
                        )
                    )
                )
            }
        }
        batch.gpsSatelliteDataList.lastOrNull()?.let { d ->
            first("satellites", "${d.numberInUse} in use")
            VehicleData.update { it.copy(satellites = Timed(Satellites(d.numberInUse, if (d.hasNumberInView()) d.numberInView else null))) }
        }
        batch.gearDataList.lastOrNull()?.let { d ->
            first("gear", d.gear.name)
            val gear = when (d.gear) {
                GearOuterClass.Gear.GEAR_PARK -> Gear(GearPosition.PARK, null)
                GearOuterClass.Gear.GEAR_REVERSE -> Gear(GearPosition.REVERSE, null)
                GearOuterClass.Gear.GEAR_NEUTRAL -> Gear(GearPosition.NEUTRAL, null)
                GearOuterClass.Gear.GEAR_DRIVE -> Gear(GearPosition.DRIVE, null)
                else -> Gear(GearPosition.MANUAL, d.gear.number)
            }
            VehicleData.update { it.copy(gear = Timed(gear)) }
        }
        batch.parkingBrakeDataList.lastOrNull()?.let { d ->
            first("parking brake", d.parkingBrake.toString())
            VehicleData.update { it.copy(parkingBrake = Timed(d.parkingBrake)) }
        }
        batch.drivingStatusDataList.lastOrNull()?.let { d ->
            first("driving status", d.status.toString())
            if (VehicleData.snapshot.value.drivingStatus?.value != d.status) EventLog.log("Driving status: ${d.status}")
            VehicleData.update { it.copy(drivingStatus = Timed(d.status)) }
        }
        batch.speedDataList.lastOrNull()?.let { d ->
            first("speed", "present, cruise ${d.hasCruiseEngaged()}")
            VehicleData.update {
                it.copy(speed = Timed(Speed(d.speedE3 / 1000.0 * 3.6, d.cruiseEngaged.takeIf { d.hasCruiseEngaged() }, d.cruiseSetSpeed.takeIf { d.hasCruiseSetSpeed() })))
            }
        }
        batch.rpmDataList.lastOrNull()?.let { d ->
            first("rpm", "present")
            VehicleData.update { it.copy(rpm = Timed(d.rpmE3 / 1000)) }
        }
        batch.compassDataList.lastOrNull()?.let { d ->
            first("compass", "present")
            VehicleData.update { s -> s.copy(motion = Timed((s.motion?.value ?: Motion()).copy(compassBearing = d.bearingE6 / 1e6))) }
        }
        batch.accelerometerDataList.lastOrNull()?.let { d ->
            first("accelerometer", "present")
            val a = Triple(d.accelerationXE3 / 1000.0, d.accelerationYE3 / 1000.0, d.accelerationZE3 / 1000.0)
            VehicleData.update { s -> s.copy(motion = Timed((s.motion?.value ?: Motion()).copy(acceleration = a))) }
        }
        batch.gyroscopeDataList.lastOrNull()?.let { d ->
            first("gyroscope", "present")
            val r = Triple(d.rotationSpeedXE3 / 1000.0, d.rotationSpeedYE3 / 1000.0, d.rotationSpeedZE3 / 1000.0)
            VehicleData.update { s -> s.copy(motion = Timed((s.motion?.value ?: Motion()).copy(rotation = r))) }
        }
        batch.deadReckoningDataList.lastOrNull()?.let { d ->
            first("dead reckoning", "${d.wheelSpeedE3Count} wheels")
            if (d.hasSteeringAngleE1()) {
                VehicleData.update { s -> s.copy(motion = Timed((s.motion?.value ?: Motion()).copy(steeringAngle = d.steeringAngleE1 / 10.0))) }
            }
        }
        batch.passengerDataList.lastOrNull()?.takeIf { it.hasPassengerPresent() }?.let { d ->
            first("passenger", d.passengerPresent.toString())
            VehicleData.update { it.copy(passengerPresent = Timed(d.passengerPresent)) }
        }
        batch.tollCardDataList.lastOrNull()?.let { d ->
            first("toll card", d.isCardPresent.toString())
            VehicleData.update { it.copy(tollCardPresent = Timed(d.isCardPresent)) }
        }
    }

    private fun first(kind: String, detail: String) {
        if (logged.add(kind)) EventLog.log("Sensors: first $kind sample ($detail)")
    }

    private companion object {
        val Continuous = setOf(
            SensorType.SENSOR_LOCATION, SensorType.SENSOR_SPEED, SensorType.SENSOR_RPM, SensorType.SENSOR_COMPASS,
            SensorType.SENSOR_ACCELEROMETER_DATA, SensorType.SENSOR_GYROSCOPE_DATA, SensorType.SENSOR_DEAD_RECKONING_DATA,
            SensorType.SENSOR_GPS_SATELLITE_DATA,
        )
    }
}
