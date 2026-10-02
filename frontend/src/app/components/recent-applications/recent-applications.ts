import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, input, output } from '@angular/core';
import { CreditApplication } from '../../core/credit.models';
import { stationFor } from '../evaluation-route/evaluation-route';

@Component({
  selector: 'app-recent-applications',
  imports: [CurrencyPipe, DatePipe],
  templateUrl: './recent-applications.html',
  styleUrl: './recent-applications.css',
})
export class RecentApplications {
  readonly applications = input<CreditApplication[]>([]);
  readonly selectedReference = input<string | null>(null);
  readonly refresh = output<void>();
  readonly selected = output<CreditApplication>();

  protected readonly stationFor = stationFor;
}
