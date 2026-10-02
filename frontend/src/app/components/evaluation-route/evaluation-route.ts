import { Component, computed, input } from '@angular/core';
import { CreditApplication } from '../../core/credit.models';

/** Estaciones en el mismo orden en que el backend (CreditPolicy) evalúa las reglas. */
export const STATIONS = [
  { reason: 'INVALID_AMOUNT', name: 'Monto', rule: 'Mayor que cero' },
  { reason: 'INVALID_TERM', name: 'Plazo', rule: 'De 6 a 60 meses' },
  { reason: 'CUSTOMER_NOT_FOUND', name: 'Cliente', rule: 'Registrado' },
  { reason: 'CUSTOMER_NOT_ELIGIBLE', name: 'Estado', rule: 'Habilitado' },
  { reason: 'CREDIT_LIMIT_EXCEEDED', name: 'Cupo', rule: 'Disponible' },
] as const;

export type StationState = 'idle' | 'passed' | 'stopped' | 'skipped';

/** Nombre de la estación donde se detuvo una solicitud rechazada. */
export function stationFor(reason: string | null): string | null {
  return STATIONS.find((s) => s.reason === reason)?.name ?? null;
}

const STATE_TEXT: Record<StationState, string> = {
  idle: 'sin evaluar',
  passed: 'superada',
  stopped: 'detenida aquí',
  skipped: 'no evaluada',
};

/**
 * Línea de evaluación: muestra en qué estación terminó la solicitud.
 * Sin solicitud se muestra en reposo, como guía de las reglas.
 */
@Component({
  selector: 'app-evaluation-route',
  templateUrl: './evaluation-route.html',
  styleUrl: './evaluation-route.css',
})
export class EvaluationRoute {
  readonly application = input<CreditApplication | null>(null);

  /** Posición de la parada: 0-4 estación de rechazo, 5 terminal (aprobada), -1 en reposo. */
  readonly stop = computed(() => {
    const app = this.application();
    if (!app) return -1;
    if (app.status === 'APPROVED') return STATIONS.length;
    const index = STATIONS.findIndex((s) => s.reason === app.rejectionReason);
    return index === -1 ? 0 : index;
  });

  /** Fracción de la línea recorrida (0 a 1), usada por la animación de llenado. */
  readonly progress = computed(() => {
    const stop = this.stop();
    return stop < 0 ? 0 : stop / STATIONS.length;
  });

  readonly approved = computed(() => this.stop() === STATIONS.length);

  readonly stations = computed(() =>
    STATIONS.map((station, index) => {
      const state = this.stateOf(index);
      return { ...station, state, stateText: STATE_TEXT[state] };
    }),
  );

  private stateOf(index: number): StationState {
    const stop = this.stop();
    if (stop < 0) return 'idle';
    if (index < stop) return 'passed';
    if (index === stop) return 'stopped';
    return 'skipped';
  }
}
