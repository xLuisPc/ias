import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { ResultKind, ResultView } from '../../core/credit.models';
import { EvaluationRoute, stationFor } from '../evaluation-route/evaluation-route';

const NOTICES: Partial<Record<ResultKind, { tone: 'info' | 'warn'; text: string }>> = {
  replayed: {
    tone: 'info',
    text: 'Esta referencia ya se había procesado. Se muestra el resultado original y no se consumió cupo de nuevo.',
  },
  conflict: {
    tone: 'warn',
    text: 'La referencia ya existe con otros datos, así que no se volvió a procesar. Esta es la solicitud original.',
  },
};

@Component({
  selector: 'app-application-result',
  imports: [CurrencyPipe, DatePipe, EvaluationRoute],
  templateUrl: './application-result.html',
  styleUrl: './application-result.css',
})
export class ApplicationResult {
  readonly result = input.required<ResultView>();

  readonly notice = computed(() => NOTICES[this.result().kind] ?? null);
  readonly approved = computed(() => this.result().application.status === 'APPROVED');
  readonly stoppedAt = computed(() => stationFor(this.result().application.rejectionReason));
}
