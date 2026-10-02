import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import {
  CreditApplication,
  CreditApplicationRequest,
  Customer,
  ProcessOutcome,
} from './credit.models';

export const REPLAYED_HEADER = 'Idempotent-Replayed';

@Injectable({ providedIn: 'root' })
export class CreditApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api';

  process(request: CreditApplicationRequest): Observable<ProcessOutcome> {
    return this.http
      .post<CreditApplication>(`${this.baseUrl}/credit-applications`, request, { observe: 'response' })
      .pipe(
        map((response) => ({
          application: response.body as CreditApplication,
          replayed: response.headers.get(REPLAYED_HEADER) === 'true',
        })),
      );
  }

  findByReference(applicationReference: string): Observable<CreditApplication> {
    return this.http.get<CreditApplication>(
      `${this.baseUrl}/credit-applications/${encodeURIComponent(applicationReference)}`,
    );
  }

  findRecent(limit = 20): Observable<CreditApplication[]> {
    return this.http.get<CreditApplication[]>(`${this.baseUrl}/credit-applications`, {
      params: { limit },
    });
  }

  findCustomers(): Observable<Customer[]> {
    return this.http.get<Customer[]>(`${this.baseUrl}/customers`);
  }
}
