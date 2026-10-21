package com.simoneluconi.betterauto.car

import com.simoneluconi.betterauto.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Whether the car stands still, as far as the car says. */
enum class CarMotion { STANDING, MOVING, UNKNOWN }

/**
 * Standing or moving, for what may only be done while the car stands (settings, editing the screen). It must
 * hold for automatic and manual cars and for head units that share nothing:
 * - a fresh speed above walking pace means moving, whatever else says;
 * - P (automatic), the parking brake (manual) or a speed of zero mean standing;
 * - otherwise the car's own driving restrictions (0 = none, what it reports while it stands);
 * - no data at all is UNKNOWN, which callers treat as standing: aftermarket head units keep everything.
 */
object CarMotionState {
    /** A speed older than this says nothing about now. */
    private const val SPEED_FRESH_MS = 15_000L
    private const val MOVING_KMH = 3.0
    private const val STANDING_KMH = 1.0

    fun of(s: VehicleSnapshot, now: Long = System.currentTimeMillis()): CarMotion {
        val kmh = s.speed?.takeIf { now - it.at < SPEED_FRESH_MS }?.value?.kmh
        if (kmh != null && kmh > MOVING_KMH) return CarMotion.MOVING
        if (s.gear?.value?.position == GearPosition.PARK) return CarMotion.STANDING
        if (s.parkingBrake?.value == true) return CarMotion.STANDING
        if (kmh != null && kmh <= STANDING_KMH) return CarMotion.STANDING
        s.drivingStatus?.let { return if (it.value == 0) CarMotion.STANDING else CarMotion.MOVING }
        return CarMotion.UNKNOWN
    }

    /** Development: pretend the car stands or moves (null: as the car says). */
    val pretend = MutableStateFlow<CarMotion?>(null)

    val state: StateFlow<CarMotion> = combine(VehicleData.snapshot, pretend) { snapshot, fake -> fake ?: of(snapshot) }
        .distinctUntilChanged()
        .onEach { EventLog.log("Car motion: ${it.name.lowercase()}") }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, CarMotion.UNKNOWN)

    /** True only when the car says it moves. */
    val moving: Boolean get() = state.value == CarMotion.MOVING
}
