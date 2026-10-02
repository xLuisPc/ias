import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { CreditApplication } from './core/credit.models';

const ORIGINAL: CreditApplication = {
  applicationReference: 'REF-001',
  customerId: 'CLI-1001',
  amount: 6000000,
  termMonths: 24,
  status: 'APPROVED',
  rejectionReason: null,
  message: 'Solicitud aprobada',
  processedAt: '2026-01-15T10:00:00Z',
};

describe('App', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function flushInitialLoad(): void {
    http.match('/api/customers').forEach((req) => req.flush([]));
    http.match((r) => r.url === '/api/credit-applications' && r.method === 'GET').forEach((req) => req.flush([]));
  }

  it('shows the original application when the backend reports a reference conflict', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    flushInitialLoad();

    fixture.componentInstance.submit({
      applicationReference: 'REF-001',
      customerId: 'CLI-1001',
      amount: 1000000,
      termMonths: 12,
    });
    http
      .expectOne((r) => r.method === 'POST')
      .flush(
        { title: 'Referencia de solicitud en conflicto', status: 409, originalApplication: ORIGINAL },
        { status: 409, statusText: 'Conflict' },
      );
    await fixture.whenStable();

    expect(fixture.componentInstance.result()).toEqual({ kind: 'conflict', application: ORIGINAL });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('con otros datos');
  });

  it('shows a friendly message when the backend is not reachable', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    http.match('/api/customers').forEach((req) => req.error(new ProgressEvent('error'), { status: 0 }));
    http.match((r) => r.url === '/api/credit-applications').forEach((req) => req.flush([]));
    await fixture.whenStable();

    expect(fixture.componentInstance.error()).toContain('No hay conexión con el backend');
  });
});
