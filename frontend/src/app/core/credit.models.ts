/** Contratos de la API del backend (credit-core). */

export type ApplicationStatus = 'APPROVED' | 'REJECTED';

export interface CreditApplicationRequest {
  applicationReference: string;
  customerId: string;
  amount: number;
  termMonths: number;
}

export interface CreditApplication {
  applicationReference: string;
  customerId: string;
  amount: number;
  termMonths: number;
  status: ApplicationStatus;
  rejectionReason: string | null;
  message: string;
  processedAt: string;
}

export interface Customer {
  id: string;
  status: 'ELIGIBLE' | 'BLOCKED';
  creditLimit: number;
  approvedAmount: number;
  availableAmount: number;
}

/** Resultado de enviar una solicitud: `replayed` indica que la referencia ya habia sido procesada. */
export interface ProcessOutcome {
  application: CreditApplication;
  replayed: boolean;
}

/** Cuerpo de error (RFC 9457 ProblemDetail) que devuelve el backend. */
export interface ProblemDetail {
  title?: string;
  detail?: string;
  status?: number;
  errors?: Record<string, string>;
  originalApplication?: CreditApplication;
}

/** Lo que se muestra en la tarjeta de resultado. */
export type ResultKind = 'created' | 'replayed' | 'conflict' | 'found';

export interface ResultView {
  kind: ResultKind;
  application: CreditApplication;
}
