import { useQuery } from '@tanstack/react-query';
import { paymentService } from '@/services/paymentService';
import { TransactionFilter } from '@/types/payment.types';

export function useTransactions(filter: TransactionFilter) {
  return useQuery({
    queryKey: ['transactions', filter],
    queryFn: () => paymentService.getTransactions(filter),
    placeholderData: (previousData) => previousData,
  });
}

export function useTransaction(id: string) {
  return useQuery({
    queryKey: ['transaction', id],
    queryFn: () => paymentService.getTransaction(id),
    enabled: !!id,
  });
}
