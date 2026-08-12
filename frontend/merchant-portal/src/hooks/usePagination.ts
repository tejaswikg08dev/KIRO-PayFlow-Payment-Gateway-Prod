import { useState, useCallback } from 'react';

interface UsePaginationOptions {
  initialPage?: number;
  initialSize?: number;
}

export function usePagination(options: UsePaginationOptions = {}) {
  const { initialPage = 0, initialSize = 20 } = options;
  const [page, setPage] = useState(initialPage);
  const [size, setSize] = useState(initialSize);

  const nextPage = useCallback(() => {
    setPage((prev) => prev + 1);
  }, []);

  const prevPage = useCallback(() => {
    setPage((prev) => Math.max(0, prev - 1));
  }, []);

  const goToPage = useCallback((pageNumber: number) => {
    setPage(Math.max(0, pageNumber));
  }, []);

  const changeSize = useCallback((newSize: number) => {
    setSize(newSize);
    setPage(0);
  }, []);

  const reset = useCallback(() => {
    setPage(initialPage);
    setSize(initialSize);
  }, [initialPage, initialSize]);

  return {
    page,
    size,
    nextPage,
    prevPage,
    goToPage,
    changeSize,
    reset,
  };
}
