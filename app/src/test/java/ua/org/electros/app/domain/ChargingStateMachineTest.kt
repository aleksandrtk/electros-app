package ua.org.electros.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.domain.statemachine.ChargingStateMachine
import ua.org.electros.app.domain.statemachine.StateEvent

class ChargingStateMachineTest {

    @Test
    fun stateMachine_happyPathToTargetReached() {
        val fsm = ChargingStateMachine(SessionStatus.IDLE)
        assertThat(fsm.getState()).isEqualTo(SessionStatus.IDLE)

        fsm.transition(StateEvent.StartTriggered(targetKwh = 30.0, amperage = 16))
        assertThat(fsm.getState()).isEqualTo(SessionStatus.CHARGING)

        fsm.transition(StateEvent.TargetQuotaReached)
        assertThat(fsm.getState()).isEqualTo(SessionStatus.TARGET_REACHED)
    }

    @Test
    fun stateMachine_outageRecoveryWithVoltageStabilizationGate() {
        val fsm = ChargingStateMachine(SessionStatus.CHARGING)

        // Grid drops
        fsm.transition(StateEvent.OutageDetected)
        assertThat(fsm.getState()).isEqualTo(SessionStatus.OUTAGE_DETECTED)

        // Grid returns with unstable voltage (e.g. 260V)
        fsm.transition(StateEvent.GridRestored(voltage = 260))
        assertThat(fsm.getState()).isEqualTo(SessionStatus.OUTAGE_DETECTED) // must reject

        // Grid returns with stable voltage (225V) -> enters STABILIZING
        fsm.transition(StateEvent.GridRestored(voltage = 225))
        assertThat(fsm.getState()).isEqualTo(SessionStatus.OUTAGE_STABILIZING)

        // Tick 1 stable
        fsm.transition(StateEvent.VoltageStable)
        assertThat(fsm.getState()).isEqualTo(SessionStatus.OUTAGE_STABILIZING)

        // Tick 2 stable -> triggers AUTO_RESUMING on 3rd tick
        fsm.transition(StateEvent.VoltageStable)
        assertThat(fsm.getState()).isEqualTo(SessionStatus.AUTO_RESUMING)

        // Resume command sent -> transitions back to CHARGING
        fsm.transition(StateEvent.ResumeCommandSent)
        assertThat(fsm.getState()).isEqualTo(SessionStatus.CHARGING)
    }

    @Test
    fun stateMachine_vehicleStopsCharging() {
        val fsm = ChargingStateMachine(SessionStatus.CHARGING)
        fsm.transition(StateEvent.VehicleStoppedCharging)
        assertThat(fsm.getState()).isEqualTo(SessionStatus.VEHICLE_STOPPED)
    }
}

