import { CurrencyPipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { Customer } from '../../core/credit.models';

/** Cupo por cliente: cuánto está aprobado y cuánto queda disponible. */
@Component({
  selector: 'app-customer-credit',
  imports: [CurrencyPipe],
  templateUrl: './customer-credit.html',
  styleUrl: './customer-credit.css',
})
export class CustomerCredit {
  readonly customers = input<Customer[]>([]);

  usage(customer: Customer): number {
    return customer.creditLimit > 0 ? Math.min(customer.approvedAmount / customer.creditLimit, 1) : 0;
  }
}
