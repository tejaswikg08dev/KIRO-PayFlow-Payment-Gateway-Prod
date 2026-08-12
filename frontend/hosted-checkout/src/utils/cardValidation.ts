export function luhnCheck(cardNumber: string): boolean {
  const digits = cardNumber.replace(/\s/g, '');
  if (!/^\d+$/.test(digits) || digits.length < 13 || digits.length > 19) {
    return false;
  }

  let sum = 0;
  let isEven = false;

  for (let i = digits.length - 1; i >= 0; i--) {
    let digit = parseInt(digits[i], 10);

    if (isEven) {
      digit *= 2;
      if (digit > 9) {
        digit -= 9;
      }
    }

    sum += digit;
    isEven = !isEven;
  }

  return sum % 10 === 0;
}

export type CardBrand = 'visa' | 'mastercard' | 'amex' | 'rupay' | 'discover' | 'unknown';

export function detectCardBrand(cardNumber: string): CardBrand {
  const digits = cardNumber.replace(/\s/g, '');

  if (/^4/.test(digits)) return 'visa';
  if (/^(5[1-5]|2[2-7])/.test(digits)) return 'mastercard';
  if (/^3[47]/.test(digits)) return 'amex';
  if (/^(60|65|81|82|508)/.test(digits)) return 'rupay';
  if (/^(6011|65|644|645|646|647|648|649)/.test(digits)) return 'discover';

  return 'unknown';
}

export function formatCardNumber(value: string): string {
  const digits = value.replace(/\D/g, '');
  const brand = detectCardBrand(digits);

  if (brand === 'amex') {
    return digits.replace(/(\d{4})(\d{6})(\d{5})/, '$1 $2 $3').trim();
  }

  return digits.replace(/(\d{4})(?=\d)/g, '$1 ').trim();
}

export function formatExpiry(value: string): string {
  const digits = value.replace(/\D/g, '');
  if (digits.length >= 2) {
    return `${digits.substring(0, 2)}/${digits.substring(2, 4)}`;
  }
  return digits;
}

export function isValidExpiry(expiry: string): boolean {
  const match = expiry.match(/^(\d{2})\/(\d{2})$/);
  if (!match) return false;

  const month = parseInt(match[1], 10);
  const year = parseInt(match[2], 10) + 2000;

  if (month < 1 || month > 12) return false;

  const now = new Date();
  const expiryDate = new Date(year, month);
  return expiryDate > now;
}

export function isValidCvv(cvv: string, brand: CardBrand): boolean {
  const length = brand === 'amex' ? 4 : 3;
  return new RegExp(`^\\d{${length}}$`).test(cvv);
}
