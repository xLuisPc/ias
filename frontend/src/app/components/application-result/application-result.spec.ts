import { registerLocaleData } from '@angular/common';
import localeEsCo from '@angular/common/locales/es-CO';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CreditApplication, ResultView } from '../../core/credit.models';
import { ApplicationResult } from './application-result';

registerLocaleData(localeEsCo);

const REJECTED: CreditApplication = {
  applicationReference: 'REF-002',
  customerId: 'CLI-1002',
  amount: 1000000,
  termMonths: 12,
  status: 'REJECTED',
  rejectionReason: 'CUSTOMER_NOT_ELIGIBLE',
  message: 'El cliente no está habilitado para solicitar crédito',
  processedAt: '2026-01-15T10:00:00Z',
};

async function render(result: ResultView): Promise<HTMLElement> {
  TestBed.configureTestingModule({ providers: [{ provide: LOCALE_ID, useValue: 'es-CO' }] });
  const fixture = TestBed.createComponent(ApplicationResult);
  fixture.componentRef.setInput('result', result);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('ApplicationResult', () => {
  it('shows a rejected application with its reason', async () => {
    const el = await render({ kind: 'created', application: REJECTED });

    expect(el.querySelector('.status')?.textContent).toContain('Rechazada');
    expect(el.querySelector('.status')?.textContent).toContain('en Estado');
    expect(el.textContent).toContain('El cliente no está habilitado');
    expect(el.textContent).toContain('CUSTOMER_NOT_ELIGIBLE');
    expect(el.querySelector('.station[data-state="stopped"] .name')?.textContent).toContain('Estado');
  });

  it('shows an approved application without a reason', async () => {
    const el = await render({
      kind: 'created',
      application: { ...REJECTED, status: 'APPROVED', rejectionReason: null, message: 'Solicitud aprobada' },
    });

    expect(el.querySelector('.status')?.textContent).toContain('Aprobada');
    expect(el.textContent).not.toContain('Código');
    expect(el.querySelectorAll('.station[data-state="passed"]').length).toBe(5);
    expect(el.querySelector('.terminal')?.getAttribute('data-state')).toBe('arrived');
  });

  it('warns when the reference was already processed', async () => {
    const el = await render({ kind: 'replayed', application: REJECTED });

    expect(el.querySelector('.notice[data-tone="info"]')?.textContent).toContain('ya se había procesado');
  });
});
