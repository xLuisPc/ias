import { TestBed } from '@angular/core/testing';
import { CreditApplication } from '../../core/credit.models';
import { EvaluationRoute, stationFor } from './evaluation-route';

const BASE: CreditApplication = {
  applicationReference: 'REF-001',
  customerId: 'CLI-1001',
  amount: 6000000,
  termMonths: 24,
  status: 'REJECTED',
  rejectionReason: 'CREDIT_LIMIT_EXCEEDED',
  message: 'La solicitud supera el cupo',
  processedAt: '2026-01-15T10:00:00Z',
};

async function render(application: CreditApplication | null): Promise<HTMLElement> {
  const fixture = TestBed.createComponent(EvaluationRoute);
  fixture.componentRef.setInput('application', application);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

const states = (el: HTMLElement) =>
  [...el.querySelectorAll('.station:not(.terminal)')].map((s) => s.getAttribute('data-state'));

describe('EvaluationRoute', () => {
  it('stops at the station of the rejection reason, in the backend evaluation order', async () => {
    const el = await render({ ...BASE, rejectionReason: 'CUSTOMER_NOT_ELIGIBLE' });
    expect(states(el)).toEqual(['passed', 'passed', 'passed', 'stopped', 'skipped']);
  });

  it('reaches the end of the line when the application is approved', async () => {
    const el = await render({ ...BASE, status: 'APPROVED', rejectionReason: null });
    expect(states(el)).toEqual(['passed', 'passed', 'passed', 'passed', 'passed']);
    expect(el.querySelector('.terminal')?.getAttribute('data-state')).toBe('arrived');
  });

  it('stays idle without an application', async () => {
    const el = await render(null);
    expect(states(el).every((s) => s === 'idle')).toBe(true);
  });

  it('names the station for each rejection reason', () => {
    expect(stationFor('INVALID_AMOUNT')).toBe('Monto');
    expect(stationFor('CREDIT_LIMIT_EXCEEDED')).toBe('Cupo');
    expect(stationFor(null)).toBeNull();
  });
});
