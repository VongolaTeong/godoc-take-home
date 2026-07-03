export interface Patient {
  id: string
  name: string
}

export interface Doctor {
  id: string
  name: string
  specialty: string
}

export interface Slot {
  id: string
  doctorId: string
  startAt: string
  endAt: string
}

export type BookingStatus = 'PENDING' | 'CONFIRMED' | 'CANCELLED' | 'COMPLETED'

export interface Booking {
  id: string
  slotId: string
  patientId: string
  status: BookingStatus
  createdAt: string
}

/** Booking joined with its slot and doctor, as returned by GET /api/v1/bookings. */
export interface BookingView {
  id: string
  status: BookingStatus
  slotId: string
  startAt: string
  endAt: string
  doctorId: string
  doctorName: string
  createdAt: string
}
