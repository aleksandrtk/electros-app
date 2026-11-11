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

    @Test
    fun stateMachine_outageRecoveryAccepts180VAndDirectResume() {
        val fsm = ChargingStateMachine(SessionStatus.CHARGING)
        fsm.transition(StateEvent.StartTriggered(targetKwh = 0.0, amperage = 12))
        assertThat(fsm.getState()).isEqualTo(SessionStatus.CHARGING)

        fsm.transition(StateEvent.OutageDetected)
        assertThat(fsm.getState()).isEqualTo(SessionStatus.OUTAGE_DETECTED)

        // Too low voltage must stay in OUTAGE_DETECTED
        fsm.transition(StateEvent.GridRestored(voltage = 175))
        assertThat(fsm.getState()).isEqualTo(SessionStatus.OUTAGE_DETECTED)

        // 180V should be accepted for Ukrainian low-voltage winter grids
        fsm.transition(StateEvent.GridRestored(voltage = 180))
        assertThat(fsm.getState()).isEqualTo(SessionStatus.OUTAGE_STABILIZING)

        // If hardware auto-resumed during stabilization, ResumeCommandSent restores CHARGING
        fsm.transition(StateEvent.ResumeCommandSent)
        assertThat(fsm.getState()).isEqualTo(SessionStatus.CHARGING)
    }

    @Test
    fun stateMachine_stopPendingTransitionsToTerminalOnlyWhenConfirmed() {
        val fsm = ChargingStateMachine(SessionStatus.CHARGING)

        fsm.transition(StateEvent.StopPending)
        assertThat(fsm.getState()).isEqualTo(SessionStatus.STOP_PENDING)

        // Confirmed target stop moves STOP_PENDING -> TARGET_REACHED
        fsm.transition(StateEvent.TargetQuotaReached)
        assertThat(fsm.getState()).isEqualTo(SessionStatus.TARGET_REACHED)

        // Manual cancel while stop pending moves STOP_PENDING -> CANCELLED
        val manualFsm = ChargingStateMachine(SessionStatus.CHARGING)
        manualFsm.transition(StateEvent.StopPending)
        assertThat(manualFsm.getState()).isEqualTo(SessionStatus.STOP_PENDING)
        manualFsm.transition(StateEvent.CancelRequested)
        assertThat(manualFsm.getState()).isEqualTo(SessionStatus.CANCELLED)

        // Stop pending from outage stays STOP_PENDING
        val outageFsm = ChargingStateMachine(SessionStatus.OUTAGE_DETECTED)
        outageFsm.transition(StateEvent.StopPending)
        assertThat(outageFsm.getState()).isEqualTo(SessionStatus.STOP_PENDING)
    }
}

