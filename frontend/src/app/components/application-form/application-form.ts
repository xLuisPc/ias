import { CurrencyPipe } from '@angular/common';
import { Component, input, output } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { CreditApplicationRequest, Customer } from '../../core/credit.models';

/** Genera una referencia legible y practicamente unica para facilitar las pruebas manuales. */
export function newReference(): string {
  return `REF-${Date.now().toString(36).toUpperCase()}`;
}

/**
 * Formulario de registro. Solo valida la forma (campos obligatorios); las reglas de negocio
 * (monto > 0, plazo 6-60, cliente habilitado, cupo) las decide el backend para no duplicarlas.
 */
@Component({
  selector: 'app-application-form',
  imports: [ReactiveFormsModule, CurrencyPipe],
  templateUrl: './application-form.html',
})
export class ApplicationForm {
  readonly customers = input<Customer[]>([]);
  readonly submitting = input(false);
  readonly submitted = output<CreditApplicationRequest>();

  readonly form = new FormGroup({
    applicationReference: new FormControl(newReference(), {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(50)],
    }),
    customerId: new FormControl('CLI-1001', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(50)],
    }),
    amount: new FormControl<number | null>(6000000, Validators.required),
    termMonths: new FormControl<number | null>(24, Validators.required),
  });

  regenerateReference(): void {
    this.form.controls.applicationReference.setValue(newReference());
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.submitted.emit({
      applicationReference: value.applicationReference.trim(),
      customerId: value.customerId.trim(),
      amount: Number(value.amount),
      termMonths: Number(value.termMonths),
    });
  }

  isInvalid(control: keyof typeof this.form.controls): boolean {
    const c = this.form.controls[control];
    return c.invalid && c.touched;
  }
}
