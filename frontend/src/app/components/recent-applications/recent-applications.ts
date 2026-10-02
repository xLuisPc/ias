import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, input, output } from '@angular/core';
import { CreditApplication } from '../../core/credit.models';

@Component({
  selector: 'app-recent-applications',
  imports: [CurrencyPipe, DatePipe],
  templateUrl: './recent-applications.html',
})
export class RecentApplications {
  readonly applications = input<CreditApplication[]>([]);
  readonly refresh = output<void>();
  readonly selected = output<CreditApplication>();
}
