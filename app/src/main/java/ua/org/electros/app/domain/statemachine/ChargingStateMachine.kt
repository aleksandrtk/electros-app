package ua.org.electros.app.domain.statemachine

import ua.org.electros.app.data.local.entity.SessionStatus

sealed class StateEvent {
    object OutageDetected : StateEvent()
    data class GridRestored(val voltage: Int) : StateEvent()
    object VoltageStable : StateEvent()
    object VoltageUnstable : StateEvent()
    object ResumeCommandSent : StateEvent()
    object StopPending : StateEvent()
    object TargetQuotaReached : StateEvent()
    object VehicleStoppedCharging : StateEvent()
    object CancelRequested : StateEvent()
}

class ChargingStateMachine(
    private var currentStatus: SessionStatus = SessionStatus.IDLE
) {

    private var stableVoltageTicksCount: Int = 0

    fun getState(): SessionStatus = currentStatus

    fun transition(event: StateEvent): SessionStatus {
        currentStatus = when (currentStatus) {
            SessionStatus.IDLE -> currentStatus

            SessionStatus.CHARGING -> when (event) {
                is StateEvent.OutageDetected -> {
                    stableVoltageTicksCount = 0
                    SessionStatus.OUTAGE_DETECTED
                }
                is StateEvent.StopPending -> SessionStatus.STOP_PENDING
                is StateEvent.TargetQuotaReached -> SessionStatus.TARGET_REACHED
                is StateEvent.VehicleStoppedCharging -> SessionStatus.VEHICLE_STOPPED
                is StateEvent.CancelRequested -> SessionStatus.CANCELLED
                else -> currentStatus
            }

            SessionStatus.OUTAGE_DETECTED -> when (event) {
                is StateEvent.GridRestored -> {
                    if (event.voltage in 180..250) {
                        stableVoltageTicksCount = 1
                        SessionStatus.OUTAGE_STABILIZING
                    } else {
                        currentStatus
                    }
                }
                is StateEvent.ResumeCommandSent -> {
                    stableVoltageTicksCount = 0
                    SessionStatus.CHARGING
                }
                is StateEvent.StopPending -> SessionStatus.STOP_PENDING
                is StateEvent.VehicleStoppedCharging -> SessionStatus.VEHICLE_STOPPED
                is StateEvent.CancelRequested -> SessionStatus.CANCELLED
                else -> currentStatus
            }

            SessionStatus.OUTAGE_STABILIZING -> when (event) {
                is StateEvent.VoltageStable -> {
                    stableVoltageTicksCount++
                    if (stableVoltageTicksCount >= 3) {
                        SessionStatus.AUTO_RESUMING
                    } else {
                        currentStatus
                    }
                }
                is StateEvent.VoltageUnstable -> {
                    stableVoltageTicksCount = 0
                    SessionStatus.OUTAGE_DETECTED
                }
                is StateEvent.OutageDetected -> {
                    stableVoltageTicksCount = 0
                    SessionStatus.OUTAGE_DETECTED
                }
                is StateEvent.ResumeCommandSent -> {
                    stableVoltageTicksCount = 0
                    SessionStatus.CHARGING
                }
                is StateEvent.StopPending -> SessionStatus.STOP_PENDING
                is StateEvent.VehicleStoppedCharging -> SessionStatus.VEHICLE_STOPPED
                is StateEvent.CancelRequested -> SessionStatus.CANCELLED
                else -> currentStatus
            }

            SessionStatus.AUTO_RESUMING -> when (event) {
                is StateEvent.ResumeCommandSent -> SessionStatus.CHARGING
                is StateEvent.OutageDetected -> {
                    stableVoltageTicksCount = 0
                    SessionStatus.OUTAGE_DETECTED
                }
                is StateEvent.StopPending -> SessionStatus.STOP_PENDING
                is StateEvent.VehicleStoppedCharging -> SessionStatus.VEHICLE_STOPPED
                is StateEvent.CancelRequested -> SessionStatus.CANCELLED
                else -> currentStatus
            }

            SessionStatus.STOP_PENDING -> when (event) {
                is StateEvent.TargetQuotaReached -> SessionStatus.TARGET_REACHED
                is StateEvent.VehicleStoppedCharging -> SessionStatus.VEHICLE_STOPPED
                is StateEvent.CancelRequested -> SessionStatus.CANCELLED
                else -> currentStatus
            }

            SessionStatus.TARGET_REACHED,
            SessionStatus.VEHICLE_STOPPED,
            SessionStatus.CANCELLED -> currentStatus
        }
        return currentStatus
    }
}

