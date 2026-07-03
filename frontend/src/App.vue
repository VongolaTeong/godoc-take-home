<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import * as api from './api'
import { ApiError, type BookingAction } from './api'
import type { BookingView, Doctor, Patient, Slot } from './types'

const patients = ref<Patient[]>([])
const doctors = ref<Doctor[]>([])
const slots = ref<Slot[]>([])
const bookings = ref<BookingView[]>([])

const selectedPatientId = ref('')
const selectedDoctorId = ref('')
const bookingInFlightSlotId = ref<string | null>(null)
const actionInFlightBookingId = ref<string | null>(null)

interface Toast {
  kind: 'success' | 'error'
  text: string
}
const toast = ref<Toast | null>(null)
let toastTimer: ReturnType<typeof setTimeout> | undefined

function showToast(kind: Toast['kind'], text: string) {
  toast.value = { kind, text }
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = null), 5000)
}

function errorText(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong — is the API running?'
}

onMounted(async () => {
  try {
    const [patientList, doctorList] = await Promise.all([api.listPatients(), api.listDoctors()])
    patients.value = patientList
    doctors.value = doctorList
    if (patientList.length > 0) selectedPatientId.value = patientList[0]!.id
    if (doctorList.length > 0) selectedDoctorId.value = doctorList[0]!.id
  } catch (error) {
    showToast('error', errorText(error))
  }
})

watch(selectedDoctorId, () => void refreshSlots())
watch(selectedPatientId, () => void refreshBookings())

async function refreshSlots() {
  if (!selectedDoctorId.value) return
  try {
    slots.value = await api.listAvailableSlots(selectedDoctorId.value)
  } catch (error) {
    showToast('error', errorText(error))
  }
}

async function refreshBookings() {
  if (!selectedPatientId.value) return
  try {
    bookings.value = await api.listMyBookings(selectedPatientId.value)
  } catch (error) {
    showToast('error', errorText(error))
  }
}

async function book(slot: Slot) {
  if (!selectedPatientId.value || bookingInFlightSlotId.value) return
  bookingInFlightSlotId.value = slot.id
  try {
    await api.bookSlot(selectedPatientId.value, slot.id)
    showToast('success', `Booked ${formatDay(slot.startAt)} ${formatTime(slot.startAt)} — pending confirmation`)
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      // The interesting case: someone else won the race for this slot.
      showToast('error', 'That slot was just taken by someone else — availability refreshed')
    } else {
      showToast('error', errorText(error))
    }
  } finally {
    bookingInFlightSlotId.value = null
    await Promise.all([refreshSlots(), refreshBookings()])
  }
}

const pastTense: Record<BookingAction, string> = {
  confirm: 'confirmed',
  cancel: 'cancelled',
  complete: 'completed',
}

async function act(booking: BookingView, action: BookingAction) {
  if (actionInFlightBookingId.value) return
  actionInFlightBookingId.value = booking.id
  try {
    await api.transitionBooking(selectedPatientId.value, booking.id, action)
    showToast('success', `Booking ${pastTense[action]}`)
  } catch (error) {
    showToast('error', errorText(error))
  } finally {
    actionInFlightBookingId.value = null
    // Cancelling frees the slot, so availability may change too.
    await Promise.all([refreshSlots(), refreshBookings()])
  }
}

const slotsByDay = computed<[string, Slot[]][]>(() => {
  const groups = new Map<string, Slot[]>()
  for (const slot of slots.value) {
    const day = formatDay(slot.startAt)
    const group = groups.get(day)
    if (group) {
      group.push(slot)
    } else {
      groups.set(day, [slot])
    }
  }
  return [...groups.entries()]
})

const selectedDoctor = computed(() => doctors.value.find((d) => d.id === selectedDoctorId.value))

interface ActionButton {
  action: BookingAction
  label: string
}

