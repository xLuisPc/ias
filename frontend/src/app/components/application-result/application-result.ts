import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { ResultKind, ResultView } from '../../core/credit.models';

const NOTICES: Record<ResultKind, string> = {
  created: 'Solicitud procesada.',
  replayed:
    'Esta referencia ya habia sido procesada: se muestra el resultado original y no se consumio cupo nuevamente.',
  conflict:
    'La referencia ya existe con datos diferentes. No se proceso de nuevo; se conserva la solicitud original:',
  found: 'Resultado de la consulta.',
};

@Component({
  selector: 'app-application-result',
  imports: [CurrencyPipe, DatePipe],
  templateUrl: './application-result.html',
})
export class ApplicationResult {
  readonly result = input.required<ResultView>();

  readonly notice = computed(() => NOTICES[this.result().kind]);
  readonly approved = computed(() => this.result().application.status === 'APPROVED');
}
