import type { Booking, BookingView, Doctor, Patient, Slot } from './types'

/** Carries the RFC 7807 problem details the backend returns on every error. */
export class ApiError extends Error {
  readonly status: number
  readonly title: string

  constructor(status: number, title: string, detail: string) {
    super(detail)
    this.status = status
    this.title = title
  }
}

// Same-origin deployments (UI served from the Spring Boot jar) leave this unset.
// Cross-origin deployments (UI on Cloudflare Pages, API on Render) set
// VITE_API_BASE_URL at build time; the API must then allow the UI's origin via
// its app.cors.allowed-origins property.
const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '')

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(API_BASE + path, init)
  if (!response.ok) {
    let title = response.statusText
    let detail = 'Request failed'
    try {
      const problem = await response.json()
      title = problem.title ?? title
      detail = problem.detail ?? detail
    } catch {
      // non-JSON error body; keep the defaults
    }
    throw new ApiError(response.status, title, detail)
  }
  return response.json() as Promise<T>
}

function patientHeaders(patientId: string): Record<string, string> {
  // Auth stub: the backend identifies the caller by this header (see README).
  return { 'X-Patient-Id': patientId }
}

export function listPatients(): Promise<Patient[]> {
  return request('/api/v1/patients')
}

export function listDoctors(): Promise<Doctor[]> {
  return request('/api/v1/doctors')
}

export function listAvailableSlots(doctorId: string): Promise<Slot[]> {
  return request(`/api/v1/doctors/${doctorId}/slots`)
}

export function listMyBookings(patientId: string): Promise<BookingView[]> {
  return request('/api/v1/bookings', { headers: patientHeaders(patientId) })
}

export function bookSlot(patientId: string, slotId: string): Promise<Booking> {
  return request('/api/v1/bookings', {
    method: 'POST',
    headers: {
      ...patientHeaders(patientId),
      'Content-Type': 'application/json',
      // One key per click: retries of the same logical attempt would reuse it, which the
      // backend answers with the original booking instead of creating a duplicate.
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({ slotId }),
  })
}

export type BookingAction = 'confirm' | 'cancel' | 'complete'

export function transitionBooking(
  patientId: string,
  bookingId: string,
  action: BookingAction,
): Promise<Booking> {
  return request(`/api/v1/bookings/${bookingId}/${action}`, {
    method: 'POST',
    headers: patientHeaders(patientId),
  })
}