function actionsFor(booking: BookingView): ActionButton[] {
  switch (booking.status) {
    case 'PENDING':
      return [
        { action: 'confirm', label: 'Confirm' },
        { action: 'cancel', label: 'Cancel' },
      ]
    case 'CONFIRMED':
      return [
        { action: 'complete', label: 'Complete' },
        { action: 'cancel', label: 'Cancel' },
      ]
    default:
      return []
  }
}

function formatDay(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <header class="topbar">
    <div class="topbar-inner">
      <div class="brand">
        <span class="brand-mark">+</span>
        <div>
          <h1>GoDoc Consult</h1>
          <p>Consultation booking — take-home demo</p>
        </div>
      </div>
      <label class="patient-picker">
        Acting as
        <select v-model="selectedPatientId">
          <option v-for="patient in patients" :key="patient.id" :value="patient.id">
            {{ patient.name }}
          </option>
        </select>
      </label>
    </div>
  </header>

  <main>
    <section class="panel">
      <h2>1 · Choose a doctor</h2>
      <div class="doctor-list">
        <button
          v-for="doctor in doctors"
          :key="doctor.id"
          class="doctor-card"
          :class="{ selected: doctor.id === selectedDoctorId }"
          @click="selectedDoctorId = doctor.id"
        >
          <strong>{{ doctor.name }}</strong>
          <span>{{ doctor.specialty }}</span>
        </button>
      </div>
    </section>

    <section class="panel">
      <h2>
        2 · Pick a free slot
        <span v-if="selectedDoctor" class="muted">— {{ selectedDoctor.name }}, next 7 days ({{ slots.length }} available)</span>
      </h2>
      <p class="muted small">Times shown in your local timezone. Slots disappear the moment anyone books them.</p>
      <div v-if="slotsByDay.length" class="day-grid">
        <div v-for="[day, daySlots] in slotsByDay" :key="day" class="day-column">
          <h3>{{ day }}</h3>
          <button
            v-for="slot in daySlots"
            :key="slot.id"
            class="slot-button"
            :disabled="bookingInFlightSlotId !== null"
            @click="book(slot)"
          >
            {{ formatTime(slot.startAt) }}
          </button>
        </div>
      </div>
      <p v-else class="muted">No free slots in the next 7 days.</p>
    </section>

    <section class="panel">
      <h2>3 · My bookings</h2>
      <table v-if="bookings.length" class="bookings-table">
        <thead>
          <tr>
            <th>When</th>
            <th>Doctor</th>
            <th>Status</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="booking in bookings" :key="booking.id">
            <td>{{ formatDay(booking.startAt) }} · {{ formatTime(booking.startAt) }}–{{ formatTime(booking.endAt) }}</td>
            <td>{{ booking.doctorName }}</td>
            <td><span class="chip" :class="booking.status.toLowerCase()">{{ booking.status }}</span></td>
            <td class="actions">
              <button
                v-for="button in actionsFor(booking)"
                :key="button.action"
                class="action-button"
                :class="button.action"
                :disabled="actionInFlightBookingId !== null"
                :title="button.action === 'complete' ? 'Clinic action — shown here for demo purposes' : undefined"
                @click="act(booking, button.action)"
              >
                {{ button.label }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">No bookings yet — pick a slot above.</p>
    </section>
  </main>

  <transition name="toast">
    <div v-if="toast" class="toast" :class="toast.kind" role="status">{{ toast.text }}</div>
  </transition>
</template>

<style scoped>
.topbar {
  background: #fff;
  border-bottom: 1px solid var(--border);
  position: sticky;
  top: 0;
  z-index: 10;
}

.topbar-inner {
  max-width: 1080px;
  margin: 0 auto;
  padding: 0.75rem 1.25rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.brand {
  display: flex;
  align-items: center;
  gap: 0.7rem;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 2.4rem;
  height: 2.4rem;
  border-radius: 0.7rem;
  background: var(--accent);
  color: #fff;
  font-size: 1.5rem;
  font-weight: 700;
}

.brand h1 {
  font-size: 1.05rem;
  margin: 0;
}

.brand p {
  margin: 0;
  font-size: 0.78rem;
  color: var(--muted);
}

.patient-picker {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
  color: var(--muted);
}

.patient-picker select {
  font: inherit;
  padding: 0.4rem 0.6rem;
  border: 1px solid var(--border);
  border-radius: 0.5rem;
  background: #fff;
  color: var(--text);
}

main {
  max-width: 1080px;
  margin: 1.25rem auto 4rem;
  padding: 0 1.25rem;
  display: grid;
  gap: 1.25rem;
}

.panel {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 0.9rem;
  padding: 1.1rem 1.25rem 1.25rem;
}

.panel h2 {
  font-size: 0.95rem;
  margin: 0 0 0.75rem;
}

.muted {
  color: var(--muted);
  font-weight: 400;
}

.small {
  font-size: 0.8rem;
  margin: -0.35rem 0 0.75rem;
}

.doctor-list {
  display: flex;
  flex-wrap: wrap;
  gap: 0.6rem;
}

.doctor-card {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  align-items: flex-start;
  padding: 0.6rem 0.9rem;
  border: 1px solid var(--border);
  border-radius: 0.7rem;
  background: #fff;
  cursor: pointer;
  font: inherit;
  color: var(--text);
}

.doctor-card span {
  font-size: 0.78rem;
  color: var(--muted);
}

.doctor-card.selected {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.day-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 0.9rem;
}

.day-column {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.day-column h3 {
  font-size: 0.78rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--muted);
  margin: 0 0 0.2rem;
}

.slot-button {
  font: inherit;
  font-size: 0.85rem;
  padding: 0.45rem 0.5rem;
  border: 1px solid var(--accent-border);
  border-radius: 0.5rem;
  background: var(--accent-soft);
  color: var(--accent-dark);
  cursor: pointer;
}

.slot-button:hover:not(:disabled) {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.slot-button:disabled {
  opacity: 0.55;
  cursor: wait;
}

.bookings-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.88rem;
}

.bookings-table th {
  text-align: left;
  font-size: 0.74rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--muted);
  padding: 0.4rem 0.6rem;
  border-bottom: 1px solid var(--border);
}

.bookings-table td {
  padding: 0.55rem 0.6rem;
  border-bottom: 1px solid var(--border);
}

.chip {
  display: inline-block;
  padding: 0.15rem 0.55rem;
  border-radius: 999px;
  font-size: 0.74rem;
  font-weight: 600;
}

.chip.pending {
  background: #fef3c7;
  color: #92400e;
}

.chip.confirmed {
  background: #dcfce7;
  color: #166534;
}

.chip.cancelled {
  background: #f3f4f6;
  color: #4b5563;
}

.chip.completed {
  background: #dbeafe;
  color: #1e40af;
}

.actions {
  text-align: right;
  white-space: nowrap;
}

.action-button {
  font: inherit;
  font-size: 0.78rem;
  padding: 0.3rem 0.7rem;
  border-radius: 0.45rem;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--text);
  cursor: pointer;
  margin-left: 0.35rem;
}

.action-button.confirm {
  border-color: var(--accent);
  color: var(--accent-dark);
}

.action-button.confirm:hover:not(:disabled) {
  background: var(--accent);
  color: #fff;
}

.action-button.cancel:hover:not(:disabled) {
  border-color: #b91c1c;
  color: #b91c1c;
}

.action-button.complete:hover:not(:disabled) {
  border-color: #1d4ed8;
  color: #1d4ed8;
}

.action-button:disabled {
  opacity: 0.55;
  cursor: wait;
}

.toast {
  position: fixed;
  bottom: 1.5rem;
  left: 50%;
  transform: translateX(-50%);
  padding: 0.7rem 1.1rem;
  border-radius: 0.6rem;
  font-size: 0.88rem;
  color: #fff;
  box-shadow: 0 10px 25px rgb(0 0 0 / 0.18);
  max-width: min(90vw, 32rem);
  text-align: center;
}

.toast.success {
  background: #15803d;
}

.toast.error {
  background: #b91c1c;
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(0.5rem);
}
</style>
