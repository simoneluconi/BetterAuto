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
import com.simoneluconi.betterauto.car.FaultCodes
import com.simoneluconi.betterauto.car.FieldValue
import com.simoneluconi.betterauto.car.Gear
import com.simoneluconi.betterauto.car.GearPosition
import com.simoneluconi.betterauto.car.HeadLights
import com.simoneluconi.betterauto.car.Position
import com.simoneluconi.betterauto.car.Satellites
import com.simoneluconi.betterauto.car.TurnSignal
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.car.VehicleField
import com.simoneluconi.betterauto.car.VehicleSource

/**
 * Car sensors: everything the car offers is requested. Night mode drives the theme; the rest feeds
 * [VehicleData] as the [VehicleSource.CAR] source, in canonical fields. The first sample of each kind is logged, without coordinates or other personal data,
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
        // Everything else becomes canonical vehicle fields, published once per batch
        val out = ArrayList<FieldValue<*>>()
        batch.fuelDataList.lastOrNull()?.let { d ->
            first("fuel", "level ${d.fuelLevel}%, range ${d.range} km, low ${d.lowFuelWarning}")
            out += VehicleField.FuelLevel of d.fuelLevel.takeIf { d.hasFuelLevel() }
            out += VehicleField.FuelRange of d.range.takeIf { d.hasRange() }
            out += VehicleField.FuelLow of d.lowFuelWarning.takeIf { d.hasLowFuelWarning() }
        }
        batch.odometerDataList.lastOrNull()?.let { d ->
            first("odometer", "present")
            out += VehicleField.Odometer of d.kmsE1 / 10.0
            out += VehicleField.Trip of (if (d.hasTripKmsE1()) d.tripKmsE1 / 10.0 else null)
        }
        batch.tirePressureDataList.lastOrNull()?.let { d ->
            first("tires", "${d.tirePressuresE2Count} values")
            // kPa x100 to bar
            out += VehicleField.TirePressures of d.tirePressuresE2List.map { p -> p / 100.0 / 100.0 }.takeIf { it.isNotEmpty() }
        }
        batch.environmentDataList.lastOrNull()?.let { d ->
            first("environment", "temperature ${d.hasTemperatureE3()}, pressure ${d.hasPressureE3()}, rain ${d.hasRain()}")
            out += VehicleField.OutsideTemperature of (if (d.hasTemperatureE3()) d.temperatureE3 / 1000.0 else null)
            out += VehicleField.AirPressure of (if (d.hasPressureE3()) d.pressureE3 / 1000.0 else null)
            out += VehicleField.Rain of (if (d.hasRain()) d.rain else null)
        }
        batch.hvacDataList.lastOrNull()?.let { d ->
            first("hvac", "present")
            out += VehicleField.ClimateTarget of (if (d.hasTargetTemperatureE3()) d.targetTemperatureE3 / 1000.0 else null)
            out += VehicleField.CabinTemperature of (if (d.hasCurrentTemperatureE3()) d.currentTemperatureE3 / 1000.0 else null)
        }
        batch.doorDataList.lastOrNull()?.let { d ->
            first("doors", "${d.doorOpenCount} doors")
            out += VehicleField.HoodOpen of d.hoodOpen.takeIf { d.hasHoodOpen() }
            out += VehicleField.TrunkOpen of d.trunkOpen.takeIf { d.hasTrunkOpen() }
            out += VehicleField.DoorsOpen of d.doorOpenList.toList()
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
            out += VehicleField.Headlights of head
            out += VehicleField.Turn of turn
            out += VehicleField.Hazard of d.hazardLightsOn.takeIf { d.hasHazardLightsOn() }
        }
        batch.diagnosticsDataList.lastOrNull()?.let { d ->
            val codes = FaultCodes.parse(d.dtc.toByteArray())
            val previous = VehicleData.faultCodes
            if (codes != previous) EventLog.log("Diagnostics: ${codes.size} fault codes ${codes.joinToString()} (${d.dtc.size()} bytes)")
            out += VehicleField.FaultCodes of codes
        }
        batch.locationDataList.lastOrNull()?.takeIf { it.latitudeE7 != 0 || it.longitudeE7 != 0 }?.let { d ->
            first("location", "accuracy ${if (d.hasAccuracyE3()) d.accuracyE3 / 1000 else "?"} m")
            out += VehicleField.Location of Position(
                latitude = d.latitudeE7 / 1e7,
                longitude = d.longitudeE7 / 1e7,
                accuracyM = if (d.hasAccuracyE3()) d.accuracyE3 / 1000.0 else null,
                altitudeM = if (d.hasAltitudeE2()) d.altitudeE2 / 100.0 else null,
                speedKmh = if (d.hasSpeedE3()) d.speedE3 / 1000.0 * 3.6 else null,
                bearing = if (d.hasBearingE6()) d.bearingE6 / 1e6 else null,
            )
        }
        batch.gpsSatelliteDataList.lastOrNull()?.let { d ->
            first("satellites", "${d.numberInUse} in use")
            out += VehicleField.GpsSatellites of Satellites(d.numberInUse, if (d.hasNumberInView()) d.numberInView else null)
        }
        batch.gearDataList.lastOrNull()?.let { d ->
            first("gear", d.gear.name)
            out += VehicleField.CurrentGear of when (d.gear) {
                GearOuterClass.Gear.GEAR_PARK -> Gear(GearPosition.PARK, null)
                GearOuterClass.Gear.GEAR_REVERSE -> Gear(GearPosition.REVERSE, null)
                GearOuterClass.Gear.GEAR_NEUTRAL -> Gear(GearPosition.NEUTRAL, null)
                GearOuterClass.Gear.GEAR_DRIVE -> Gear(GearPosition.DRIVE, null)
                else -> Gear(GearPosition.MANUAL, d.gear.number)
            }
        }
        batch.parkingBrakeDataList.lastOrNull()?.let { d ->
            first("parking brake", d.parkingBrake.toString())
            out += VehicleField.ParkingBrake of d.parkingBrake
        }
        batch.drivingStatusDataList.lastOrNull()?.let { d ->
            first("driving status", d.status.toString())
            if (VehicleData.snapshot.value.drivingStatus?.value != d.status) EventLog.log("Driving status: ${d.status}")
            out += VehicleField.DrivingStatus of d.status
        }
        batch.speedDataList.lastOrNull()?.let { d ->
            first("speed", "present, cruise ${d.hasCruiseEngaged()}")
            out += VehicleField.Speed of d.speedE3 / 1000.0 * 3.6
            out += VehicleField.CruiseOn of d.cruiseEngaged.takeIf { d.hasCruiseEngaged() }
            out += VehicleField.CruiseSet of d.cruiseSetSpeed.takeIf { d.hasCruiseSetSpeed() }
        }
        batch.rpmDataList.lastOrNull()?.let { d ->
            first("rpm", "present")
            out += VehicleField.Rpm of d.rpmE3 / 1000
        }
        batch.compassDataList.lastOrNull()?.let { d ->
            first("compass", "present")
            out += VehicleField.Compass of d.bearingE6 / 1e6
        }
        batch.accelerometerDataList.lastOrNull()?.let { d ->
            first("accelerometer", "present")
            out += VehicleField.Acceleration of Triple(d.accelerationXE3 / 1000.0, d.accelerationYE3 / 1000.0, d.accelerationZE3 / 1000.0)
        }
        batch.gyroscopeDataList.lastOrNull()?.let { d ->
            first("gyroscope", "present")
            out += VehicleField.Rotation of Triple(d.rotationSpeedXE3 / 1000.0, d.rotationSpeedYE3 / 1000.0, d.rotationSpeedZE3 / 1000.0)
        }
        batch.deadReckoningDataList.lastOrNull()?.let { d ->
            first("dead reckoning", "${d.wheelSpeedE3Count} wheels")
            if (d.hasSteeringAngleE1()) out += VehicleField.SteeringAngle of d.steeringAngleE1 / 10.0
        }
        batch.passengerDataList.lastOrNull()?.takeIf { it.hasPassengerPresent() }?.let { d ->
            first("passenger", d.passengerPresent.toString())
            out += VehicleField.PassengerPresent of d.passengerPresent
        }
        batch.tollCardDataList.lastOrNull()?.let { d ->
            first("toll card", d.isCardPresent.toString())
            out += VehicleField.TollCard of d.isCardPresent
        }
        if (out.isNotEmpty()) VehicleData.publish(VehicleSource.CAR, out)
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
