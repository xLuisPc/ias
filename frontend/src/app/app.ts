import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApplicationForm } from './components/application-form/application-form';
import { ApplicationResult } from './components/application-result/application-result';
import { RecentApplications } from './components/recent-applications/recent-applications';
import { CreditApiService } from './core/credit-api.service';
import {
  CreditApplication,
  CreditApplicationRequest,
  Customer,
  ProblemDetail,
  ResultView,
} from './core/credit.models';

/**
 * Componente contenedor: maneja el estado (signals) y la comunicacion con el backend.
 * Los componentes hijos son presentacionales (reciben datos por input y emiten eventos).
 */
@Component({
  selector: 'app-root',
  imports: [FormsModule, ApplicationForm, ApplicationResult, RecentApplications],
  templateUrl: './app.html',
})
export class App implements OnInit {
  private readonly api = inject(CreditApiService);

  readonly customers = signal<Customer[]>([]);
  readonly recent = signal<CreditApplication[]>([]);
  readonly result = signal<ResultView | null>(null);
  readonly error = signal<string | null>(null);
  readonly submitting = signal(false);
  searchReference = '';

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.api.findCustomers().subscribe({
      next: (customers) => this.customers.set(customers),
      error: (err) => this.showError(err),
    });
    this.api.findRecent().subscribe({
      next: (applications) => this.recent.set(applications),
      error: (err) => this.showError(err),
    });
  }

  submit(request: CreditApplicationRequest): void {
    this.submitting.set(true);
    this.error.set(null);
    this.api.process(request).subscribe({
      next: ({ application, replayed }) => {
        this.result.set({ kind: replayed ? 'replayed' : 'created', application });
        this.submitting.set(false);
        this.reload();
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        const problem = err.error as ProblemDetail | null;
        if (err.status === 409 && problem?.originalApplication) {
          this.result.set({ kind: 'conflict', application: problem.originalApplication });
          return;
        }
        this.result.set(null);
        this.showError(err);
      },
    });
  }

  search(): void {
    const reference = this.searchReference.trim();
    if (!reference) {
      return;
    }
    this.error.set(null);
    this.api.findByReference(reference).subscribe({
      next: (application) => this.result.set({ kind: 'found', application }),
      error: (err) => {
        this.result.set(null);
        this.showError(err);
      },
    });
  }

  show(application: CreditApplication): void {
    this.error.set(null);
    this.result.set({ kind: 'found', application });
  }

  private showError(err: HttpErrorResponse): void {
    if (err.status === 0) {
      this.error.set('No fue posible conectar con el backend. Verifica que este ejecutandose en el puerto 8080.');
      return;
    }
    const problem = err.error as ProblemDetail | null;
    const fieldErrors = problem?.errors
      ? ' ' + Object.entries(problem.errors).map(([field, msg]) => `${field}: ${msg}`).join('; ')
      : '';
    this.error.set(`${problem?.title ?? 'Error'}: ${problem?.detail ?? err.message}${fieldErrors}`);
  }
}
