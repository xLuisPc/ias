import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CreditApiService, REPLAYED_HEADER } from './credit-api.service';
import { CreditApplication, ProcessOutcome } from './credit.models';

const APPLICATION: CreditApplication = {
  applicationReference: 'REF-001',
  customerId: 'CLI-1001',
  amount: 6000000,
  termMonths: 24,
  status: 'APPROVED',
  rejectionReason: null,
  message: 'Solicitud aprobada',
  processedAt: '2026-01-15T10:00:00Z',
};

describe('CreditApiService', () => {
  let service: CreditApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CreditApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('posts the request and marks a new application as not replayed', () => {
    let outcome: ProcessOutcome | undefined;
    service
      .process({ applicationReference: 'REF-001', customerId: 'CLI-1001', amount: 6000000, termMonths: 24 })
      .subscribe((value) => (outcome = value));

    const req = http.expectOne('/api/credit-applications');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.amount).toBe(6000000);
    req.flush(APPLICATION, { status: 201, statusText: 'Created' });

    expect(outcome).toEqual({ application: APPLICATION, replayed: false });
  });

  it('detects a replayed reference from the response header', () => {
    let outcome: ProcessOutcome | undefined;
    service
      .process({ applicationReference: 'REF-001', customerId: 'CLI-1001', amount: 6000000, termMonths: 24 })
      .subscribe((value) => (outcome = value));

    http
      .expectOne('/api/credit-applications')
      .flush(APPLICATION, { status: 200, statusText: 'OK', headers: { [REPLAYED_HEADER]: 'true' } });

    expect(outcome?.replayed).toBe(true);
  });

  it('encodes the reference when searching', () => {
    service.findByReference('REF 1/2').subscribe();
    http.expectOne('/api/credit-applications/REF%201%2F2').flush(APPLICATION);
  });

  it('requests recent applications with a limit', () => {
    service.findRecent(5).subscribe();
    const req = http.expectOne((r) => r.url === '/api/credit-applications');
    expect(req.request.params.get('limit')).toBe('5');
    req.flush([]);
  });
});
